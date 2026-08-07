package io.github.joshuakfarrar.apollo.http4s

import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.*
import io.github.joshuakfarrar.apollo.http4s.CookieHelpers.withFlashCookie
import io.github.joshuakfarrar.apollo.http4s.atproto.AtprotoOAuthClient
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Location, `Content-Type`}
import org.http4s.implicits.uri
import org.http4s.twirl.*
import org.typelevel.log4cats.LoggerFactory
import play.twirl.api.Html

/** Opt-in "sign in with atproto" routes, built on atproto OAuth
  * (PAR + DPoP + PKCE). Mount alongside [[AuthRoutes]]:
  *
  * {{{
  * AtprotoRoutes.routes(apollo, oauth, identities) <+> AuthRoutes.routes(apollo)
  * }}}
  *
  *   - `GET /oauth/client-metadata.json` serves the client metadata
  *     document; `AtprotoOAuthConfig.clientId` must be this route's
  *     public URL
  *   - `GET /atproto/login` renders a handle form
  *   - `POST /atproto/login` resolves the identity, pushes the
  *     authorization request, and redirects to the account's
  *     authorization server
  *   - `GET /atproto/callback` finishes the flow and signs in the linked
  *     local user, provisioning one on first sign-in
  */
object AtprotoRoutes:
  def routes[F[_]: Async: LoggerFactory, U, I, E](
      apollo: Apollo[F, U, I, E],
      oauth: AtprotoOAuthClient[F],
      identities: AtprotoIdentityService[F, U, I],
      template: (String, Option[Flash]) => Html = (csrfToken, flash) =>
        html.atprotoLoginForm(csrfToken, flash.map(f => (f.cssClass, f.message)))
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val log = LoggerFactory[F].getLogger

    object StateParam extends QueryParamDecoderMatcher[String]("state")
    object IssParam extends QueryParamDecoderMatcher[String]("iss")
    object CodeParam extends QueryParamDecoderMatcher[String]("code")
    object ErrorParam extends OptionalQueryParamDecoderMatcher[String]("error")

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

    def guarded(action: F[Response[F]]): F[Response[F]] =
      action.handleErrorWith { error =>
        log.error(error)("Unexpected failure in atproto route") *>
          errorRedirect("Could not sign you in. Please try again")
      }

    HttpRoutes.of[F]:
      case GET -> Root / "oauth" / "client-metadata.json" =>
        Ok(oauth.clientMetadata)
          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
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
        guarded {
          request.as[UrlForm].flatMap { form =>
            form.getFirst("identifier").map(_.trim).filter(_.nonEmpty) match {
              case None => errorRedirect("Enter your handle to sign in")
              case Some(identifier) =>
                oauth.start(identifier).value.flatMap {
                  case Right(authorizeUrl) =>
                    SeeOther(Location(authorizeUrl))
                  case Left(_) =>
                    errorRedirect(
                      "Could not verify that handle. Check it and try again"
                    )
                }
            }
          }
        }
      case GET -> Root / "atproto" / "callback" :?
          StateParam(state) +& IssParam(iss) +& CodeParam(code) =>
        guarded {
          oauth.callback(state, iss, code).value.flatMap {
            case Right(account) => signInLinkedUser(account)
            case Left(AuthError.OAuthRequestNotFound) =>
              errorRedirect("This sign-in link expired. Please try again")
            case Left(_) =>
              errorRedirect("Could not sign you in. Please try again")
          }
        }
      case GET -> Root / "atproto" / "callback" :? ErrorParam(error) =>
        log.debug(
          s"atproto authorization was not granted: ${error.getOrElse("(no error param)")}"
        ) *> errorRedirect("Sign-in was cancelled")
