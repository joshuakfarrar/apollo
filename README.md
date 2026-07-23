# Apollo

[![Scala](https://img.shields.io/badge/Scala%203-%23de3423.svg?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![CI](https://github.com/joshuakfarrar/apollo/actions/workflows/ci.yml/badge.svg)](https://github.com/joshuakfarrar/apollo/actions/workflows/ci.yml)

An easy-to-use Devise-like authentication solution for Http4s. It's modular, so you only depend on what you really need.

It has a few features:

- Database registration and authentication
- Account confirmation
- Password recovery

Long-term, we'd like to also support Omniauth and all functionality of the standard Devise module

## Modules

Apollo ships as three artifacts so you only depend on what you need:

| Module | What's inside | Depends on |
|--------|---------------|------------|
| `apollo-core` | Type classes (`HasId`, `HasEmail`, `HasPassword`, `Hashable`) and the service traits | cats, cats-effect-std |
| `apollo-doobie` | Doobie/PostgreSQL implementations of the service traits (`DoobieUserService`, ...) | `apollo-core`, doobie |
| `apollo-http4s` | `AuthRoutes`, `SessionAuth`, CSRF & flash middleware, default Twirl templates | `apollo-core`, http4s |

`apollo-http4s` never touches the database and `apollo-doobie` never touches HTTP — bring your own implementations of the `apollo-core` traits (Skunk, Slick, a different mailer) and everything still composes.

```scala
mvnDeps = Seq(
  mvn"io.github.joshuakfarrar::apollo-http4s:0.1.0-SNAPSHOT",
  mvn"io.github.joshuakfarrar::apollo-doobie:0.1.0-SNAPSHOT"
)
```

### Account confirmation is opt-in

Out of the box Apollo needs no mail provider: registration signs the user
straight in, and `MailService.console` prints password-reset codes to the
server log instead of sending e-mail.

```scala
given Console[F] = Console.make[F]

ApolloServices[F, User, UserId, String](
  user = DoobieUserService[F, User, UserId](xa),
  mail = MailService.console[F],
  session = DoobieSessionService[F, User, UserId](xa),
  reset = DoobieResetService[F, User, UserId](xa)
)
```

To require e-mail confirmation before login, provide the service and a real
mailer:

```scala
  mail = mailgunMailService, // your MailService[F, E, Unit]
  confirmation = Some(DoobieConfirmationService[F, User, UserId](xa))
```

When `confirmation` is `None`, the `/confirm` route is not served. When it is
provided, login is refused until the account is confirmed.

### Security notes

- Session tokens and confirmation/reset codes are generated from the
  `Random[F]` you provide. Use a cryptographically secure one:
  `Random.javaSecuritySecureRandom[F]`, not `Random.scalaUtilRandom[F]`.
- Password-reset codes expire after one hour by default; tune with
  `DoobieResetService(xa, resetTtl = 30.minutes)`.
- `MailService.console` prints reset codes to the server log — development
  only.

### A default home page

Apollo's flows redirect to `/` after login, logout, and registration.
Until your application has a real home page, mount the optional scaffold
that shows who is signed in (with a working log-out button):

```scala
WelcomeRoutes.routes(apollo) <+> AuthRoutes.routes(apollo)
```

Replace it by dropping that line — Apollo never claims `/` on its own.

## Getting Started

First, pull down Apollo and add it to your local ivy cache:

```shell
$ git clone https://github.com/joshuakfarrar/apollo
$ cd apollo
$ .\mill.bat --no-server __.publishLocal
```

Then, use our fancy new [Giter8](https://www.foundweekends.org/giter8) template!

```shell
$ .\mill.bat --interactive init joshuakfarrar/apollo.g8
$ cd webapp
$ .\db\initalize-database.bat # ⚠️ warning: this will destroy and reinitialize your database!
```

Configure the application in `.\webapp\resources\application.conf`, then, run the server:

```shell
webapp $ .\mill.bat --no-server webapp.run
```

Browse to [http://localhost:8080](http://localhost:8080) *et voilà!*

## Publishing to Maven Central

Releases go through the [Sonatype Central Portal](https://central.sonatype.com). With `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` and a PGP key configured:

```shell
$ mill mill.scalalib.SonatypeCentralPublishModule/publishAll --publishArtifacts __.publishArtifacts
```

The `io.github.joshuakfarrar` namespace is verified on the portal automatically via GitHub account ownership. Pushing a `v*` tag publishes automatically via GitHub Actions.

We promise we'll add generators to automate some of the setup soon.

🎉
