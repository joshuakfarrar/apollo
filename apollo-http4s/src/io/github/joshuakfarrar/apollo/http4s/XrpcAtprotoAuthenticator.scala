package io.github.joshuakfarrar.apollo.http4s

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.{
  AtprotoAccount,
  AtprotoAuthenticator,
  AuthError
}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.uri
import org.typelevel.log4cats.LoggerFactory

/** PROTOTYPE: verifies an atproto account with handle + app password via
  * com.atproto.server.createSession on the PDS (or entryway). The
  * production path is atproto OAuth (PAR + DPoP); it belongs behind the
  * same AtprotoAuthenticator trait when it lands.
  *
  * The default `pds` is Bluesky's entryway, which serves accounts hosted
  * on bsky.social. Accounts on self-hosted PDSes need `pds` pointed at
  * their host (full handle -> DID -> PDS resolution is future work).
  */
object XrpcAtprotoAuthenticator:
  def apply[F[_]: Async: LoggerFactory](
      client: Client[F],
      pds: Uri = uri"https://bsky.social"
  ): AtprotoAuthenticator[F] =
    new AtprotoAuthenticator[F] {
      private val log = LoggerFactory[F].getLogger

      override def verify(
          identifier: String,
          appPassword: String
      ): EitherT[F, AuthError, AtprotoAccount] = EitherT {
        val payload = ujson.Obj(
          "identifier" -> identifier,
          "password" -> appPassword
        )

        val request = Request[F](
          method = Method.POST,
          uri = pds / "xrpc" / "com.atproto.server.createSession"
        )
          .withEntity(payload.render())
          .withContentType(`Content-Type`(MediaType.application.json))

        client.run(request).use { response =>
          response.bodyText.compile.string.flatMap { body =>
            response.status match {
              case status if status.isSuccess =>
                Async[F]
                  .delay {
                    val json = ujson.read(body)
                    AtprotoAccount(
                      did = json("did").str,
                      handle = json("handle").str
                    )
                  }
                  .map(_.asRight[AuthError])
              case Status.BadRequest | Status.Unauthorized =>
                log
                  .debug(s"atproto createSession refused for $identifier")
                  .as(AuthError.AtprotoAuthFailed.asLeft[AtprotoAccount])
              case status =>
                new RuntimeException(
                  s"atproto createSession failed: HTTP ${status.code}"
                ).raiseError[F, Either[AuthError, AtprotoAccount]]
            }
          }
        }
      }
    }
