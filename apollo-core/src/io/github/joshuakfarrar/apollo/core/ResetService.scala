package io.github.joshuakfarrar.apollo.core

import cats.data.EitherT

import java.time.Instant

case class Reset[Id](userId: Id, code: String, createdAt: Instant)

trait ResetService[F[_], U, I] {
  def createReset(user: U): F[String]

  def getReset(code: String): EitherT[F, AuthError, Reset[I]]

  def invalidateReset(code: String): F[Unit]
}
