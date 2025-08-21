package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits.*
import com.password4j.*
import doobie.*
import doobie.implicits.*

import java.util.UUID

trait UserService[F[_], U] {
  def createUser(
      name: String,
      email: String,
      password: String
  ): EitherT[F, Throwable, U]

  def fetchUser(email: String): EitherT[F, Throwable, U]
}

object UserService {
  def impl[F[_], U](xa: Transactor[F])(using S: Sync[F], R: Read[U]): UserService[F, U] =
    new UserService[F, U] {
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
          pw   <- EitherT.liftF(
            S.delay(
              Password.hash(password).withArgon2().getResult
            )
          )
          _    <- insertUser(name, email, pw)
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
    }
}
