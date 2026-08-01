package io.github.joshuakfarrar.apollo.doobie

import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.github.joshuakfarrar.apollo.core.{HasId, SessionService}
import io.github.joshuakfarrar.apollo.core.util.generateAlphaNumericString

object DoobieSessionService {
  def apply[F[_], U, I](
      xa: Transactor[F]
  )(using
      R: Random[F],
      C: Concurrent[F],
      H: HasId[U, I]
  ): SessionService[F, U, I] =
    new SessionService[F, U, I] {
      def createSession(user: U): F[String] =
        generateAlphaNumericString[F](256).flatMap { token =>
          sql"""
            INSERT INTO sessions (user_id, token, expires_at)
            VALUES (${H
              .id(user)
              .toString}::uuid, $token, NOW() + INTERVAL '2 hours')
          """.update.run
            .transact(xa)
            .as(token)
        }

      def deleteSession(token: String): F[Unit] =
        sql"DELETE FROM sessions WHERE token = $token".update.run
          .transact(xa)
          .void
    }
}
