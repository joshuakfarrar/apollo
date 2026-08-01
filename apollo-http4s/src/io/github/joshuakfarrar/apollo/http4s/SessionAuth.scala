package io.github.joshuakfarrar.apollo.http4s

import cats.effect.Sync
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.UserService
import org.http4s.headers.Location
import org.http4s.implicits.uri
import org.http4s.{Request, Response, Status, Uri}

object SessionAuth {

  /** Any failure to resolve the session — a missing/expired session (the
    * typed `SessionNotFound`) or an infrastructure error — is treated as
    * "not signed in". An auth check must fail closed.
    */
  private def resolveUser[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I]
  ): F[Option[U]] =
    request.cookies.find(_.name == "session_token") match {
      case None => Sync[F].pure(None)
      case Some(cookie) =>
        userService
          .findBySessionToken(cookie.content)
          .value
          .attempt
          .map(_.toOption.flatMap(_.toOption))
    }

  def authenticated[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I]
  )(onSuccess: U => F[Response[F]]): F[Response[F]] =
    resolveUser(request, userService).flatMap {
      case Some(user) => onSuccess(user)
      case None       => Sync[F].pure(Response[F](Status.Unauthorized))
    }

  def optionalUser[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I]
  )(onResult: Option[U] => F[Response[F]]): F[Response[F]] =
    resolveUser(request, userService).flatMap(onResult)

  def authenticatedOrRedirect[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I],
      loginUri: Uri = uri"/"
  )(onSuccess: U => F[Response[F]]): F[Response[F]] =
    resolveUser(request, userService).flatMap {
      case Some(user) => onSuccess(user)
      case None =>
        Sync[F].pure(
          Response[F](Status.SeeOther).withHeaders(Location(loginUri))
        )
    }
}
