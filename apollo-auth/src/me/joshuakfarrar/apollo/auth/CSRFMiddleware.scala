package me.joshuakfarrar.apollo.auth

import cats.data.Kleisli
import cats.effect.Sync
import org.http4s.{Request, Response, Status}
import org.http4s.headers.Cookie as HCookie
import org.http4s.server.middleware.CSRF
import org.typelevel.vault.Key
import cats.implicits.*
import org.http4s.server.middleware.CSRF.CSRFCheckFailed

object CSRFMiddleware {
  def validate[F[_] : Sync](
                             csrf: CSRF[F, F],
                             tokenKey: Key[String]
                           )(
                             app: Kleisli[F, Request[F], Response[F]]
                           ): Kleisli[F, Request[F], Response[F]] = {
    def handleSafe(r: Request[F])(implicit F: Sync[F]): F[Response[F]] =
      r.headers
        .get[HCookie]
        .flatMap(_.values.find(_.name == "csrf-token")) match {
        case Some(c) =>
          (for {
            raw <- F.fromEither(csrf.extractRaw(c.content))
            newToken <- csrf.signToken[F](raw)
            updatedReq = r.withAttribute(tokenKey, newToken.toString)
            res <- app(updatedReq)
          } yield res.addCookie(csrf.createResponseCookie(newToken)))
            .recover { case CSRFCheckFailed =>
              Response[F](Status.Forbidden)
            }
        case None =>
          for {
            token <- csrf.generateToken[F]
            updatedReq = r.withAttribute(tokenKey, token.toString)
            resp <- app(updatedReq)
          } yield resp.addCookie(csrf.createResponseCookie(token))
      }

    Kleisli { req =>
      if (req.method.isSafe) handleSafe(req)
      else csrf.checkCSRF(req, app(req))
    }
  }
}