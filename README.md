# Apollo

🚧 This code is under development and is NOT meant for production use! 🚧

An easy-to-use Devise-like authentication solution for Http4s. It will become configurable and more modular, so that you only need to use what you really need.

It has a few features:

- Database registration and authentication
- Account confirmation
- Password recovery

Long-term, we'd like to also support Omniauth and all functionality of the standard Devise module

## Getting Started

First, pull down the code and add Apollo to your local ivy cache:

```shell
PS C:\Users\you\apollo> .\mill.bat apollo-auth.publishLocal
```

This will allow you to add Apollo to your Http4s application as a dependency, for example:

```scala 3
object webApp extends SbtModule {
  def scalaVersion = "3.3.3"

  def mainClass = Some("Main")

  def ivyDeps = Agg(
    // your dependencies go here
    ivy"me.joshuakfarrar:apollo-auth:0.1.0-SNAPSHOT"
  )
}
```

Then, we include all the database tables you need to get started with SQL Server. Simply `cd` to `.\apollo\apollo-auth\src\db`:

```shell
PS C:\Users\you\apollo\apollo-auth\src\db> .\initialize-database.bat
```

Next, since we use a type-safe random string generator for various utilities, your `IOApp` must provide a source of randomness in the form of an `F[Random[F]]` instance, e.g.:

```scala 3
import cats.effect.std.Random
import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  given IO[Random[IO]] = Random.scalaUtilRandom[IO]
  val run: IO[Unit] = Server.run[IO](config)
```

Then, because we don't want to make assumptions about your user type, or the library you use to send e-mails, you must tell Apollo a few things about your app and configure the Apollo services:

```scala 3
object Server:

  // typeclass instances for User domain object
  given HasId[User, UUID] = _.id
  given HasPassword[User] = _.password
  given HasEmail[User] = _.email

  // database mapping instances for UUID type (Doobie needs this for IDs)
  given Get[UUID] = Get[String].map(UUID.fromString)
  given Put[UUID] = Put[String].contramap(_.toString)

  def run[F[_] : Async : Network](
    config: ApplicationConfiguration
  )(using C: Console[F], F: Monad[F], R: F[Random[F]]): F[Nothing] = {
    for {
      xa = getTransactor[F](config)
      userService = UserService.impl[F, User](xa)
        
      // we may eventually include my Mailgun implementation as the default Mailer
      mailService = new MailService[F, Email, Unit] {
        val mailgun = new Mailgun(
          domain = Uri
            .fromString(Mailgun.uri(config.mailgunDomain))
            .fold(throw _, identity),
          apiKey = config.mailgunKey
        )

        // you can customize the e-mails we send your users by simply overriding a function!
        // choose between sending html and plaintext
        override def confirmationEmail(to: String, code: String): Email = Email(
          EmailAddress(config.mailgunSender),
          EmailAddress(to),
          "Confirm your account",
          Some(s"Confirm your account (text): http://localhost:8080/confirm/${code}"),
          Some(s"Confirm your account (html): <a href='http://localhost:8080/confirm/${code}'>http://localhost:8080/confirm/${code}</a>")
        )

        override def send(msg: Email): EitherT[F, Throwable, Unit] = mailgun.send(msg).map(_ => ())
      }

      confirmationService = ConfirmationService.impl[F, User, UUID](xa)
      sessionService = SessionService.impl[F, User, UUID](xa)

      // we use cookie-based Flash Messages to send errors back to the form, check out the Nice Feature
      httpApp = FlashMiddleware
        .httpRoutes[F](
          webjarServiceBuilder[F].toRoutes
            <+> AuthRoutes.routes[F, User, Email, UUID](userService, confirmationService, mailService, sessionService) // adding our routes is as simple as configuring a service
        )
        .orNotFound

      _ <-
        EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(httpApp)
          .build
    } yield ()
  }.useForever
```

We promise we'll get this on Maven Central and add generators to automate some of the setup soon. Also be on the look out for a Giter8 template.

🎉