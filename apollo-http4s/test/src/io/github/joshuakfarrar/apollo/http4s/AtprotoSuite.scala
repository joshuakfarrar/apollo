package io.github.joshuakfarrar.apollo.http4s

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.*
import org.http4s.*
import org.http4s.implicits.*
import play.twirl.api.Html

import java.util.UUID

class AtprotoSuite extends ApolloTestKit:

  private val validHandle = "josh.bsky.social"
  private val validAppPassword = "abcd-efgh-ijkl-mnop"
  private val did = "did:plc:testtesttesttest"

  private val stubAuthenticator = new AtprotoAuthenticator[IO]:
    def verify(
        identifier: String,
        appPassword: String
    ): EitherT[IO, AuthError, AtprotoAccount] =
      if (identifier == validHandle && appPassword == validAppPassword)
        EitherT.rightT(AtprotoAccount(did, validHandle))
      else
        EitherT.leftT(AuthError.AtprotoAuthFailed)

  private def stubIdentities(
      w: World
  ): AtprotoIdentityService[IO, TestUser, UUID] =
    new AtprotoIdentityService[IO, TestUser, UUID]:
      def findByDid(did: String): EitherT[IO, AuthError, TestUser] =
        EitherT.fromOptionF(
          w.users.get.map(_.get(did)), // provisioned users are keyed by DID
          AuthError.UserNotFound
        )

      def createFromAtproto(
          account: AtprotoAccount
      ): EitherT[IO, AuthError, TestUser] =
        EitherT.liftF {
          val user = TestUser(
            UUID.randomUUID(),
            account.handle,
            account.did,
            "!atproto!unusable",
            confirmed = true
          )
          w.users.update(_ + (account.did -> user)).as(user)
        }

  private def atprotoApp(w: World): HttpApp[IO] =
    AtprotoRoutes
      .routes(
        w.apollo,
        stubAuthenticator,
        stubIdentities(w),
        (_, flash) =>
          Html(s"atproto-form:${flash.map(_.message).getOrElse("")}")
      )
      .orNotFound

  private def atprotoLogin(
      w: World,
      identifier: String,
      password: String
  ): IO[Response[IO]] =
    atprotoApp(w).run(
      Request[IO](Method.POST, uri"/atproto/login").withEntity(
        UrlForm("identifier" -> identifier, "password" -> password)
      )
    )

  test("GET /atproto/login renders the form when the CSRF token is present") {
    newWorld().flatMap { w =>
      atprotoApp(w)
        .run(withToken(w, Request[IO](Method.GET, uri"/atproto/login")))
        .flatMap { res =>
          assertEquals(res.status, Status.Ok)
          res.as[String].map(body => assert(body.contains("atproto-form")))
        }
    }
  }

  test("GET /atproto/login is forbidden without a CSRF token") {
    newWorld().flatMap { w =>
      atprotoApp(w)
        .run(Request[IO](Method.GET, uri"/atproto/login"))
        .map(res => assertEquals(res.status, Status.Forbidden))
    }
  }

  test("first atproto sign-in provisions a user and issues a session") {
    newWorld().flatMap { w =>
      atprotoLogin(w, validHandle, validAppPassword).flatMap { res =>
        assertEquals(res.status, Status.SeeOther)
        assert(sessionCookie(res).isDefined)
        w.users.get.map { users =>
          assertEquals(users.size, 1)
          assertEquals(users(did).name, validHandle)
        }
      }
    }
  }

  test("a returning atproto sign-in reuses the linked user") {
    newWorld().flatMap { w =>
      for {
        first <- atprotoLogin(w, validHandle, validAppPassword)
        firstUser <- w.users.get.map(_(did))
        second <- atprotoLogin(w, validHandle, validAppPassword)
        users <- w.users.get
      } yield {
        assert(sessionCookie(second).isDefined)
        assertEquals(users.size, 1)
        assertEquals(users(did).id, firstUser.id)
      }
    }
  }

  test("a wrong app password does not sign in or provision anyone") {
    newWorld().flatMap { w =>
      atprotoLogin(w, validHandle, "wrong-password").flatMap { res =>
        assert(sessionCookie(res).isEmpty)
        flashMessage(res).flatMap { msg =>
          assertEquals(
            msg,
            Some("Could not verify that handle and app password")
          )
          w.users.get.map(users => assert(users.isEmpty))
        }
      }
    }
  }

  test("missing fields are rejected") {
    newWorld().flatMap { w =>
      atprotoApp(w)
        .run(
          Request[IO](Method.POST, uri"/atproto/login")
            .withEntity(UrlForm("identifier" -> validHandle))
        )
        .flatMap { res =>
          assert(sessionCookie(res).isEmpty)
          flashMessage(res).map(msg =>
            assertEquals(msg, Some("Missing handle or app password"))
          )
        }
    }
  }
