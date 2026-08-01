package io.github.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.github.joshuakfarrar.apollo.core.{AuthError, ConfirmationService, HasId}
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

      override def createConfirmation(user: U): F[String] =
        generateAlphaNumericString[F](32).flatMap { code =>
          sql"""
            INSERT INTO confirmations (user_id, code)
            VALUES (${H.id(user).toString}::uuid, $code)
          """.update.run
            .transact(xa)
            .as(code)
        }

      override def confirmByCode(code: String): EitherT[F, AuthError, Unit] =
        EitherT {
          (for {
            updated <-
              sql"""
                UPDATE users SET confirmed_at = NOW()
                WHERE id = (SELECT user_id FROM confirmations WHERE code = $code)
              """.update.run
            _ <- sql"DELETE FROM confirmations WHERE code = $code".update.run
          } yield updated)
            .transact(xa)
            .map { updated =>
              if (updated > 0) ().asRight[AuthError]
              else AuthError.ConfirmationNotFound.asLeft
            }
        }

      override def isConfirmed(user: U): F[Boolean] =
        sql"SELECT confirmed_at IS NOT NULL FROM users WHERE id = ${H.id(user).toString}::uuid"
          .query[Boolean]
          .option
          .transact(xa)
          .map(_.getOrElse(false))
    }
}
