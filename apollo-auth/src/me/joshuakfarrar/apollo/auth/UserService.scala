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

  def findBySessionToken(sessionToken: String): EitherT[F, Throwable, U]

  def updatePassword(
      userId: I,
      password: String
  ): EitherT[F, Throwable, Unit]
}

object UserService {
  def impl[F[_], U, I: Put](
      xa: Transactor[F]
  )(using
      S: Sync[F],
      R: Read[U],
      H: HasId[U, I],
      PW: Hashable[F, String]
  ): UserService[F, U, I] =
    new UserService[F, U, I] {
      def insertUser(
          name: String,
          email: String,
          password: String
      ): EitherT[F, Throwable, Int] =
        EitherT {
          sql"INSERT INTO users (name, email, password) VALUES ($name, $email, $password)".update.run
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
          sql"SELECT id, name, email, password FROM users WHERE email = $email"
            .query[U]
            .unique
            .transact(xa)
            .attempt
        }

      def findBySessionToken(sessionToken: String): EitherT[F, Throwable, U] =
        EitherT {
          sql"""
            SELECT u.id, u.name, u.email, u.password
            FROM users u
            INNER JOIN sessions s ON s.user_id = u.id
            WHERE s.token = $sessionToken
              AND s.expires_at > NOW()
          """
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
          _ <- EitherT {
            sql"UPDATE users SET password = $pw WHERE id = $userId".update.run
              .transact(xa)
              .attempt
          }
        } yield ()
    }
}
