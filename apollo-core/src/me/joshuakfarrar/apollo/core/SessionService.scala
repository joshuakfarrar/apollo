package me.joshuakfarrar.apollo.core

import cats.data.EitherT

trait SessionService[F[_], U, I] {
  def createSession(user: U): EitherT[F, Throwable, String]

  def deleteSession(token: String): EitherT[F, Throwable, Unit]
}
