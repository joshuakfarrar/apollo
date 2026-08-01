package io.github.joshuakfarrar.apollo.core

import cats.data.EitherT

trait UserService[F[_], U, I] {
  def createUser(
      name: String,
      email: String,
      password: String
  ): EitherT[F, AuthError, U]

  def fetchUser(email: String): EitherT[F, AuthError, U]

  def findBySessionToken(sessionToken: String): EitherT[F, AuthError, U]

  def updatePassword(userId: I, password: String): F[Unit]
}
