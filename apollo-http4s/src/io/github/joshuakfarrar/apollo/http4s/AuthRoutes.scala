package io.github.joshuakfarrar.apollo.http4s

import cats.data.EitherT
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

object AuthRoutes:
  def routes[F[_]: Async: LoggerFactory, U: HasPassword: HasEmail, I, E](
      apollo: Apollo[F, U, I, E]
  )(implicit PW: Hashable[F, String]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val log = LoggerFactory[F].getLogger

    /** Business outcomes arrive as `AuthError`; anything raised in F is
      * infrastructure. Routes wrap themselves in `guarded` so users see a
      * friendly flash either way, with the underlying failure logged.
      */
    def guarded(fallback: => F[Response[F]])(
        action: F[Response[F]]
    ): F[Response[F]] =
      action.handleErrorWith { error =>
        log.error(error)("Unexpected failure in auth route") *> fallback
      }

    def renderLogin(
        csrfToken: String,
        focus: String,
        flash: Option[Flash] = None
    ) =
      Ok(apollo.templates.auth(csrfToken, focus, flash))

    def redirectWithFlashMessages(
        flashMessages: Map[String, String]
    )(location: Location): F[Response[F]] =
      FlashOps
        .serialize(flashMessages)
        .flatMap(
          _.fold(
            err =>
              BadRequest(
                "failed to serialize cookie, this is very bad"
              ),
            messages => {
              val cookie = ResponseCookie(
                name = "flash",
                content = messages,
                path = Some("/")
              )
              SeeOther(location).map(_.addCookie(cookie))
            }
          )
        )

    def redirectWithFlash(
        focus: String
    )(cssClass: String)(location: Location)(message: String) = {
      val flashMessages: Map[String, String] = Map(
        "message" -> message,
        "cssClass" -> cssClass,
        "location" -> focus
      )
      redirectWithFlashMessages(flashMessages)(location)
    }

    def resetRedirectWithError(message: String): F[Response[F]] = {
      val flashMessages: Map[String, String] = Map(
        "message" -> message,
        "cssClass" -> "alert-danger"
      )
      redirectWithFlashMessages(flashMessages)(Location(uri"/reset"))
    }

    def loginRedirectWithSuccess: String => F[Response[F]] =
      redirectWithFlash("login")("alert-success")(Location(uri"/login"))

    def loginRedirectWithError: String => F[Response[F]] =
      redirectWithFlash("login")("alert-danger")(
        Location(uri"/login")
      ) // lol, curry

    def registrationRedirectWithError: String => F[Response[F]] =
      redirectWithFlash("register")("alert-danger")(Location(uri"/login"))

    def resetCodeRedirectWithError(
        code: String
    )(message: String): F[Response[F]] = {
      val flashMessages: Map[String, String] = Map(
        "message" -> message,
        "cssClass" -> "alert-danger"
      )
      redirectWithFlashMessages(flashMessages)(
        Location(Uri.unsafeFromString(s"/reset/$code"))
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

    def confirmedSignIn(user: U): F[Response[F]] =
      apollo.services.confirmation match {
        case None => signIn(user)
        case Some(confirmation) =>
          confirmation.isConfirmed(user).flatMap {
            case true => signIn(user)
            case false =>
              loginRedirectWithError(
                "Please confirm your account before logging in"
              )
          }
      }

    def createUser(
        name: String,
        email: String,
        password: String,
        invite: Option[(InvitationService[F, U, I], String)]
    ) =
      apollo.services.user.createUser(name, email, password).value.flatMap {
        case Right(user) =>
          val proceed = apollo.services.confirmation match {
            case Some(confirmation) => createConfirmation(confirmation, user)
            case None               => signIn(user)
          }
          invite match {
            case None => proceed
            case Some((invitations, code)) =>
              invitations.consumeInvitation(code).attempt.flatMap {
                case Left(error) =>
                  log.warn(error)("Failed to consume invitation") *>
                    proceed.map(_.removeCookie("invite"))
                case Right(_) =>
                  proceed.map(_.removeCookie("invite"))
              }
          }
        case Left(AuthError.EmailTaken) =>
          registrationRedirectWithError(
            "User with that e-mail already exists"
          )
        case Left(_) =>
          registrationRedirectWithError(
            "Could not create your account. Please try again"
          )
      }

    def createConfirmation(
        confirmation: ConfirmationService[F, U, I],
        user: U
    ) = {
      val confirmationResult = for {
        code <- confirmation.createConfirmation(user)
        _ <- apollo.services.mail.send(
          apollo.services.mail.confirmationEmail(
            implicitly[HasEmail[U]].email(user),
            code
          )
        )
      } yield ()

      confirmationResult.attempt.flatMap {
        case Left(error) =>
          log.warn(error)(
            "Failed to create confirmation or send confirmation e-mail"
          ) *> registrationRedirectWithError("Failed to create new user")
        case Right(_) =>
          loginRedirectWithSuccess(
            "Check your e-mail to confirm your account before logging in"
          )
      }
    }

    case class RegistrationForm(
        name: String,
        email: String,
        password: String,
        confirmPassword: String
    )

    HttpRoutes.of[F]:
      case request @ GET -> Root / "login" =>
        request.attributes.lookup(apollo.config.csrfTokenKey) match {
          case Some(token) =>
            withFlashCookie(request) { cookie =>
              val location = cookie.flatMap(_.get("location"))
              val focus = location match {
                case Some("register") => "register"
                case _                => "login"
              }
              val cssClass = cookie.flatMap(_.get("cssClass"))
              val flash = for {
                message <- cookie.flatMap(_.get("message"))
              } yield Flash(
                cssClass = cssClass.getOrElse("alert-danger"),
                message = message
              )
              renderLogin(token, focus, flash)
            }
          case None => Forbidden()
        }
      case request @ POST -> Root / "register" =>
        def validateRegistrationForm(form: RegistrationForm): Option[String] =
          if (form.name.isEmpty)
            Some("Name field cannot be empty")
          else if (form.email.isEmpty)
            Some("Email field cannot be empty")
          else if (form.password.isEmpty)
            Some("Password cannot be empty")
          else if (!form.password.equals(form.confirmPassword))
            Some("Password and confirmation did not match")
          else
            None

        def parseFormData(form: UrlForm): Option[RegistrationForm] =
          for {
            name <- form.getFirst("name")
            email <- form.getFirst("email")
            password <- form.getFirst("password")
            confirmPassword <- form.getFirst("confirmPassword")
          } yield RegistrationForm(name, email, password, confirmPassword)

        guarded(
          registrationRedirectWithError(
            "Could not create your account. Please try again"
          )
        ) {
          request
            .as[UrlForm]
            .flatMap { form =>
              parseFormData(form) match {
                case Some(parsed) =>
                  validateRegistrationForm(parsed) match {
                    case Some(error) => registrationRedirectWithError(error)
                    case None =>
                      apollo.services.invitation match {
                        case None =>
                          createUser(
                            parsed.name,
                            parsed.email,
                            parsed.password,
                            None
                          )
                        case Some(invitations) =>
                          request.cookies
                            .find(_.name == "invite")
                            .map(_.content) match {
                            case None =>
                              registrationRedirectWithError(
                                "Registration is by invitation only"
                              )
                            case Some(code) =>
                              invitations.getInvitation(code).value.flatMap {
                                case Left(_) =>
                                  registrationRedirectWithError(
                                    "Invalid or expired invitation"
                                  )
                                case Right(_) =>
                                  createUser(
                                    parsed.name,
                                    parsed.email,
                                    parsed.password,
                                    Some((invitations, code))
                                  )
                              }
                          }
                      }
                  }
                case None =>
                  registrationRedirectWithError(
                    "Missing name, email, password, or password confirmation"
                  )
              }
            }
        }
      case request @ POST -> Root / "login" =>

        def extractCredentials(form: UrlForm): Option[(String, String)] =
          for {
            email <- form.getFirst("email")
            password <- form.getFirst("password")
          } yield (email, password)

        def authenticateUser(
            email: String,
            password: String
        ): EitherT[F, AuthError, (U, Boolean)] =
          for {
            user <- apollo.services.user.fetchUser(email)
            passwordsMatch <- EitherT.liftF(
              PW.verify(password, implicitly[HasPassword[U]].password(user))
            )
          } yield (user, passwordsMatch)

        guarded(
          loginRedirectWithError("Could not sign you in. Please try again")
        ) {
          request
            .as[UrlForm]
            .flatMap { form =>
              extractCredentials(form) match {
                case Some((email, password)) =>
                  authenticateUser(email, password).value.flatMap {
                    case Right((user, authenticated)) =>
                      if (authenticated) confirmedSignIn(user)
                      else
                        loginRedirectWithError(
                          "Could not find user with that email or password"
                        )
                    case Left(_) =>
                      loginRedirectWithError(
                        "Could not find user with that email or password"
                      )
                  }
                case None =>
                  loginRedirectWithError("Missing email or password")
              }
            }
        }
      case GET -> Root / "confirm" / code =>
        apollo.services.confirmation match {
          case None => NotFound()
          case Some(confirmation) =>
            guarded(BadRequest("Unable to confirm your account")) {
              confirmation.confirmByCode(code).value.flatMap {
                case Left(_) => BadRequest("Unable to confirm your account")
                case Right(_) =>
                  loginRedirectWithSuccess(
                    "Account confirmed! You can now log in"
                  )
              }
            }
        }
      case request @ GET -> Root / "reset" =>
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
              Ok(apollo.templates.forgotPassword(token, flash))
            }
          case None => Forbidden()
        }
      case request @ POST -> Root / "reset" =>
        request.as[UrlForm].flatMap { form =>
          form.getFirst("email") match {
            case Some(email) =>
              val result = for {
                user <- apollo.services.user.fetchUser(email)
                code <- EitherT.liftF[F, AuthError, String](
                  apollo.services.reset.createReset(user)
                )
                _ <- EitherT.liftF[F, AuthError, Unit](
                  apollo.services.mail.send(
                    apollo.services.mail
                      .resetEmail(implicitly[HasEmail[U]].email(user), code)
                  )
                )
              } yield ()

              // Always the same response, whatever happened: reset requests
              // must not reveal whether an account exists.
              result.value.attempt.flatMap { outcome =>
                val logFailure = outcome match {
                  case Left(error) =>
                    log.warn(error)("Failed to create or send password reset")
                  case Right(_) => Async[F].unit
                }
                logFailure *> resetRedirectWithError(
                  "A password reset e-mail has been sent to the provided user's email address, if they exist"
                )
              }
            case None =>
              resetRedirectWithError("Invalid e-mail address provided")
          }
        }
      case request @ GET -> Root / "reset" / code =>
        request.attributes.lookup(apollo.config.csrfTokenKey) match {
          case Some(token) =>
            guarded(resetRedirectWithError("Invalid or expired reset code")) {
              apollo.services.reset
                .getReset(code)
                .foldF(
                  _ => resetRedirectWithError("Invalid or expired reset code"),
                  _ =>
                    withFlashCookie(request) { cookie =>
                      val error = cookie.flatMap(_.get("message"))
                      Ok(apollo.templates.resetPassword(token, error))
                    }
                )
            }
          case None => Forbidden()
        }
      case request @ POST -> Root / "reset" / code =>

        def extractNewPassword(form: UrlForm): Option[(String, String)] =
          for {
            password <- form.getFirst("password")
            confirmPassword <- form.getFirst("confirmPassword")
          } yield (password, confirmPassword)

        def validatePasswords(
            password: String,
            confirmPassword: String
        ): Option[String] =
          if (password.isEmpty)
            Some("Password cannot be empty")
          else if (!password.equals(confirmPassword))
            Some("Password and confirmation did not match")
          else if (password.length < 8) // or whatever your password policy is
            Some("Password must be at least 8 characters")
          else
            None

        def processPasswordReset(
            resetToken: Reset[I],
            newPassword: String
        ): F[Response[F]] =
          apollo.services.user.updatePassword(resetToken.userId, newPassword) *>
            apollo.services.reset.invalidateReset(code).attempt.flatMap {
              case Left(error) =>
                // The password DID change; a stale reset row must not fail it
                log.warn(error)("Failed to invalidate used reset code") *>
                  loginRedirectWithSuccess(
                    "Password successfully reset. Please log in with your new password"
                  )
              case Right(_) =>
                loginRedirectWithSuccess(
                  "Password successfully reset. Please log in with your new password"
                )
            }

        guarded(
          resetCodeRedirectWithError(code)(
            "Failed to update password. Please try again"
          )
        ) {
          apollo.services.reset.getReset(code).value.flatMap {
            case Left(_) =>
              resetCodeRedirectWithError(code)("Invalid or expired reset code")
            case Right(reset) =>
              request.as[UrlForm].flatMap { form =>
                extractNewPassword(form) match {
                  case Some((password, confirmPassword)) =>
                    validatePasswords(password, confirmPassword) match {
                      case Some(validationError) =>
                        resetCodeRedirectWithError(code)(validationError)
                      case None =>
                        processPasswordReset(reset, password)
                    }
                  case None =>
                    resetCodeRedirectWithError(code)(
                      "Missing password or password confirmation"
                    )
                }
              }
          }
        }
      case request @ POST -> Root / "logout" =>
        request.cookies.find(_.name == "session_token") match {
          case Some(cookie) =>
            apollo.services.session
              .deleteSession(cookie.content)
              .attempt
              .flatMap { outcome =>
                val logFailure = outcome match {
                  case Left(error) => log.warn(error)("Failed to delete session")
                  case Right(_)    => Async[F].unit
                }
                logFailure *> SeeOther(Location(uri"/")).map(
                  _.removeCookie("session_token")
                )
              }
          case None =>
            SeeOther(Location(uri"/"))
        }
