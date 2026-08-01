package io.github.joshuakfarrar.apollo.http4s

import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.InvitationService
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.implicits.uri
import org.typelevel.log4cats.LoggerFactory

/** Opt-in routes for invitation-gated registration. Mount alongside
  * [[AuthRoutes]] when `ApolloServices.invitation` is configured:
  *
  * {{{
  * InvitationRoutes.routes(apollo) <+> AuthRoutes.routes(apollo)
  * }}}
  *
  *   - `POST /invitations` (form field `email`) creates an invitation and
  *     mails a link to `/invite/:code`. With `requireAuth = true` (the
  *     default) only signed-in users may create invitations; with `false`
  *     anyone may — rate-limit that upstream, it sends e-mail on request.
  *   - `GET /invite/:code` validates the code, stores it in a short-lived
  *     cookie, and redirects to the registration form. Registration then
  *     consumes the invitation. No template changes are required.
  *
  * Both routes return 404 when no invitation service is configured.
  */
object InvitationRoutes:
  def routes[F[_]: Async: LoggerFactory, U, I, E](
      apollo: Apollo[F, U, I, E],
      requireAuth: Boolean = true
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val log = LoggerFactory[F].getLogger

    def flashRedirect(
        location: Location,
        cssClass: String,
        message: String,
        focus: Option[String] = None
    ): F[Response[F]] =
      FlashOps
        .serialize(
          Map("message" -> message, "cssClass" -> cssClass) ++
            focus.map("location" -> _)
        )
        .flatMap {
          case Left(_) => SeeOther(location)
          case Right(content) =>
            SeeOther(location).map(
              _.addCookie(
                ResponseCookie("flash", content, path = Some("/"))
              )
            )
        }

    def createAndSend(
        invitations: InvitationService[F, U, I],
        email: String,
        inviter: Option[U]
    ): F[Response[F]] = {
      val result = for {
        code <- invitations.createInvitation(email, inviter)
        _ <- apollo.services.mail.send(
          apollo.services.mail.invitationEmail(email, code)
        )
      } yield ()

      result.value.flatMap {
        case Right(_) =>
          flashRedirect(Location(uri"/"), "alert-success", "Invitation sent")
        case Left(error) =>
          log.warn(error)("Failed to create or send invitation") *>
            flashRedirect(
              Location(uri"/"),
              "alert-danger",
              "Could not send invitation. Please try again"
            )
      }
    }

    HttpRoutes.of[F]:
      case request @ POST -> Root / "invitations" =>
        apollo.services.invitation match {
          case None => NotFound()
          case Some(invitations) =>
            request.as[UrlForm].flatMap { form =>
              form.getFirst("email").map(_.trim).filter(_.nonEmpty) match {
                case None =>
                  flashRedirect(
                    Location(uri"/"),
                    "alert-danger",
                    "An e-mail address is required"
                  )
                case Some(email) =>
                  if (requireAuth)
                    SessionAuth.authenticated(request, apollo.services.user) {
                      user => createAndSend(invitations, email, Some(user))
                    }
                  else
                    SessionAuth.optionalUser(request, apollo.services.user) {
                      maybeUser => createAndSend(invitations, email, maybeUser)
                    }
              }
            }
        }
      case GET -> Root / "invite" / code =>
        apollo.services.invitation match {
          case None => NotFound()
          case Some(invitations) =>
            invitations.getInvitation(code).value.flatMap {
              case Left(_) =>
                flashRedirect(
                  Location(uri"/login"),
                  "alert-danger",
                  "Invalid or expired invitation"
                )
              case Right(_) =>
                flashRedirect(
                  Location(uri"/login"),
                  "alert-success",
                  "Invitation accepted! Create your account",
                  focus = Some("register")
                ).map(
                  _.addCookie(
                    ResponseCookie(
                      name = "invite",
                      content = code,
                      path = Some("/"),
                      httpOnly = true,
                      maxAge = Some(3600)
                    )
                  )
                )
            }
        }
