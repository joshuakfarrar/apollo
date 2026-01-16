package me.joshuakfarrar.apollo.doobie

import cats.data.{EitherT, OptionT}
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import me.joshuakfarrar.apollo.core.{HasId, Reset, ResetService}
import me.joshuakfarrar.apollo.core.util.generateAlphaNumericString

import java.time.{Instant, OffsetDateTime, ZoneOffset}

object ResetServiceDoobie {

  given Get[Instant] = Get[OffsetDateTime].map(_.toInstant)

  given Put[Instant] = Put[OffsetDateTime].contramap { instant =>
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
  }

  def impl[F[_], U, I: Get: Put](
      xa: Transactor[F]
  )(using
      R: Random[F],
      C: Concurrent[F],
      H: HasId[U, I]
  ): ResetService[F, U, I] =
    new ResetService[F, U, I] {

      override def createReset(
          user: U
      ): EitherT[F, Throwable, String] = for {
        code <- EitherT.liftF(generateAlphaNumericString[F](32))
        res <-
          sql"""
            INSERT INTO resets (user_id, code)
            VALUES (${H.id(user).toString}::uuid, $code)
          """.update.run
            .transact(xa)
            .attemptT
      } yield code

      override def getReset(code: String): EitherT[F, Throwable, Reset[I]] =
        EitherT {
          sql"SELECT user_id, code, created_at FROM resets WHERE code = $code"
            .query[Reset[I]]
            .unique
            .transact(xa)
            .attempt
        }

      override def invalidateReset(code: String) =
        sql"DELETE FROM resets WHERE code = $code".update.run
          .transact(xa)
          .attemptT
          .swap
          .toOption
    }
}
