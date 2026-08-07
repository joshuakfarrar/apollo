package io.github.joshuakfarrar.apollo.http4s

import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.*
import io.github.joshuakfarrar.apollo.http4s.CookieHelpers.withFlashCookie
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.implicits.uri
import org.http4s.twirl.*
import org.typelevel.log4cats.LoggerFactory
import play.twirl.api.Html

/** PROTOTYPE: opt-in "sign in with atproto" routes. Mount alongside
  * [[AuthRoutes]]:
  *
  * {{{
  * AtprotoRoutes.routes(apollo, authenticator, identities) <+> ...
  * }}}
  *
  *   - `GET /atproto/login` renders a handle + app-password form
  *   - `POST /atproto/login` verifies the account against the PDS, then
  *     signs in the linked local user — provisioning and linking one on
  *     first sign-in
  */
object AtprotoRoutes:
  def routes[F[_]: Async: LoggerFactory, U, I, E](
      apollo: Apollo[F, U, I, E],
      authenticator: AtprotoAuthenticator[F],
      identities: AtprotoIdentityService[F, U, I],
      template: (String, Option[Flash]) => Html = (csrfToken, flash) =>
        html.atprotoLoginForm(csrfToken, flash.map(f => (f.cssClass, f.message)))
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val log = LoggerFactory[F].getLogger

    def errorRedirect(message: String): F[Response[F]] =
      FlashOps
        .serialize(Map("message" -> message, "cssClass" -> "alert-danger"))
        .flatMap {
          case Left(_) => SeeOther(Location(uri"/atproto/login"))
          case Right(content) =>
            SeeOther(Location(uri"/atproto/login")).map(
              _.addCookie(ResponseCookie("flash", content, path = Some("/")))
            )
        }

    def signIn(user: U): F[Response[F]] =
      apollo.services.session.createSession(user).flatMap { token =>
        SeeOther(Location(uri"/")).map(
          _.addCookie(
            ResponseCookie(
              name = "session_token",
              content = token,
              path = Some("/"),
              httpOnly = true,
              secure = true,
              sameSite = Some(SameSite.Strict)
            )
          )
        )
      }

    def signInLinkedUser(account: AtprotoAccount): F[Response[F]] =
      identities.findByDid(account.did).value.flatMap {
        case Right(user) => signIn(user)
        case Left(_) =>
          identities.createFromAtproto(account).value.flatMap {
            case Right(user) => signIn(user)
            case Left(_) =>
              errorRedirect("Could not create your account. Please try again")
          }
      }

    HttpRoutes.of[F]:
      case request @ GET -> Root / "atproto" / "login" =>
        request.attributes.lookup(apollo.config.csrfTokenKey) match {
          case Some(token) =>
            withFlashCookie(request) { cookie =>
              val flash = cookie
                .flatMap(_.get("message"))
                .map(msg =>
                  Flash(
                    cookie.flatMap(_.get("cssClass")).getOrElse("alert-danger"),
                    msg
                  )
                )
              Ok(template(token, flash))
            }
          case None => Forbidden()
        }
      case request @ POST -> Root / "atproto" / "login" =>
        request
          .as[UrlForm]
          .flatMap { form =>
            (form.getFirst("identifier"), form.getFirst("password")) match {
              case (Some(identifier), Some(appPassword))
                  if identifier.nonEmpty && appPassword.nonEmpty =>
                authenticator.verify(identifier.trim, appPassword).value.flatMap {
                  case Right(account) => signInLinkedUser(account)
                  case Left(AuthError.AtprotoAuthFailed) =>
                    errorRedirect(
                      "Could not verify that handle and app password"
                    )
                  case Left(_) =>
                    errorRedirect("Could not sign you in. Please try again")
                }
              case _ =>
                errorRedirect("Missing handle or app password")
            }
          }
          .handleErrorWith { error =>
            log.error(error)("Unexpected failure in atproto login") *>
              errorRedirect("Could not sign you in. Please try again")
          }
