package me.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import me.joshuakfarrar.apollo.core.{HasId, SessionService}
import me.joshuakfarrar.apollo.core.util.generateAlphaNumericString

object SessionServiceDoobie {
  def impl[F[_], U, I](
      xa: Transactor[F]
  )(using
      R: Random[F],
      C: Concurrent[F],
      H: HasId[U, I]
  ): SessionService[F, U, I] =
    new SessionService[F, U, I] {
      def createSession(user: U): EitherT[F, Throwable, String] =
        for {
          token <- EitherT.liftF(generateAlphaNumericString[F](256))
          _ <-
            sql"""
              INSERT INTO sessions (user_id, token, expires_at)
              VALUES (${H
                .id(user)
                .toString}::uuid, $token, NOW() + INTERVAL '2 hours')
            """.update.run
              .transact(xa)
              .attemptT
        } yield token

      def deleteSession(token: String): EitherT[F, Throwable, Unit] =
        EitherT {
          sql"DELETE FROM sessions WHERE token = $token".update.run
            .transact(xa)
            .attempt
            .map(_.void)
        }
    }
}
