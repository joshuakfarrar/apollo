package io.github.joshuakfarrar.apollo.core

import cats.data.EitherT

trait ConfirmationService[F[_], U, I] {
  def createConfirmation(user: U): F[String]

  def confirmByCode(code: String): EitherT[F, AuthError, Unit]

  def isConfirmed(user: U): F[Boolean]
}
