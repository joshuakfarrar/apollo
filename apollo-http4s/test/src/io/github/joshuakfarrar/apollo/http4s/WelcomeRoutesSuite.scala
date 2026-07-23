package io.github.joshuakfarrar.apollo.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.implicits.*
import play.twirl.api.Html

class WelcomeRoutesSuite extends ApolloTestKit:

  private def welcome(w: World): HttpApp[IO] =
    WelcomeRoutes
      .routes(
        w.apollo,
        (_, user) => Html(s"welcome:${user.getOrElse("anonymous")}")
      )
      .orNotFound

  test("shows the anonymous state without a session") {
    newWorld().flatMap { w =>
      welcome(w).run(withToken(w, Request[IO](Method.GET, uri"/"))).flatMap {
        res =>
          assertEquals(res.status, Status.Ok)
          res.as[String].map(body => assert(body.contains("welcome:anonymous")))
      }
    }
  }

  test("shows the signed-in user's e-mail") {
    newWorld().flatMap { w =>
      for {
        regRes <- register(w)
        token = sessionCookie(regRes).getOrElse(fail("no session issued"))
        res <- welcome(w).run(
          withToken(
            w,
            Request[IO](Method.GET, uri"/").addCookie("session_token", token)
          )
        )
        body <- res.as[String]
      } yield assert(body.contains("welcome:a@b.c"))
    }
  }

  test("is forbidden without a CSRF token") {
    newWorld().flatMap { w =>
      welcome(w)
        .run(Request[IO](Method.GET, uri"/"))
        .map(res => assertEquals(res.status, Status.Forbidden))
    }
  }
