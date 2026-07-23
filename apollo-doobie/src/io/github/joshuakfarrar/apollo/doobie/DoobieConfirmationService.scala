package io.github.joshuakfarrar.apollo.doobie

import cats.data.{EitherT, OptionT}
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.github.joshuakfarrar.apollo.core.{ConfirmationService, HasId}
import io.github.joshuakfarrar.apollo.core.util.generateAlphaNumericString

object DoobieConfirmationService {
  def apply[F[_], U, I](
      xa: Transactor[F]
  )(using
      R: Random[F],
      C: Concurrent[F],
      H: HasId[U, I]
  ): ConfirmationService[F, U, I] =
    new ConfirmationService[F, U, I] {

      override def createConfirmation(
          user: U
      ): EitherT[F, Throwable, String] = for {
        code <- EitherT.liftF(generateAlphaNumericString[F](32))
        res <-
          sql"""
            INSERT INTO confirmations (user_id, code)
            VALUES (${H.id(user).toString}::uuid, $code)
          """.update.run
            .transact(xa)
            .attemptT
      } yield code

      override def isConfirmed(user: U): EitherT[F, Throwable, Boolean] =
        EitherT {
          sql"SELECT confirmed_at IS NOT NULL FROM users WHERE id = ${H.id(user).toString}::uuid"
            .query[Boolean]
            .unique
            .transact(xa)
            .attempt
        }

      override def confirmByCode(code: String): OptionT[F, Throwable] =
        (
          sql"""
            UPDATE users SET confirmed_at = NOW()
            WHERE id = (SELECT user_id FROM confirmations WHERE code = $code)
          """.update.run,
          sql"DELETE FROM confirmations WHERE code = $code".update.run
        ).mapN(_ + _)
          .transact(xa)
          .attemptT
          .swap
          .toOption
    }
}
