package io.github.joshuakfarrar.apollo.http4s

import cats.data.{EitherT, OptionT}
import cats.effect.{IO, Ref}
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.http4s.server.middleware.CSRF
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import org.typelevel.vault.Key
import play.twirl.api.Html

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

case class TestUser(
    id: UUID,
    name: String,
    email: String,
    password: String,
    confirmed: Boolean
)

/** In-memory stub services and a fully wired Apollo for route tests. */
trait ApolloTestKit extends CatsEffectSuite:

  given LoggerFactory[IO] = NoOpFactory[IO]

  given HasId[TestUser, UUID] = _.id
  given HasEmail[TestUser] = _.email
  given HasPassword[TestUser] = _.password

  given Hashable[IO, String] with
    def hash(plain: String): IO[String] = IO.pure(s"hashed:$plain")
    def verify(plain: String, hashed: String): IO[Boolean] =
      IO.pure(hashed == s"hashed:$plain")

  case class World(
      users: Ref[IO, Map[String, TestUser]],
      sessions: Ref[IO, Map[String, UUID]],
      resets: Ref[IO, Map[String, UUID]],
      mails: Ref[IO, List[String]],
      apollo: Apollo[IO, TestUser, UUID, String]
  ):
    def routes: HttpApp[IO] = AuthRoutes.routes(apollo).orNotFound
    def tokenKey: Key[String] = apollo.config.csrfTokenKey

  def newWorld(confirmationEnabled: Boolean = false): IO[World] =
    for {
      users <- Ref.of[IO, Map[String, TestUser]](Map.empty)
      sessions <- Ref.of[IO, Map[String, UUID]](Map.empty)
      resets <- Ref.of[IO, Map[String, UUID]](Map.empty)
      mails <- Ref.of[IO, List[String]](List.empty)
      key <- CSRF.generateSigningKey[IO]()
      tokenKey <- Key.newKey[IO, String]
    } yield {
      val csrf = CSRF[IO, IO](key, _ => true).build

      val userService = new UserService[IO, TestUser, UUID]:
        def createUser(
            name: String,
            email: String,
            password: String
        ): EitherT[IO, Throwable, TestUser] =
          for {
            pw <- EitherT.liftF(summon[Hashable[IO, String]].hash(password))
            user <- EitherT(users.modify { m =>
              if (m.contains(email))
                (m, Left(new SQLException("duplicate", "23505")))
              else {
                val u = TestUser(
                  UUID.randomUUID(),
                  name,
                  email,
                  pw,
                  confirmed = false
                )
                (m + (email -> u), Right(u))
              }
            })
          } yield user

        def fetchUser(email: String): EitherT[IO, Throwable, TestUser] =
          EitherT.fromOptionF(
            users.get.map(_.get(email)),
            new NoSuchElementException(email)
          )

        def findBySessionToken(
            token: String
        ): EitherT[IO, Throwable, TestUser] =
          for {
            id <- EitherT.fromOptionF(
              sessions.get.map(_.get(token)),
              new NoSuchElementException(token)
            )
            user <- EitherT.fromOptionF(
              users.get.map(_.values.find(_.id == id)),
              new NoSuchElementException(token)
            )
          } yield user

        def updatePassword(
            userId: UUID,
            password: String
        ): EitherT[IO, Throwable, Unit] =
          for {
            pw <- EitherT.liftF(summon[Hashable[IO, String]].hash(password))
            _ <- EitherT.liftF(users.update(_.map { case (k, u) =>
              if (u.id == userId) (k, u.copy(password = pw)) else (k, u)
            }))
          } yield ()

      val sessionService = new SessionService[IO, TestUser, UUID]:
        def createSession(user: TestUser): EitherT[IO, Throwable, String] =
          EitherT.liftF {
            val token = s"session-${UUID.randomUUID()}"
            sessions.update(_ + (token -> user.id)).as(token)
          }

        def deleteSession(token: String): EitherT[IO, Throwable, Unit] =
          EitherT.liftF(sessions.update(_ - token))

      val mailService = new MailService[IO, String, Unit]:
        def confirmationEmail(to: String, code: String): String =
          s"confirm|$to|$code"
        def resetEmail(to: String, code: String): String =
          s"reset|$to|$code"
        def send(msg: String): EitherT[IO, Throwable, Unit] =
          EitherT.liftF(mails.update(msg :: _))

      val confirmationService = new ConfirmationService[IO, TestUser, UUID]:
        def createConfirmation(
            user: TestUser
        ): EitherT[IO, Throwable, String] =
          EitherT.pure(s"confcode-${user.id}")

        def confirmByCode(code: String): OptionT[IO, Throwable] =
          OptionT {
            users.modify { m =>
              m.values.find(u => code == s"confcode-${u.id}") match {
                case Some(u) =>
                  (m + (u.email -> u.copy(confirmed = true)), None)
                case None =>
                  (m, Some(new NoSuchElementException(code): Throwable))
              }
            }
          }

        def isConfirmed(user: TestUser): EitherT[IO, Throwable, Boolean] =
          EitherT.liftF(users.get.map(_.get(user.email).exists(_.confirmed)))

      val resetService = new ResetService[IO, TestUser, UUID]:
        def createReset(user: TestUser): EitherT[IO, Throwable, String] =
          EitherT.liftF {
            val code = s"resetcode-${UUID.randomUUID()}"
            resets.update(_ + (code -> user.id)).as(code)
          }

        def getReset(code: String): EitherT[IO, Throwable, Reset[UUID]] =
          EitherT.fromOptionF(
            resets.get.map(_.get(code).map(Reset(_, code, Instant.EPOCH))),
            new NoSuchElementException(code)
          )

        def invalidateReset(code: String): OptionT[IO, Throwable] =
          OptionT(resets.update(_ - code).as(None: Option[Throwable]))

      val services = ApolloServices[IO, TestUser, UUID, String](
        user = userService,
        mail = mailService,
        session = sessionService,
        reset = resetService,
        confirmation =
          if (confirmationEnabled) Some(confirmationService) else None
      )

      val templates = ApolloTemplates(
        auth = (_, focus, flash) =>
          Html(s"auth:$focus:${flash.map(_.message).getOrElse("")}"),
        forgotPassword =
          (_, flash) => Html(s"forgot:${flash.map(_.message).getOrElse("")}"),
        resetPassword = (_, error) => Html(s"resetform:${error.getOrElse("")}")
      )

      World(
        users,
        sessions,
        resets,
        mails,
        Apollo(ApolloConfig[IO](tokenKey, csrf), templates, services)
      )
    }

  def withToken(w: World, req: Request[IO]): Request[IO] =
    req.withAttribute(w.tokenKey, "test-csrf-token")

  def register(
      w: World,
      email: String = "a@b.c",
      password: String = "password1"
  ): IO[Response[IO]] =
    w.routes.run(
      Request[IO](Method.POST, uri"/register").withEntity(
        UrlForm(
          "name" -> "Test User",
          "email" -> email,
          "password" -> password,
          "confirmPassword" -> password
        )
      )
    )

  def login(w: World, email: String, password: String): IO[Response[IO]] =
    w.routes.run(
      Request[IO](Method.POST, uri"/login").withEntity(
        UrlForm("email" -> email, "password" -> password)
      )
    )

  def sessionCookie(res: Response[IO]): Option[String] =
    res.cookies
      .find(c => c.name == "session_token" && c.content.nonEmpty)
      .map(_.content)

  def flashMessage(res: Response[IO]): IO[Option[String]] =
    res.cookies.find(_.name == "flash").map(_.content) match {
      case None => IO.pure(None)
      case Some(content) =>
        FlashOps
          .deserialize[IO](content)
          .map(_.toOption.flatMap(_.get("message")))
    }
