package io.github.joshuakfarrar.apollo.http4s

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.*
import io.github.joshuakfarrar.apollo.http4s.atproto.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import play.twirl.api.Html

import java.util.UUID

/** Drives the full OAuth flow against an in-process fake Atmosphere:
  * a handle host, a PLC directory, a PDS, and an authorization server
  * that enforces the DPoP nonce dance and cryptographically verifies
  * every DPoP proof our client produces.
  */
class AtprotoOAuthSuite extends ApolloTestKit:

  private val handle = "alice.test"
  private val did = "did:plc:alice123"
  private val issuer = "https://auth.test"

  private case class FakeAtmosphere(
      nonce: String = "server-nonce-1",
      parJwk: Option[String] = None,
      challenge: Option[String] = None,
      authState: Option[String] = None,
      code: Option[String] = None,
      subOverride: Option[String] = None,
      parCalls: Int = 0,
      tokenCalls: Int = 0
  )

  /** Parses and verifies a DPoP proof like a real server: signature
    * against the embedded JWK, htm/htu binding, and the nonce dance.
    * Returns Left(challenge response) when a nonce must be (re)supplied.
    */
  private def checkDpop(
      request: Request[IO],
      expectedHtu: String,
      currentNonce: String
  ): Either[Response[IO], String] = {
    val proof = request.headers
      .get(CIString("DPoP"))
      .map(_.head.value)
      .getOrElse(fail("request carried no DPoP header"))
    val Array(h, p, _) = proof.split('.')
    val header = Jose.decodeJwtPart(h)
    val payload = Jose.decodeJwtPart(p)
    val jwk = header("jwk")

    assert(Jose.verifyJwt(proof, jwk), "DPoP proof signature must verify")
    assertEquals(header("typ").str, "dpop+jwt")
    assertEquals(payload("htm").str, "POST")
    assertEquals(payload("htu").str, expectedHtu)

    payload.obj.get("nonce").map(_.str) match {
      case Some(n) if n == currentNonce => Right(jwk.render())
      case _ =>
        Left(
          Response[IO](Status.BadRequest)
            .withEntity("""{"error":"use_dpop_nonce"}""")
            .putHeaders(Header.Raw(CIString("DPoP-Nonce"), currentNonce))
        )
    }
  }

  private def fakeAtmosphere(ref: Ref[IO, FakeAtmosphere]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { request =>
      val host = request.uri.host.map(_.value).getOrElse("")
      (host, request.method, request.uri.path.renderString) match {

        case ("alice.test", Method.GET, "/.well-known/atproto-did") =>
          Ok(did)

        case ("plc.test", Method.GET, p) if p == s"/$did" =>
          Ok(
            ujson
              .Obj(
                "id" -> did,
                "alsoKnownAs" -> ujson.Arr(s"at://$handle"),
                "service" -> ujson.Arr(
                  ujson.Obj(
                    "id" -> "#atproto_pds",
                    "type" -> "AtprotoPersonalDataServer",
                    "serviceEndpoint" -> "https://pds.test"
                  )
                )
              )
              .render()
          )

        case ("pds.test", Method.GET, "/.well-known/oauth-protected-resource") =>
          Ok(ujson.Obj("authorization_servers" -> ujson.Arr(issuer)).render())

        case ("auth.test", Method.GET, "/.well-known/oauth-authorization-server") =>
          Ok(
            ujson
              .Obj(
                "issuer" -> issuer,
                "authorization_endpoint" -> s"$issuer/authorize",
                "token_endpoint" -> s"$issuer/token",
                "pushed_authorization_request_endpoint" -> s"$issuer/par"
              )
              .render()
          )

        case ("auth.test", Method.POST, "/par") =>
          ref.update(s => s.copy(parCalls = s.parCalls + 1)) *>
            ref.get.flatMap { server =>
              checkDpop(request, s"$issuer/par", server.nonce) match {
                case Left(challenge) => IO.pure(challenge)
                case Right(jwk) =>
                  request.as[UrlForm].flatMap { form =>
                    assertEquals(form.getFirst("code_challenge_method"), Some("S256"))
                    assertEquals(form.getFirst("response_type"), Some("code"))
                    ref.update(
                      _.copy(
                        parJwk = Some(jwk),
                        challenge = form.getFirst("code_challenge"),
                        authState = form.getFirst("state"),
                        code = Some("code-" + UUID.randomUUID().toString)
                      )
                    ) *> Created(
                      ujson
                        .Obj(
                          "request_uri" -> "urn:ietf:params:oauth:request_uri:req-1",
                          "expires_in" -> 60
                        )
                        .render()
                    )
                  }
              }
            }

        case ("auth.test", Method.POST, "/token") =>
          ref.update(s => s.copy(tokenCalls = s.tokenCalls + 1)) *>
            ref.get.flatMap { server =>
              checkDpop(request, s"$issuer/token", server.nonce) match {
                case Left(challenge) => IO.pure(challenge)
                case Right(jwk) =>
                  request.as[UrlForm].flatMap { form =>
                    // the token request must use the same DPoP key as PAR
                    assertEquals(Some(jwk), server.parJwk)
                    assertEquals(form.getFirst("grant_type"), Some("authorization_code"))
                    assertEquals(form.getFirst("code"), server.code)
                    // PKCE: S256(verifier) must equal the pushed challenge
                    assertEquals(
                      form.getFirst("code_verifier").map(Pkce.challenge),
                      server.challenge
                    )
                    Ok(
                      ujson
                        .Obj(
                          "access_token" -> "fake-access-token",
                          "token_type" -> "DPoP",
                          "sub" -> server.subOverride.getOrElse(did)
                        )
                        .render()
                    )
                  }
              }
            }

        case _ => NotFound(s"fake atmosphere: no route for $host ${request.uri.path}")
      }
    })

  private val oauthConfig = AtprotoOAuthConfig(
    clientId = uri"https://app.test/oauth/client-metadata.json",
    redirectUri = uri"https://app.test/atproto/callback",
    plcDirectory = uri"https://plc.test"
  )

  private def stubStore(
      ref: Ref[IO, Map[String, AtprotoOAuthRequest]]
  ): AtprotoOAuthRequestStore[IO] =
    new AtprotoOAuthRequestStore[IO]:
      def create(request: AtprotoOAuthRequest): IO[Unit] =
        ref.update(_ + (request.state -> request))
      def consume(state: String): EitherT[IO, AuthError, AtprotoOAuthRequest] =
        EitherT(ref.modify { m =>
          m.get(state) match {
            case Some(req) => (m - state, req.asRight)
            case None      => (m, AuthError.OAuthRequestNotFound.asLeft)
          }
        })

  private def stubIdentities(
      w: World
  ): AtprotoIdentityService[IO, TestUser, UUID] =
    new AtprotoIdentityService[IO, TestUser, UUID]:
      def findByDid(did: String): EitherT[IO, AuthError, TestUser] =
        EitherT.fromOptionF(w.users.get.map(_.get(did)), AuthError.UserNotFound)
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

  private case class OAuthWorld(
      w: World,
      app: HttpApp[IO],
      server: Ref[IO, FakeAtmosphere]
  )

  private def newOAuthWorld(
      mutate: FakeAtmosphere => FakeAtmosphere = identity
  ): IO[OAuthWorld] =
    for {
      w <- newWorld()
      server <- Ref.of[IO, FakeAtmosphere](mutate(FakeAtmosphere()))
      requests <- Ref.of[IO, Map[String, AtprotoOAuthRequest]](Map.empty)
      oauth = AtprotoOAuthClient(
        fakeAtmosphere(server),
        oauthConfig,
        stubStore(requests)
      )
      app = AtprotoRoutes
        .routes(
          w.apollo,
          oauth,
          stubIdentities(w),
          (_, flash) => Html(s"atproto-form:${flash.map(_.message).getOrElse("")}")
        )
        .orNotFound
    } yield OAuthWorld(w, app, server)

  private def startLogin(ow: OAuthWorld): IO[Response[IO]] =
    ow.app.run(
      Request[IO](Method.POST, uri"/atproto/login")
        .withEntity(UrlForm("identifier" -> handle))
    )

  test("the client metadata document is served as JSON") {
    newOAuthWorld().flatMap { ow =>
      ow.app.run(Request[IO](Method.GET, uri"/oauth/client-metadata.json")).flatMap {
        res =>
          assertEquals(res.status, Status.Ok)
          res.as[String].map { body =>
            val json = ujson.read(body)
            assertEquals(json("client_id").str, oauthConfig.clientId.renderString)
            assertEquals(json("token_endpoint_auth_method").str, "none")
            assertEquals(json("dpop_bound_access_tokens").bool, true)
          }
      }
    }
  }

  test("POST /atproto/login resolves, pushes the request, and redirects to the authorization server") {
    newOAuthWorld().flatMap { ow =>
      startLogin(ow).flatMap { res =>
        assertEquals(res.status, Status.SeeOther)
        val location = res.headers.get[Location].map(_.uri).getOrElse(fail("no Location"))
        assertEquals(location.host.map(_.value), Some("auth.test"))
        assertEquals(location.path.renderString, "/authorize")
        assertEquals(
          location.query.params.get("request_uri"),
          Some("urn:ietf:params:oauth:request_uri:req-1")
        )
        ow.server.get.map { server =>
          assertEquals(server.parCalls, 2, "PAR must do the DPoP nonce dance")
          assert(server.authState.isDefined)
          assert(server.challenge.isDefined)
        }
      }
    }
  }

  test("the callback exchanges the code and signs in a newly provisioned user") {
    newOAuthWorld().flatMap { ow =>
      for {
        _ <- startLogin(ow)
        server <- ow.server.get
        state = server.authState.getOrElse(fail("no state pushed"))
        code = server.code.getOrElse(fail("no code minted"))
        res <- ow.app.run(
          Request[IO](
            Method.GET,
            (uri"/atproto/callback")
              .withQueryParam("state", state)
              .withQueryParam("iss", issuer)
              .withQueryParam("code", code)
          )
        )
        users <- ow.w.users.get
        finalServer <- ow.server.get
      } yield {
        assertEquals(res.status, Status.SeeOther)
        assert(sessionCookie(res).isDefined, "callback must sign the user in")
        assertEquals(users.size, 1)
        assertEquals(users(did).name, handle)
        assertEquals(finalServer.tokenCalls, 2, "token exchange must do the DPoP nonce dance")
      }
    }
  }

  test("a returning atproto user is not provisioned twice") {
    newOAuthWorld().flatMap { ow =>
      def fullLogin: IO[Response[IO]] =
        for {
          _ <- startLogin(ow)
          server <- ow.server.get
          res <- ow.app.run(
            Request[IO](
              Method.GET,
              (uri"/atproto/callback")
                .withQueryParam("state", server.authState.get)
                .withQueryParam("iss", issuer)
                .withQueryParam("code", server.code.get)
            )
          )
        } yield res

      for {
        first <- fullLogin
        second <- fullLogin
        users <- ow.w.users.get
      } yield {
        assert(sessionCookie(first).isDefined)
        assert(sessionCookie(second).isDefined)
        assertEquals(users.size, 1)
      }
    }
  }

  test("a state cannot be redeemed twice") {
    newOAuthWorld().flatMap { ow =>
      for {
        _ <- startLogin(ow)
        server <- ow.server.get
        callback = Request[IO](
          Method.GET,
          (uri"/atproto/callback")
            .withQueryParam("state", server.authState.get)
            .withQueryParam("iss", issuer)
            .withQueryParam("code", server.code.get)
        )
        _ <- ow.app.run(callback)
        replay <- ow.app.run(callback)
        msg <- flashMessage(replay)
      } yield {
        assert(sessionCookie(replay).isEmpty)
        assertEquals(msg, Some("This sign-in link expired. Please try again"))
      }
    }
  }

  test("a token response for the wrong DID is rejected") {
    newOAuthWorld(_.copy(subOverride = Some("did:plc:mallory999"))).flatMap { ow =>
      for {
        _ <- startLogin(ow)
        server <- ow.server.get
        res <- ow.app.run(
          Request[IO](
            Method.GET,
            (uri"/atproto/callback")
              .withQueryParam("state", server.authState.get)
              .withQueryParam("iss", issuer)
              .withQueryParam("code", server.code.get)
          )
        )
        users <- ow.w.users.get
      } yield {
        assert(sessionCookie(res).isEmpty, "mismatched sub must not sign in")
        assert(users.isEmpty)
      }
    }
  }

  test("a callback from the wrong issuer is rejected") {
    newOAuthWorld().flatMap { ow =>
      for {
        _ <- startLogin(ow)
        server <- ow.server.get
        res <- ow.app.run(
          Request[IO](
            Method.GET,
            (uri"/atproto/callback")
              .withQueryParam("state", server.authState.get)
              .withQueryParam("iss", "https://evil.test")
              .withQueryParam("code", server.code.get)
          )
        )
      } yield assert(sessionCookie(res).isEmpty)
    }
  }

  test("an unresolvable handle reports a friendly error") {
    newOAuthWorld().flatMap { ow =>
      ow.app
        .run(
          Request[IO](Method.POST, uri"/atproto/login")
            .withEntity(UrlForm("identifier" -> "nobody.invalid"))
        )
        .flatMap { res =>
          assert(sessionCookie(res).isEmpty)
          flashMessage(res).map(msg =>
            assertEquals(
              msg,
              Some("Could not verify that handle. Check it and try again")
            )
          )
        }
    }
  }

  test("a denied authorization redirects with a cancellation message") {
    newOAuthWorld().flatMap { ow =>
      ow.app
        .run(
          Request[IO](
            Method.GET,
            (uri"/atproto/callback").withQueryParam("error", "access_denied")
          )
        )
        .flatMap(res => flashMessage(res).map(msg => assertEquals(msg, Some("Sign-in was cancelled"))))
    }
  }
