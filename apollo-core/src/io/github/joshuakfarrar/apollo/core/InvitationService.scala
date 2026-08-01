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
  def createInvitation(
      email: String,
      invitedBy: Option[U]
  ): EitherT[F, Throwable, String]

  def getInvitation(code: String): EitherT[F, Throwable, Invitation[I]]

  def consumeInvitation(code: String): EitherT[F, Throwable, Unit]
}
