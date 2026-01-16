package me.joshuakfarrar.apollo.core

import cats.data.{EitherT, OptionT}

trait ConfirmationService[F[_], U, I] {
  def createConfirmation(user: U): EitherT[F, Throwable, String]

  def confirmByCode(code: String): OptionT[F, Throwable]
}
