package me.joshuakfarrar.apollo.auth

import cats.effect.Async
import org.http4s.{Request, Response}
import org.http4s.headers.Cookie
import cats.implicits.*
import org.http4s.dsl.Http4sDsl

object CookieHelpers:

  def withFlashCookie[F[_]: Async](
      request: Request[F]
  )(handler: Option[Map[String, String]] => F[Response[F]]): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    deserializeFlashCookie(request).flatMap {
      case Left(_) =>
        BadRequest("failed to deserialize cookie, this is very bad")
      case Right(flashData) =>
        handler(flashData)
    }
  }

  def deserializeFlashCookie[F[_]: Async](
      request: Request[F]
  ): F[Either[Throwable, Option[Map[String, String]]]] =
    extractCookie(request, "flash") match {
      case Some(cookieContent) =>
        FlashOps.deserialize(cookieContent).map(_.map(Some(_)))
      case None =>
        Async[F].pure(Right(None))
    }

  def extractCookie[F[_]](
      request: Request[F],
      cookieName: String
  ): Option[String] =
    request.headers
      .get[Cookie]
      .flatMap(_.values.find(_.name == cookieName))
      .map(_.content)
