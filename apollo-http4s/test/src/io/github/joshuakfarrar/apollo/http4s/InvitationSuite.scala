package io.github.joshuakfarrar.apollo.http4s

import cats.effect.IO
import cats.implicits.*
import org.http4s.*
import org.http4s.headers.Location
import org.http4s.implicits.*

class InvitationSuite extends ApolloTestKit:

  private def inviteApp(w: World, requireAuth: Boolean): HttpApp[IO] =
    InvitationRoutes.routes(w.apollo, requireAuth).orNotFound

  private def inviteCookie(res: Response[IO]): Option[String] =
    res.cookies
      .find(c => c.name == "invite" && c.content.nonEmpty)
      .map(_.content)

  test("registration is refused without an invitation when enabled") {
    newWorld(invitationEnabled = true).flatMap { w =>
      register(w).flatMap { res =>
        flashMessage(res).flatMap { msg =>
          assertEquals(msg, Some("Registration is by invitation only"))
          w.users.get.map(users => assert(users.isEmpty))
        }
      }
    }
  }

  test("registration is refused with an invalid invitation code") {
    newWorld(invitationEnabled = true).flatMap { w =>
      w.routes
        .run(
          Request[IO](Method.POST, uri"/register")
            .withEntity(
              UrlForm(
                "name" -> "T",
                "email" -> "a@b.c",
                "password" -> "password1",
                "confirmPassword" -> "password1"
              )
            )
            .addCookie("invite", "bogus-code")
        )
        .flatMap { res =>
          flashMessage(res).flatMap { msg =>
            assertEquals(msg, Some("Invalid or expired invitation"))
            w.users.get.map(users => assert(users.isEmpty))
          }
        }
    }
  }

  test("the full invitation flow registers and signs in the invitee") {
    newWorld(invitationEnabled = true).flatMap { w =>
      for {
        // anyone may create an invitation in waitlist mode
        createRes <- inviteApp(w, requireAuth = false).run(
          Request[IO](Method.POST, uri"/invitations")
            .withEntity(UrlForm("email" -> "friend@b.c"))
        )
        _ = assertEquals(createRes.status, Status.SeeOther)
        mails <- w.mails.get
        _ = assert(mails.exists(_.startsWith("invite|friend@b.c|")))
        code <- w.invites.get.map(_.keys.head)

        // following the mailed link stores the code in a cookie
        landRes <- inviteApp(w, requireAuth = false).run(
          Request[IO](Method.GET, Uri.unsafeFromString(s"/invite/$code"))
        )
        _ = assertEquals(landRes.status, Status.SeeOther)
        _ = assertEquals(
          landRes.headers.get[Location].map(_.uri),
          Some(uri"/login")
        )
        cookie = inviteCookie(landRes).getOrElse(fail("no invite cookie set"))
        _ = assertEquals(cookie, code)

        // registering with the cookie consumes the invitation and signs in
        regRes <- w.routes.run(
          Request[IO](Method.POST, uri"/register")
            .withEntity(
              UrlForm(
                "name" -> "Friend",
                "email" -> "friend@b.c",
                "password" -> "password1",
                "confirmPassword" -> "password1"
              )
            )
            .addCookie("invite", cookie)
        )
        remaining <- w.invites.get
        users <- w.users.get
      } yield {
        assertEquals(regRes.status, Status.SeeOther)
        assert(sessionCookie(regRes).isDefined)
        assert(users.contains("friend@b.c"))
        assert(remaining.isEmpty, "invitation should be consumed")
        assert(
          regRes.cookies.exists(c => c.name == "invite" && c.content.isEmpty),
          "invite cookie should be cleared"
        )
      }
    }
  }

  test("a failed registration does not consume the invitation") {
    newWorld(invitationEnabled = true).flatMap { w =>
      for {
        _ <- inviteApp(w, requireAuth = false).run(
          Request[IO](Method.POST, uri"/invitations")
            .withEntity(UrlForm("email" -> "friend@b.c"))
        )
        code <- w.invites.get.map(_.keys.head)
        // password mismatch fails validation before user creation
        res <- w.routes.run(
          Request[IO](Method.POST, uri"/register")
            .withEntity(
              UrlForm(
                "name" -> "Friend",
                "email" -> "friend@b.c",
                "password" -> "one1",
                "confirmPassword" -> "two2"
              )
            )
            .addCookie("invite", code)
        )
        remaining <- w.invites.get
      } yield {
        assert(remaining.contains(code), "invitation should remain unconsumed")
      }
    }
  }

  test("anonymous invitation creation is refused when requireAuth is true") {
    newWorld(invitationEnabled = true).flatMap { w =>
      inviteApp(w, requireAuth = true)
        .run(
          Request[IO](Method.POST, uri"/invitations")
            .withEntity(UrlForm("email" -> "friend@b.c"))
        )
        .flatMap { res =>
          assertEquals(res.status, Status.Unauthorized)
          w.invites.get.map(invites => assert(invites.isEmpty))
        }
    }
  }

  test("a signed-in user can create an invitation when requireAuth is true") {
    newWorld(invitationEnabled = true).flatMap { w =>
      val member = TestUser(
        java.util.UUID.randomUUID(),
        "Member",
        "member@b.c",
        "hashed:password1",
        confirmed = true
      )
      for {
        // seed an existing member directly (registration is gated here)
        _ <- w.users.update(_ + (member.email -> member))
        loginRes <- login(w, "member@b.c", "password1")
        token = sessionCookie(loginRes).getOrElse(fail("no session issued"))
        res <- inviteApp(w, requireAuth = true).run(
          Request[IO](Method.POST, uri"/invitations")
            .withEntity(UrlForm("email" -> "friend@b.c"))
            .addCookie("session_token", token)
        )
        invites <- w.invites.get
        mails <- w.mails.get
      } yield {
        assertEquals(res.status, Status.SeeOther)
        assertEquals(invites.size, 1)
        assert(mails.exists(_.startsWith("invite|friend@b.c|")))
      }
    }
  }

  test("invitation routes are not served when the feature is disabled") {
    newWorld().flatMap { w =>
      for {
        createRes <- inviteApp(w, requireAuth = false).run(
          Request[IO](Method.POST, uri"/invitations")
            .withEntity(UrlForm("email" -> "friend@b.c"))
        )
        landRes <- inviteApp(w, requireAuth = false).run(
          Request[IO](Method.GET, uri"/invite/whatever")
        )
      } yield {
        assertEquals(createRes.status, Status.NotFound)
        assertEquals(landRes.status, Status.NotFound)
      }
    }
  }

  test("following an invalid invite link reports an error") {
    newWorld(invitationEnabled = true).flatMap { w =>
      inviteApp(w, requireAuth = false)
        .run(Request[IO](Method.GET, uri"/invite/bogus"))
        .flatMap { res =>
          assertEquals(res.status, Status.SeeOther)
          assert(inviteCookie(res).isEmpty)
          flashMessage(res).map(msg =>
            assertEquals(msg, Some("Invalid or expired invitation"))
          )
        }
    }
  }
