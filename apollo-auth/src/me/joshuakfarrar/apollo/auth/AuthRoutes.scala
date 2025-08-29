package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import com.microsoft.sqlserver.jdbc.SQLServerException
import com.password4j.Password
import me.joshuakfarrar.apollo.auth.CookieHelpers.withFlashCookie
import me.joshuakfarrar.apollo.auth.DefaultAuthForm.Flash
import me.joshuakfarrar.apollo.auth.ScalatagsInstances.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Cookie, Location}
import org.http4s.implicits.uri
import org.http4s.multipart.{Multipart, Part}
import org.http4s.{EntityDecoder, HttpRoutes, Response, ResponseCookie, SameSite}
import org.http4s.EntityEncoder

object AuthRoutes:
  def routes[F[_]: Async, U: HasPassword: HasEmail, E, I](
      UserService: UserService[F, U, I],
      ConfirmationService: ConfirmationService[F, U, I],
      MailService: MailService[F, E, Unit],
      SessionService: SessionService[F, U, I],
      ResetService: ResetService[F, U, I]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    given EntityEncoder[F, Reset[I]] =
      EntityEncoder.stringEncoder[F].contramap(_.toString)

    def renderLogin(
        focus: DefaultAuthForm.Focus,
        flash: Option[Flash] = None
    ) =
      DefaultLayout.render(DefaultAuthForm.page(focus, flash))

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
      case request @ GET -> Root =>
        withFlashCookie(request) { cookie =>
          val location = cookie.flatMap(_.get("location"))
          val focus = location match {
            case Some("register") => DefaultAuthForm.Focus.Register
            case _ => DefaultAuthForm.Focus.Login
          }
          val cssClass = cookie.flatMap(_.get("cssClass"))
          val flash = for {
            message <- cookie.flatMap(_.get("message"))
          } yield Flash(cssClass = cssClass.getOrElse("alert-danger"), message = message)
          Ok(renderLogin(focus, flash))
        }
      case request @ POST -> Root / "register" =>
        case class RegistrationForm(name: String, email: String, password: String, confirmPassword: String)

        def extractRegistrationForm(multipart: Multipart[F]): Option[(Part[F], Part[F], Part[F], Part[F])] =
          for {
            namePart <- multipart.parts.find(_.name.contains("name"))
            emailPart <- multipart.parts.find(_.name.contains("email"))
            passwordPart <- multipart.parts.find(_.name.contains("password"))
            confirmPasswordPart <- multipart.parts.find(_.name.contains("confirmPassword"))
          } yield (namePart, emailPart, passwordPart, confirmPasswordPart)

        def parseFormData(namePart: Part[F], emailPart: Part[F], passwordPart: Part[F], confirmPasswordPart: Part[F]): F[RegistrationForm] =
          for {
            name <- namePart.bodyText.compile.string
            email <- emailPart.bodyText.compile.string
            password <- passwordPart.bodyText.compile.string
            confirmPassword <- confirmPasswordPart.bodyText.compile.string
          } yield RegistrationForm(name, email, password, confirmPassword)

        def validateRegistrationForm(form: RegistrationForm): Option[String] =
          if (form.name.isEmpty)
            Some("Name field cannot be empty")
          else if (!form.password.equals(form.confirmPassword))
            Some("Password and confirmation did not match")
          else
            None

        request
          .as[Multipart[F]]
          .flatMap { multipart =>
            extractRegistrationForm(multipart) match {
              case Some((namePart, emailPart, passwordPart, confirmPasswordPart)) =>
                parseFormData(namePart, emailPart, passwordPart, confirmPasswordPart).flatMap { form =>
                  validateRegistrationForm(form) match {
                    case Some(error) => registrationRedirectWithError(error)
                    case None => createUser(form.name, form.email, form.password)
                  }
                }
              case None =>
                registrationRedirectWithError(
                  "Missing name, email, password, or password confirmation"
                )
            }
          }
      case request @ POST -> Root / "login" =>
        def extractCredentials(multipart: Multipart[F]): Option[(Part[F], Part[F])] =
          for {
            emailPart <- multipart.parts.find(_.name.contains("email"))
            passwordPart <- multipart.parts.find(_.name.contains("password"))
          } yield (emailPart, passwordPart)

        def authenticateUser(emailPart: Part[F], passwordPart: Part[F]): EitherT[F, Throwable, (U, Boolean)] =
          for {
            email <- EitherT.liftF(emailPart.bodyText.compile.string)
            password <- EitherT.liftF(passwordPart.bodyText.compile.string)
            user <- UserService.fetchUser(email)
          } yield (
            user,
            Password
              .check(password, implicitly[HasPassword[U]].password(user))
              .withArgon2()
          )

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

        request
          .as[Multipart[F]]
          .flatMap { multipart =>
            extractCredentials(multipart) match {
              case Some((emailPart, passwordPart)) =>
                authenticateUser(emailPart, passwordPart).value.flatMap {
                  case Right((user, authenticated)) =>
                    if (authenticated) createSessionResponse(user)
                    else loginRedirectWithError("Could not find user with that email or password")
                  case Left(_) =>
                    loginRedirectWithError("Could not find user with that email or password")
                }
              case None =>
                loginRedirectWithError("Missing email or password")
            }
          }
      case GET -> Root / "confirm" / code =>
        ConfirmationService.confirmByCode(code).value.flatMap {
          case Some(_) => BadRequest("Unable to confirm your account")
          case _           => loginRedirectWithSuccess("Account confirmed! You can now log in")
        }
      case request @ GET -> Root / "reset" =>
        withFlashCookie(request) {
          case None => Ok(DefaultLayout.render(DefaultResetRequestForm.page(None)))
          case Some(cookie) => Ok(DefaultLayout.render(DefaultResetRequestForm.page(Some(DefaultResetRequestForm.Flash(cssClass = "alert-danger", message = cookie.getOrElse("message", ""))))))
        }
      case request @ POST -> Root / "reset" =>
        request.as[Multipart[F]].flatMap { multipart =>
          val emailOpt = multipart.parts.find(_.name.contains("email"))
          emailOpt match {
            case Some(emailPart) => {
              val result = for {
                email <- EitherT.liftF(emailPart.bodyText.compile.string)
                user <- UserService.fetchUser(email)
                code <- ResetService.createReset(user)
                _ <- MailService.send(
                  MailService
                    .resetEmail(implicitly[HasEmail[U]].email(user), code)
                )
              } yield code

              result.value.flatMap { _ =>
                Ok(
                  DefaultLayout.render(
                    DefaultResetRequestForm.page(
                      Some(
                        DefaultResetRequestForm.Flash(cssClass = "alert-info", message = "A password reset e-mail has been sent to the provided user's email address, if they exist")
                      )
                    )
                  )
                )
              }
            }
            case None =>
              Ok(
                DefaultLayout.render(
                  DefaultResetRequestForm
                    .page(Some(DefaultResetRequestForm.Flash(cssClass = "alert-danger", message = "Form processing error, try submitting again")))
                )
              )
          }
        }
      case GET -> Root / "reset" / code => ResetService.getReset(code).value.flatMap {
        case Left(_) => resetRedirectWithError("Invalid or expired reset code")
        case Right(reset) => Ok(
          DefaultLayout.render(
            DefaultChangePasswordForm
              .page(None)
          )
        )
      }
      case request @ POST -> Root / "reset" / code => {

        def extractNewPassword(multipart: Multipart[F]): Option[(Part[F], Part[F])] =
          for {
            passwordPart <- multipart.parts.find(_.name.contains("password"))
            confirmPasswordPart <- multipart.parts.find(_.name.contains("confirmPassword"))
          } yield (passwordPart, confirmPasswordPart)

        def parsePasswordData(passwordPart: Part[F], confirmPasswordPart: Part[F]): F[(String, String)] =
          for {
            password <- passwordPart.bodyText.compile.string
            confirmPassword <- confirmPasswordPart.bodyText.compile.string
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
                    Some(s"Failed to update password: ${error.getMessage}")
                  )
                )
              )
          }

        def renderPasswordForm(error: Option[String] = None): F[Response[F]] =
          Ok(
            DefaultLayout.render(
              DefaultChangePasswordForm.page(error)
            )
          )

        ResetService.getReset(code).value.flatMap {
          case Left(_) =>
            renderPasswordForm(Some("Invalid or expired reset code"))
          case Right(reset) =>
            request.as[Multipart[F]].flatMap { multipart =>
              extractNewPassword(multipart) match {
                case Some((passwordPart, confirmPasswordPart)) =>
                  parsePasswordData(passwordPart, confirmPasswordPart).flatMap { (password, confirmPassword) =>
                    validatePasswords(password, confirmPassword) match {
                      case Some(validationError) =>
                        renderPasswordForm(Some(validationError))
                      case None =>
                        processPasswordReset(reset, password)
                    }
                  }
                case None =>
                    renderPasswordForm(Some("Missing password or password confirmation"))
                }
              }
          }
      }