package me.joshuakfarrar.apollo.auth

import cats.data.{EitherT, OptionT}
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits._
import doobie.implicits.javatimedrivernative._
import me.joshuakfarrar.apollo.auth.util.generateAlphaNumericString
import java.util.UUID
import java.time.{Instant, OffsetDateTime, ZoneOffset}

case class Reset[Id](userId: Id, code: String, createdAt: Instant)

trait ResetService[F[_], U, I] {
  def createReset(user: U): EitherT[F, Throwable, String]

  def getReset(code: String): EitherT[F, Throwable, Reset[I]]

  def invalidateReset(code: String): OptionT[F, Throwable]
}

object ResetService {

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
          sql"insert into [webapp].[dbo].[resets] (user_id, code, created_at) values (${H.id(user).toString}, ${code}, CURRENT_TIMESTAMP)".update.run
            .transact(xa)
            .attemptT
      } yield code

      override def getReset(code: String): EitherT[F, Throwable, Reset[I]] =
        EitherT {
          sql"select user_id, code, created_at from [webapp].[dbo].[resets] where code = ${code}"
            .query[Reset[I]]
            .unique
            .transact(xa)
            .attempt
        }

      override def invalidateReset(code: String) =
        sql"delete from [webapp].[dbo].[resets] where code = ${code}".update.run
          .transact(xa)
          .attemptT
          .swap
          .toOption // eats the Int, we only care about errors
    }
}
