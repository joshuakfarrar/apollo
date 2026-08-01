package io.github.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import io.github.joshuakfarrar.apollo.core.{AuthError, HasId, Invitation, InvitationService}
import io.github.joshuakfarrar.apollo.core.util.generateAlphaNumericString

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.duration.*

object DoobieInvitationService {

  given Get[Instant] = Get[OffsetDateTime].map(_.toInstant)

  given Put[Instant] = Put[OffsetDateTime].contramap { instant =>
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
  }

  def apply[F[_], U, I: Get](
      xa: Transactor[F],
      invitationTtl: FiniteDuration = 7.days
  )(using
      R: Random[F],
      C: Concurrent[F],
      H: HasId[U, I]
  ): InvitationService[F, U, I] =
    new InvitationService[F, U, I] {

      override def createInvitation(
          email: String,
          invitedBy: Option[U]
      ): F[String] =
        generateAlphaNumericString[F](32).flatMap { code =>
          sql"""
            INSERT INTO invitations (code, email, invited_by)
            VALUES ($code, $email, ${invitedBy.map(H.id(_).toString)}::uuid)
          """.update.run
            .transact(xa)
            .as(code)
        }

      override def getInvitation(
          code: String
      ): EitherT[F, AuthError, Invitation[I]] =
        EitherT.fromOptionF(
          sql"""
            SELECT code, email, invited_by, created_at FROM invitations
            WHERE code = $code
              AND created_at > NOW() - (${invitationTtl.toSeconds} * INTERVAL '1 second')
          """
            .query[Invitation[I]]
            .option
            .transact(xa),
          AuthError.InvitationNotFound
        )

      override def consumeInvitation(code: String): F[Unit] =
        sql"DELETE FROM invitations WHERE code = $code".update.run
          .transact(xa)
          .void
    }
}
