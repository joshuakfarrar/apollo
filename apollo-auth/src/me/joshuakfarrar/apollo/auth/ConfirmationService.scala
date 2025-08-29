package me.joshuakfarrar.apollo.auth

import cats.Monad
import cats.data.{EitherT, OptionT}
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import me.joshuakfarrar.apollo.auth.util.generateAlphaNumericString

trait ConfirmationService[F[_], U, I] {
  def createConfirmation(user: U): EitherT[F, Throwable, String]

  def confirmByCode(code: String): OptionT[F, Throwable]
}

object ConfirmationService {

  def impl[F[_], U, I](
      xa: Transactor[F]
  )(using R: Random[F], C: Concurrent[F], H: HasId[U, I]): ConfirmationService[F, U, I] =
    new ConfirmationService[F, U, I] {

      override def createConfirmation(
          user: U
      ): EitherT[F, Throwable, String] = for {
        code <- EitherT.liftF(generateAlphaNumericString[F](32))
        res <-
          sql"insert into [webapp].[dbo].[confirmations] (user_id, code, created_at) values (${H.id(user).toString}, ${code}, CURRENT_TIMESTAMP)".update.run
            .transact(xa)
            .attemptT
      } yield code

      override def confirmByCode(code: String) =
        (
          sql"update [webapp].[dbo].[users] set confirmed_at = CURRENT_TIMESTAMP where id = (select user_id from [webapp].[dbo].[confirmations] where code = ${code})".update.run,
          sql"delete from [webapp].[dbo].[confirmations] where code = ${code}".update.run
        ).mapN(_ + _)
          .transact(xa)
          .attemptT
          .swap
          .toOption // eats the Int, we only care about errors
    }
}
