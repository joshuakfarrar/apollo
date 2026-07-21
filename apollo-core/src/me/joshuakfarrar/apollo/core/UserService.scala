package me.joshuakfarrar.apollo.core

import cats.data.EitherT

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
