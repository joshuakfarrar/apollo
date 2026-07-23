package io.github.joshuakfarrar.apollo.http4s

import cats.effect.IO
import cats.implicits.*
import org.http4s.*
import org.http4s.headers.Location
import org.http4s.implicits.*

class AuthRoutesSuite extends ApolloTestKit:

  test("GET /login renders the auth template when the CSRF token is present") {
    newWorld().flatMap { w =>
      w.routes.run(withToken(w, Request[IO](Method.GET, uri"/login"))).flatMap {
        res =>
          assertEquals(res.status, Status.Ok)
          res.as[String].map(body => assert(body.contains("auth:login")))
      }
    }
  }

  test("GET /login is forbidden without a CSRF token") {
    newWorld().flatMap { w =>
      w.routes
        .run(Request[IO](Method.GET, uri"/login"))
        .map(res => assertEquals(res.status, Status.Forbidden))
    }
  }

  test("registration without confirmation signs the user in immediately") {
    newWorld().flatMap { w =>
      register(w).flatMap { res =>
        assertEquals(res.status, Status.SeeOther)
        assertEquals(res.headers.get[Location].map(_.uri), Some(uri"/"))
        assert(sessionCookie(res).isDefined)
        (w.mails.get, w.users.get).tupled.map { (mails, users) =>
          assertEquals(mails, List.empty)
          assert(users.contains("a@b.c"))
        }
      }
    }
  }

  test(
    "registration with confirmation sends a confirmation e-mail and does not sign in"
  ) {
    newWorld(confirmationEnabled = true).flatMap { w =>
      register(w).flatMap { res =>
        assertEquals(res.status, Status.SeeOther)
        assertEquals(res.headers.get[Location].map(_.uri), Some(uri"/login"))
        assert(sessionCookie(res).isEmpty)
        w.mails.get.map { mails =>
          assert(mails.exists(_.startsWith("confirm|a@b.c|")))
        }
      }
    }
  }

  test("registration rejects mismatched passwords") {
    newWorld().flatMap { w =>
      w.routes
        .run(
          Request[IO](Method.POST, uri"/register").withEntity(
            UrlForm(
              "name" -> "T",
              "email" -> "a@b.c",
              "password" -> "one1",
              "confirmPassword" -> "two2"
            )
          )
        )
        .flatMap { res =>
          flashMessage(res).flatMap { msg =>
            assertEquals(msg, Some("Password and confirmation did not match"))
            w.users.get.map(users => assert(users.isEmpty))
          }
        }
    }
  }

  test("registering an existing e-mail reports a duplicate") {
    newWorld().flatMap { w =>
      register(w) >> register(w).flatMap { res =>
        flashMessage(res).map(msg =>
          assertEquals(msg, Some("User with that e-mail already exists"))
        )
      }
    }
  }

  test("login with valid credentials issues a session cookie") {
    newWorld().flatMap { w =>
      register(w) >> login(w, "a@b.c", "password1").map { res =>
        assertEquals(res.status, Status.SeeOther)
        assert(sessionCookie(res).isDefined)
      }
    }
  }

  test("login with a wrong password does not issue a session") {
    newWorld().flatMap { w =>
      register(w) >> login(w, "a@b.c", "wrong").flatMap { res =>
        assert(sessionCookie(res).isEmpty)
        flashMessage(res).map(msg =>
          assertEquals(
            msg,
            Some("Could not find user with that email or password")
          )
        )
      }
    }
  }

  test("login with an unknown e-mail uses the same error as a wrong password") {
    newWorld().flatMap { w =>
      login(w, "nobody@b.c", "whatever").flatMap { res =>
        assert(sessionCookie(res).isEmpty)
        flashMessage(res).map(msg =>
          assertEquals(
            msg,
            Some("Could not find user with that email or password")
          )
        )
      }
    }
  }

  test("login is refused for unconfirmed users when confirmation is enabled") {
    newWorld(confirmationEnabled = true).flatMap { w =>
      register(w) >> login(w, "a@b.c", "password1").flatMap { res =>
        assert(sessionCookie(res).isEmpty)
        flashMessage(res).map(msg =>
          assertEquals(
            msg,
            Some("Please confirm your account before logging in")
          )
        )
      }
    }
  }

  test("login succeeds after the account is confirmed") {
    newWorld(confirmationEnabled = true).flatMap { w =>
      for {
        _ <- register(w)
        id <- w.users.get.map(_("a@b.c").id)
        confirmRes <- w.routes.run(
          Request[IO](Method.GET, Uri.unsafeFromString(s"/confirm/confcode-$id"))
        )
        _ = assertEquals(confirmRes.status, Status.SeeOther)
        loginRes <- login(w, "a@b.c", "password1")
      } yield assert(sessionCookie(loginRes).isDefined)
    }
  }

  test("GET /confirm is not served when confirmation is disabled") {
    newWorld().flatMap { w =>
      w.routes
        .run(Request[IO](Method.GET, uri"/confirm/whatever"))
        .map(res => assertEquals(res.status, Status.NotFound))
    }
  }

  test("requesting a password reset sends a mail with the code") {
    newWorld().flatMap { w =>
      register(w) >> w.routes
        .run(
          Request[IO](Method.POST, uri"/reset")
            .withEntity(UrlForm("email" -> "a@b.c"))
        )
        .flatMap { res =>
          assertEquals(res.status, Status.SeeOther)
          w.mails.get.map(mails =>
            assert(mails.exists(_.startsWith("reset|a@b.c|")))
          )
        }
    }
  }

  test("completing a reset updates the password and invalidates the code") {
    newWorld().flatMap { w =>
      for {
        _ <- register(w)
        _ <- w.routes.run(
          Request[IO](Method.POST, uri"/reset")
            .withEntity(UrlForm("email" -> "a@b.c"))
        )
        code <- w.resets.get.map(_.keys.head)
        res <- w.routes.run(
          Request[IO](Method.POST, Uri.unsafeFromString(s"/reset/$code"))
            .withEntity(
              UrlForm(
                "password" -> "newpassword9",
                "confirmPassword" -> "newpassword9"
              )
            )
        )
        _ = assertEquals(res.status, Status.SeeOther)
        remaining <- w.resets.get
        _ = assert(remaining.isEmpty)
        oldLogin <- login(w, "a@b.c", "password1")
        newLogin <- login(w, "a@b.c", "newpassword9")
      } yield {
        assert(sessionCookie(oldLogin).isEmpty)
        assert(sessionCookie(newLogin).isDefined)
      }
    }
  }

  test("logout deletes the session and clears the cookie") {
    newWorld().flatMap { w =>
      for {
        regRes <- register(w) // registration signs in, creating the session
        token = sessionCookie(regRes).getOrElse(fail("no session issued"))
        res <- w.routes.run(
          Request[IO](Method.POST, uri"/logout")
            .addCookie("session_token", token)
        )
        sessions <- w.sessions.get
      } yield {
        assertEquals(res.status, Status.SeeOther)
        assert(sessions.isEmpty)
        assert(
          res.cookies.exists(c => c.name == "session_token" && c.content.isEmpty)
        )
      }
    }
  }
