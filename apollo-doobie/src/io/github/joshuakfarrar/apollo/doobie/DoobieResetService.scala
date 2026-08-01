package io.github.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import io.github.joshuakfarrar.apollo.core.{AuthError, HasId, Reset, ResetService}
import io.github.joshuakfarrar.apollo.core.util.generateAlphaNumericString

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.duration.*

object DoobieResetService {

  given Get[Instant] = Get[OffsetDateTime].map(_.toInstant)

  given Put[Instant] = Put[OffsetDateTime].contramap { instant =>
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
  }

  def apply[F[_], U, I: Get](
      xa: Transactor[F],
      resetTtl: FiniteDuration = 1.hour
  )(using
      R: Random[F],
      C: Concurrent[F],
      H: HasId[U, I]
  ): ResetService[F, U, I] =
    new ResetService[F, U, I] {

      override def createReset(user: U): F[String] =
        generateAlphaNumericString[F](32).flatMap { code =>
          sql"""
            INSERT INTO resets (user_id, code)
            VALUES (${H.id(user).toString}::uuid, $code)
          """.update.run
            .transact(xa)
            .as(code)
        }

      override def getReset(code: String): EitherT[F, AuthError, Reset[I]] =
        EitherT.fromOptionF(
          sql"""
            SELECT user_id, code, created_at FROM resets
            WHERE code = $code
              AND created_at > NOW() - (${resetTtl.toSeconds} * INTERVAL '1 second')
          """
            .query[Reset[I]]
            .option
            .transact(xa),
          AuthError.ResetNotFound
        )

      override def invalidateReset(code: String): F[Unit] =
        sql"DELETE FROM resets WHERE code = $code".update.run
          .transact(xa)
          .void
    }
}
