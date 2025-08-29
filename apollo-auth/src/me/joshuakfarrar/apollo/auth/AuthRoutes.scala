package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import com.microsoft.sqlserver.jdbc.SQLServerException
import com.password4j.Password
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Cookie, Location}
import org.http4s.{EntityDecoder, HttpRoutes, Response, ResponseCookie, SameSite}
import org.http4s.implicits.uri
import org.http4s.multipart.Multipart
import ScalatagsInstances.*

object AuthRoutes:
  def routes[F[_]: Async, U : HasPassword : HasEmail, E, I](
      UserService: UserService[F, U],
      ConfirmationService: ConfirmationService[F, U, I],
      MailService: MailService[F, E, Unit],
      SessionService: SessionService[F, U, I]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    def renderLogin(focus: DefaultAuthForm.Focus, flash: Option[String] = None) =
      DefaultLayout.render(DefaultAuthForm.page(focus, flash))

    def redirectWithFlashMessages(
        flashMessages: Map[String, String]
    ): F[Response[F]] =
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
              SeeOther(Location(uri"/")).map(_.addCookie(cookie))
            }
          )
        )

    def redirectWithFlash(location: String)(message: String) = {
      val flashMessages: Map[String, String] = Map(
        "message" -> message,
        "location" -> location
      )
      redirectWithFlashMessages(flashMessages)
    }

    def loginRedirectWithFlash: String => F[Response[F]] =
      redirectWithFlash("login") // lol, curry

    def registrationRedirectWithFlash: String => F[Response[F]] =
      redirectWithFlash("register")

    def createUser(name: String, email: String, password: String) =
      UserService.createUser(name, email, password).value.flatMap {
        case Right(user) => createConfirmation(user) //
        case Left(error) =>
          registrationRedirectWithFlash {
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
        _ <- MailService.send(MailService.confirmationEmail(implicitly[HasEmail[U]].email(user), code))
      } yield user

      confirmationResult.value.flatMap(
        _.fold(
          err => registrationRedirectWithFlash(s"failed: ${err.getMessage}"),
          user => Ok(s"u created, check the db lmao (nvm, loser: ${implicitly[HasPassword[U]].password(user)})")
        )
      )
    }

    HttpRoutes.of[F]:
      case request @ GET -> Root =>
        val flashCookie: Option[String] = request.headers
          .get[Cookie]
          .flatMap(_.values.find(_.name == "flash"))
          .map(_.content)

        val cookieDeserializationResult: F[Either[Throwable, Option[Map[String, String]]]] =
          flashCookie
            .map(FlashOps.deserialize)
            .sequence
            .map(_.sequence)

        cookieDeserializationResult.flatMap(
          _.fold(
            _ => BadRequest("failed to deserialize cookie, this is very bad"),
            deserializedCookie => {
              val location = deserializedCookie.flatMap(_.get("location"))
              val focus = location match {
                case Some("register") => DefaultAuthForm.Focus.Register
                case _ => DefaultAuthForm.Focus.Login
              }
              val message = deserializedCookie.flatMap(_.get("message"))
              Ok(renderLogin(focus, message))
            }
          )
        )
      case request @ POST -> Root / "register" =>
        request
          .as[Multipart[F]]
          .flatMap { multipart =>
            val nameOpt = multipart.parts.find(_.name.contains("name"))
            val emailOpt = multipart.parts.find(_.name.contains("email"))
            val passwordOpt = multipart.parts.find(_.name.contains("password"))
            val confirmPasswordOpt =
              multipart.parts.find(_.name.contains("confirmPassword"))
            (nameOpt, emailOpt, passwordOpt, confirmPasswordOpt) match {
              case (
                    Some(namePart),
                    Some(emailPart),
                    Some(passwordPart),
                    Some(confirmPasswordPart)
                  ) =>
                for {
                  name <- namePart.bodyText.compile.string
                  email <- emailPart.bodyText.compile.string
                  password <- passwordPart.bodyText.compile.string
                  confirmPassword <- confirmPasswordPart.bodyText.compile.string
                  formValidationError =
                    if (name.isEmpty)
                      Some("Name field cannot be empty")
                    else if (!password.equals(confirmPassword))
                      Some("Password and confirmation did not match")
                    else
                      None
                  response <- formValidationError match {
                    case Some(error) => registrationRedirectWithFlash(error)
                    case None        => createUser(name, email, password)
                  }
                } yield response
              case _ =>
                registrationRedirectWithFlash(
                  "Missing name, email, password, or password confirmation"
                )
            }
          }
      case request @ POST -> Root / "login" =>
        request
          .as[Multipart[F]]
          .flatMap(multipart =>
            val emailOpt = multipart.parts.find(_.name.contains("email"))
            val passwordOpt = multipart.parts.find(_.name.contains("password"))
            (emailOpt, passwordOpt) match {
              case (Some(emailPart), Some(passwordPart)) =>
                val result = for {
                  email <- EitherT.liftF(emailPart.bodyText.compile.string)
                  password <- EitherT.liftF(passwordPart.bodyText.compile.string)
                  user <- UserService.fetchUser(email)
                } yield (user, Password.check(password, implicitly[HasPassword[U]].password(user)).withArgon2())

                result.value.flatMap {
                  case Right((user, authenticated)) =>
                    if (authenticated) {
                      val token = for {
                        token <- SessionService.createSession(user)
                      } yield token
                      token.value.flatMap {
                        case Right(token) => SeeOther(Location(uri"/")).map(
                          _.addCookie(ResponseCookie(
                            name = "session_token",
                            content = token, // generated secure token
                            path = Some("/"),
                            httpOnly = true,
                            secure = true,
                            sameSite = Some(SameSite.Strict)
                          ))
                        )
                        case Left(error) => loginRedirectWithFlash(s"Could not create session, you are not logged in: ${error.getMessage()}")
                      }
                    } else
                      loginRedirectWithFlash("Could not find user with that email or password")

                  case Left(error) =>
                    loginRedirectWithFlash("Could not find user with that email or password")
                }
              case _ =>
                loginRedirectWithFlash("Missing email or password")
            }
          )
      case GET -> Root / "confirm" / code =>
        ConfirmationService.confirmByCode(code).value.flatMap {
          case Some(error) => BadRequest(error.getMessage)
          case _ => Ok("confirmed")
        }