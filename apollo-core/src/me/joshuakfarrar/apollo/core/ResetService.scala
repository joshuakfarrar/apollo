package me.joshuakfarrar.apollo.core

import cats.data.{EitherT, OptionT}
import java.time.Instant

case class Reset[Id](userId: Id, code: String, createdAt: Instant)

trait ResetService[F[_], U, I] {
  def createReset(user: U): EitherT[F, Throwable, String]

  def getReset(code: String): EitherT[F, Throwable, Reset[I]]

  def invalidateReset(code: String): OptionT[F, Throwable]
}
