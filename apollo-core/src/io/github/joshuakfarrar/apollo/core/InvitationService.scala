package io.github.joshuakfarrar.apollo.core

import cats.data.EitherT

import java.time.Instant

case class Invitation[Id](
    code: String,
    email: String,
    invitedBy: Option[Id],
    createdAt: Instant
)

trait InvitationService[F[_], U, I] {
  def createInvitation(email: String, invitedBy: Option[U]): F[String]

  def getInvitation(code: String): EitherT[F, AuthError, Invitation[I]]

  def consumeInvitation(code: String): F[Unit]
}
