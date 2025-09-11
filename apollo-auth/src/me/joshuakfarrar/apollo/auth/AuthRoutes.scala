package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import com.microsoft.sqlserver.jdbc.SQLServerException
import me.joshuakfarrar.apollo.auth.CookieHelpers.withFlashCookie
import me.joshuakfarrar.apollo.auth.DefaultAuthForm.Flash
import me.joshuakfarrar.apollo.auth.ScalatagsInstances.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.implicits.uri
import org.http4s.server.middleware.CSRF
import org.http4s.{EntityEncoder, HttpRoutes, Response, ResponseCookie, SameSite, UrlForm}
import org.typelevel.vault.{Key, LookupKey}

object AuthRoutes:
  def routes[F[_]: Async, U: HasPassword: HasEmail, E, I](
      csrfTokenKey: Key[String],
      Csrf: CSRF[F, F],
      UserService: UserService[F, U, I],
      ConfirmationService: ConfirmationService[F, U, I],
      MailService: MailService[F, E, Unit],
      SessionService: SessionService[F, U, I],
      ResetService: ResetService[F, U, I]
  )(implicit PW: Hashable[F, String]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    def renderLogin(
                     csrfToken: String,
                     focus: DefaultAuthForm.Focus,
                     flash: Option[Flash] = None
    ) =
      DefaultLayout.render(DefaultAuthForm.page(csrfToken, focus, flash))

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

    def redirectWithFlash(focus: String)(cssClass: String)(location: Location)(message: String) = {
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
        "cssClass" -> "alert-danger",

      )
      redirectWithFlashMessages(flashMessages)(Location(uri"/reset"))
    }

    def loginRedirectWithSuccess: String => F[Response[F]] =
      redirectWithFlash("login")("alert-success")(Location(uri"/"))

    def loginRedirectWithError: String => F[Response[F]] =
      redirectWithFlash("login")("alert-danger")(Location(uri"/")) // lol, curry

    def registrationRedirectWithError: String => F[Response[F]] =
      redirectWithFlash("register")("alert-danger")(Location(uri"/"))

    def createUser(name: String, email: String, password: String) =
      UserService.createUser(name, email, password).value.flatMap {
        case Right(user) => createConfirmation(user) //
        case Left(error) =>
          registrationRedirectWithError {
            error match {
              case sqlEx: SQLServerException =>
                sqlEx.getErrorCode match {
                  case 0 =>
                    "Database appears offline or is otherwise inaccessible"
                  case 515 =>
                    "Somehow a field that should not have been empty was empty, please try again"
                  case 2601 | 2627 => "User with that e-mail already exists"
                  case code        => s"Database problem: ($code)"
                }
              case _ => s"Unknown error: ${error.getClass.getName}"
            }
          }
      }

    def createConfirmation(user: U) = {
      val confirmationResult = for {
        code <- ConfirmationService.createConfirmation(user)
        _ <- MailService.send(
          MailService.confirmationEmail(
            implicitly[HasEmail[U]].email(user),
            code
          )
        )
      } yield user

      confirmationResult.value.flatMap(
        _.fold(
          _ => registrationRedirectWithError("Failed to create new user"),
          _ => loginRedirectWithSuccess("Check your e-mail to confirm your account before logging in")
        )
      )
    }

    HttpRoutes.of[F]:
      case request @ GET -> Root => {
        request.attributes.lookup(csrfTokenKey) match {
          case Some(token) => withFlashCookie(request) { cookie =>
            val location = cookie.flatMap(_.get("location"))
            val focus = location match {
              case Some("register") => DefaultAuthForm.Focus.Register
              case _ => DefaultAuthForm.Focus.Login
            }
            val cssClass = cookie.flatMap(_.get("cssClass"))
            val flash = for {
              message <- cookie.flatMap(_.get("message"))
            } yield Flash(cssClass = cssClass.getOrElse("alert-danger"), message = message)
            Ok(renderLogin(token, focus, flash))
          }
          case None => Forbidden()
        }
      }
      case request @ POST -> Root / "register" =>
        case class RegistrationForm(name: String, email: String, password: String, confirmPassword: String)

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

        request
          .as[UrlForm]
          .flatMap { form =>
            parseFormData(form) match {
              case Some(parsed) =>
                validateRegistrationForm(parsed) match {
                  case Some(error) => registrationRedirectWithError(error)
                  case None => createUser(parsed.name, parsed.email, parsed.password)
                }
              case None => registrationRedirectWithError(
                "Missing name, email, password, or password confirmation"
              )
            }
          }
      case request @ POST -> Root / "login" =>

        def createSessionResponse(user: U): F[Response[F]] =
          SessionService.createSession(user).value.flatMap {
            case Right(token) =>
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
            case Left(error) =>
              loginRedirectWithError(
                s"Could not create session, you are not logged in: ${error.getMessage}"
              )
          }

        def extractCredentials(form: UrlForm): Option[(String, String)] =
          for {
            email <- form.getFirst("email")
            password <- form.getFirst("password")
          } yield (email, password)

        def authenticateUser(email: String, password: String): EitherT[F, Throwable, (U, Boolean)] =
          for {
            user <- UserService.fetchUser(email)
            passwordsMatch <- EitherT(PW.verify(password, implicitly[HasPassword[U]].password(user)).map(Right(_)))
          } yield (
            user,
            passwordsMatch
          )

        request
          .as[UrlForm]
          .flatMap { form =>
            extractCredentials(form) match {
              case Some((email, password)) =>
                authenticateUser(email, password).value.flatMap {
                  case Right((user, authenticated)) =>
                    if (authenticated) createSessionResponse(user)
                    else loginRedirectWithError("Could not find user with that email or password")
                  case Left(_) =>
                    loginRedirectWithError("Could not find user with that email or password")
                }
              case None => loginRedirectWithError("Missing email or password")
            }
          }
      case GET -> Root / "confirm" / code =>
        ConfirmationService.confirmByCode(code).value.flatMap {
          case Some(_) => BadRequest("Unable to confirm your account")
          case _           => loginRedirectWithSuccess("Account confirmed! You can now log in")
        }
      case request @ GET -> Root / "reset" =>
        request.attributes.lookup(csrfTokenKey) match {
          case Some(token) => withFlashCookie(request) {
            case None => Ok(DefaultLayout.render(DefaultResetRequestForm.page(token, None)))
            case Some(cookie) => Ok(DefaultLayout.render(DefaultResetRequestForm.page(token, Some(DefaultResetRequestForm.Flash(cssClass = "alert-danger", message = cookie.getOrElse("message", ""))))))
          }
          case None => Forbidden()
        }
      case request @ POST -> Root / "reset" =>
        request.as[UrlForm].flatMap { form =>
          form.getFirst("email") match {
            case Some(email) => {
              val result = for {
                user <- UserService.fetchUser(email)
                code <- ResetService.createReset(user)
                _ <- MailService.send(
                  MailService
                    .resetEmail(implicitly[HasEmail[U]].email(user), code)
                )
              } yield code

              result.value.flatMap { _ =>
                // todo: replace with alert-info
                resetRedirectWithError("A password reset e-mail has been sent to the provided user's email address, if they exist")
              }
            }
            case None => resetRedirectWithError("Invalid e-mail address provided")
          }
        }
      case request @ GET -> Root / "reset" / code =>
        request.attributes.lookup(csrfTokenKey) match {
          case Some(token) =>
            ResetService
              .getReset(code)
              .foldF(error => resetRedirectWithError(s"Invalid or expired reset code ${error.getMessage}"),
                reset =>
                  Ok(
                    DefaultLayout.render(
                      DefaultChangePasswordForm
                        .page(token.toString, None)
                    )
                  )
              )
          case None => Forbidden()
        }
      case request @ POST -> Root / "reset" / code => {

        def extractNewPassword(form: UrlForm): Option[(String, String)] =
          for {
            password <- form.getFirst("password")
            confirmPassword <- form.getFirst("confirmPassword")
          } yield (password, confirmPassword)

        def validatePasswords(password: String, confirmPassword: String): Option[String] =
          if (password.isEmpty)
            Some("Password cannot be empty")
          else if (!password.equals(confirmPassword))
            Some("Password and confirmation did not match")
          else if (password.length < 8) // or whatever your password policy is
            Some("Password must be at least 8 characters")
          else
            None

        def processPasswordReset(resetToken: Reset[I], newPassword: String): F[Response[F]] =
          UserService.updatePassword(resetToken.userId, newPassword).value.flatMap {
            case Right(_) =>
              // Invalidate the reset token after successful use
              ResetService.invalidateReset(code).value.flatMap { _ =>
                loginRedirectWithSuccess("Password successfully reset. Please log in with your new password")
              }
            case Left(error) =>
              Ok(
                DefaultLayout.render(
                  DefaultChangePasswordForm.page(
                    "test",
                    Some(s"Failed to update password: ${error.getMessage}")
                  )
                )
              )
          }

        def renderPasswordForm(error: Option[String] = None): F[Response[F]] =
          Ok(
            DefaultLayout.render(
              DefaultChangePasswordForm.page("test", error)
            )
          )

        ResetService.getReset(code).value.flatMap {
          case Left(_) =>
            renderPasswordForm(Some("Invalid or expired reset code"))
          case Right(reset) =>
            request.as[UrlForm].flatMap { form =>
              extractNewPassword(form) match {
                case Some((password, confirmPassword)) =>
                  validatePasswords(password, confirmPassword) match {
                    case Some(validationError) =>
                      renderPasswordForm(Some(validationError))
                    case None =>
                      processPasswordReset(reset, password)
                  }
                case None => renderPasswordForm(Some("Missing password or password confirmation"))
              }
            }
          }
      }