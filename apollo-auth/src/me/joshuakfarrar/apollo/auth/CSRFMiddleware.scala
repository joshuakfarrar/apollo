package me.joshuakfarrar.apollo.auth

import cats.Applicative
import cats.data.Kleisli
import cats.effect.{Async, Sync}
import cats.implicits.*
import org.http4s.headers.Cookie as HCookie
import org.http4s.server.middleware.CSRF
import org.http4s.server.middleware.CSRF.CSRFCheckFailed
import org.http4s.{Request, RequestCookie, Response}
import org.typelevel.vault.Key

object CSRFMiddleware {
  def validate[F[_]: Async, G[_]: Applicative](
      csrf: CSRF[F, G],
      csrfCookieName: String, // this is gross but CSRF.CookieSettings is private
      tokenKey: Key[String]
  )(
      app: Kleisli[F, Request[G], Response[G]]
  ): Kleisli[F, Request[G], Response[G]] = {
    def cookieFromHeaders(
        request: Request[G],
        cookieName: String
    ): Option[RequestCookie] =
      request.headers
        .get[HCookie]
        .flatMap(_.values.find(_.name == cookieName))

    def handleSafe(r: Request[G])(implicit F: Async[F]): F[Response[G]] =
      cookieFromHeaders(r, csrfCookieName) match {
        case Some(c) =>
          (for {
            raw <- csrf.extractRaw[F](c.content).flatMap(F.fromEither)
            newToken <- csrf.signToken[F](raw)
            updatedReq = r.withAttribute(tokenKey, newToken.toString)
            res <- app(updatedReq)
          } yield res.addCookie(csrf.createResponseCookie(newToken)))
            .recoverWith { case CSRFCheckFailed =>
              csrf.onfailureF
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
