package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import me.joshuakfarrar.apollo.auth.util.generateAlphaNumericString

trait SessionService[F[_], U, I] {
  def createSession(user: U): EitherT[F, Throwable, String]
  
  def deleteSession(token: String): EitherT[F, Throwable, Unit]
}

object SessionService {
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
            sql"insert into [webapp].[dbo].[sessions] (user_id, token, created_at, expires_at) values (${H.id(user).toString}, ${token}, CURRENT_TIMESTAMP, DATEADD(hour, 2, SYSDATETIMEOFFSET()))".update.run
              .transact(xa)
              .attemptT
        } yield token

      def deleteSession(token: String): EitherT[F, Throwable, Unit] =
        EitherT {
          sql"delete from [webapp].[dbo].[sessions] where token = $token".update.run
            .transact(xa)
            .attempt
            .map(_.void)
        }
    }
}
