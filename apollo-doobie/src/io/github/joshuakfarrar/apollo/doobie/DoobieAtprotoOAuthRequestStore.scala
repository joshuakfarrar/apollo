package io.github.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Concurrent
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import io.github.joshuakfarrar.apollo.core.{
  AtprotoOAuthRequest,
  AtprotoOAuthRequestStore,
  AuthError
}

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.duration.*

object DoobieAtprotoOAuthRequestStore {

  given Get[Instant] = Get[OffsetDateTime].map(_.toInstant)

  given Put[Instant] = Put[OffsetDateTime].contramap { instant =>
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
  }

  def apply[F[_]](
      xa: Transactor[F],
      requestTtl: FiniteDuration = 10.minutes
  )(using C: Concurrent[F]): AtprotoOAuthRequestStore[F] =
    new AtprotoOAuthRequestStore[F] {

      override def create(request: AtprotoOAuthRequest): F[Unit] =
        sql"""
          INSERT INTO atproto_oauth_requests
            (state, identifier, did, handle, issuer, token_endpoint,
             pkce_verifier, dpop_private_key, dpop_public_jwk)
          VALUES
            (${request.state}, ${request.identifier}, ${request.did},
             ${request.handle}, ${request.issuer}, ${request.tokenEndpoint},
             ${request.pkceVerifier}, ${request.dpopPrivateKey},
             ${request.dpopPublicJwk})
        """.update.run.transact(xa).void

      override def consume(
          state: String
      ): EitherT[F, AuthError, AtprotoOAuthRequest] =
        EitherT.fromOptionF(
          sql"""
            DELETE FROM atproto_oauth_requests
            WHERE state = $state
              AND created_at > NOW() - (${requestTtl.toSeconds} * INTERVAL '1 second')
            RETURNING state, identifier, did, handle, issuer, token_endpoint,
                      pkce_verifier, dpop_private_key, dpop_public_jwk,
                      created_at
          """
            .query[AtprotoOAuthRequest]
            .option
            .transact(xa),
          AuthError.OAuthRequestNotFound
        )
    }
}
