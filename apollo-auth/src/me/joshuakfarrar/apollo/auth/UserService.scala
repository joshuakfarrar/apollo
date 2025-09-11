package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits.*
import doobie.*
import doobie.implicits.*

trait UserService[F[_], U, I] {
  def createUser(
      name: String,
      email: String,
      password: String
  ): EitherT[F, Throwable, U]

  def fetchUser(email: String): EitherT[F, Throwable, U]

  def updatePassword(
      userId: I,
      password: String
  ): EitherT[F, Throwable, Unit]
}

object UserService {
  def impl[F[_], U, I: Put](
      xa: Transactor[F]
  )(using S: Sync[F], R: Read[U], H: HasId[U, I], PW: Hashable[F, String]): UserService[F, U, I] =
    new UserService[F, U, I] {
      def insertUser(
          name: String,
          email: String,
          password: String
      ): EitherT[F, Throwable, Int] =
        EitherT {
          sql"insert into [webapp].[dbo].[users] (name, email, password, created_at) values ($name, $email, $password, CURRENT_TIMESTAMP)".update.run
            .transact(xa)
            .attempt
        }

      def createUser(
          name: String,
          email: String,
          password: String
      ): EitherT[F, Throwable, U] = {
        for {
          pw <- EitherT.liftF(PW.hash(password))
          _ <- insertUser(name, email, pw)
          user <- fetchUser(email)
        } yield user
      }

      def fetchUser(email: String): EitherT[F, Throwable, U] =
        EitherT {
          sql"select id, name, email, password from [webapp].[dbo].[users] where email = ${email}"
            .query[U]
            .unique
            .transact(xa)
            .attempt
        }

      override def updatePassword(
          userId: I,
          password: String
      ): EitherT[F, Throwable, Unit] =
        for {
          pw <- EitherT.liftF(PW.hash(password))
          _ <- EitherT { sql"update [webapp].[dbo].[users] set password = ${pw} where id = ${userId}".update.run
            .transact(xa)
            .attempt }
        } yield ()
    }
}
