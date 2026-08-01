package io.github.joshuakfarrar.apollo.core

trait SessionService[F[_], U, I] {
  def createSession(user: U): F[String]

  def deleteSession(token: String): F[Unit]
}
