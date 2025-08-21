package me.joshuakfarrar.apollo.auth

import cats.data.Kleisli
import cats.effect.Sync
import org.http4s.headers.Cookie
import org.http4s.{Header, HttpDate, HttpRoutes, Request, ResponseCookie}

import java.time.Instant

object FlashMiddleware {
  def httpRoutes[F[_] : Sync](service: HttpRoutes[F]): HttpRoutes[F] = Kleisli {
    (req: Request[F]) =>
      service(req).map { response =>
        val hasFlashCookie = req.headers
          .get[Cookie]
          .exists(_.values.exists(_.name == "flash"))

        if (hasFlashCookie) {
          val deleteFlashCookie = ResponseCookie(
            name = "flash",
            content = "",
            expires = Some(HttpDate.unsafeFromInstant(Instant.EPOCH)),
            path = Some("/")
          )
          response.addCookie(deleteFlashCookie)
        } else {
          response
        }
      }
  }
}
