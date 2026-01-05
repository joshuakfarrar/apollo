package me.joshuakfarrar.apollo.auth

import cats.effect.Sync
import cats.implicits.*
import org.http4s.headers.Location
import org.http4s.implicits.uri
import org.http4s.{Request, Response, Status, Uri}

object SessionAuth {

  def authenticated[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I]
  )(onSuccess: U => F[Response[F]]): F[Response[F]] =
    request.cookies.find(_.name == "session_token") match {
      case None =>
        Sync[F].pure(Response[F](Status.Unauthorized))
      case Some(cookie) =>
        userService.findBySessionToken(cookie.content).value.flatMap {
          case Right(user) => onSuccess(user)
          case Left(_)     => Sync[F].pure(Response[F](Status.Unauthorized))
        }
    }

  def optionalUser[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I]
  )(onResult: Option[U] => F[Response[F]]): F[Response[F]] =
    request.cookies.find(_.name == "session_token") match {
      case None =>
        onResult(None)
      case Some(cookie) =>
        userService.findBySessionToken(cookie.content).value.flatMap {
          case Right(user) => onResult(Some(user))
          case Left(_)     => onResult(None)
        }
    }

  def authenticatedOrRedirect[F[_]: Sync, U, I](
      request: Request[F],
      userService: UserService[F, U, I],
      loginUri: Uri = uri"/"
  )(onSuccess: U => F[Response[F]]): F[Response[F]] = {
    request.cookies.find(_.name == "session_token") match {
      case None =>
        Sync[F].pure(
          Response[F](Status.SeeOther).withHeaders(Location(loginUri))
        )
      case Some(cookie) =>
        userService.findBySessionToken(cookie.content).value.flatMap {
          case Right(user) => onSuccess(user)
          case Left(_) =>
            Sync[F].pure(
              Response[F](Status.SeeOther).withHeaders(Location(loginUri))
            )
        }
    }
  }
}
