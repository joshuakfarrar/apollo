package io.github.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.github.joshuakfarrar.apollo.core.{AuthError, Hashable, UserService}

import java.sql.SQLException

object DoobieUserService {
  def apply[F[_], U, I](
      xa: Transactor[F]
  )(using
      S: Sync[F],
      R: Read[U],
      PW: Hashable[F, String]
  ): UserService[F, U, I] =
    new UserService[F, U, I] {

      // SQLState 23505 is a unique-constraint violation (SQL standard)
      private def isDuplicate(e: SQLException): Boolean =
        e.getSQLState == "23505" || e.getSQLState == "23000"

      private def insertUser(
          name: String,
          email: String,
          hashedPassword: String
      ): EitherT[F, AuthError, Unit] =
        EitherT {
          sql"INSERT INTO users (name, email, password) VALUES ($name, $email, $hashedPassword)".update.run
            .transact(xa)
            .map(_.asRight[AuthError].void)
            .recover { case e: SQLException if isDuplicate(e) =>
              AuthError.EmailTaken.asLeft
            }
        }

      def createUser(
          name: String,
          email: String,
          password: String
      ): EitherT[F, AuthError, U] =
        for {
          pw <- EitherT.liftF(PW.hash(password))
          _ <- insertUser(name, email, pw)
          user <- fetchUser(email)
        } yield user

      def fetchUser(email: String): EitherT[F, AuthError, U] =
        EitherT.fromOptionF(
          sql"SELECT id, name, email, password FROM users WHERE email = $email"
            .query[U]
            .option
            .transact(xa),
          AuthError.UserNotFound
        )

      def findBySessionToken(sessionToken: String): EitherT[F, AuthError, U] =
        EitherT.fromOptionF(
          sql"""
            SELECT u.id, u.name, u.email, u.password
            FROM users u
            INNER JOIN sessions s ON s.user_id = u.id
            WHERE s.token = $sessionToken
              AND s.expires_at > NOW()
          """
            .query[U]
            .option
            .transact(xa),
          AuthError.SessionNotFound
        )

      override def updatePassword(userId: I, password: String): F[Unit] =
        PW.hash(password).flatMap { pw =>
          sql"UPDATE users SET password = $pw WHERE id = ${userId.toString}::uuid".update.run
            .transact(xa)
            .void
        }
    }
}
