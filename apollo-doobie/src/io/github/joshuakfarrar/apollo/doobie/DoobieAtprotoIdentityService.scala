package io.github.joshuakfarrar.apollo.doobie

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.std.Random
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.github.joshuakfarrar.apollo.core.{
  AtprotoAccount,
  AtprotoIdentityService,
  AuthError
}
import io.github.joshuakfarrar.apollo.core.util.generateAlphaNumericString

object DoobieAtprotoIdentityService {

  /** PROTOTYPE NOTES: a provisioned atproto user stores the DID in the
    * users.email column (it is unique and satisfies the schema) and an
    * unusable random string in users.password, so the password login
    * path can never match it. A real release should relax the schema
    * instead.
    */
  def apply[F[_], U, I](
      xa: Transactor[F]
  )(using
      R: Random[F],
      C: Concurrent[F],
      RU: Read[U]
  ): AtprotoIdentityService[F, U, I] =
    new AtprotoIdentityService[F, U, I] {

      override def findByDid(did: String): EitherT[F, AuthError, U] =
        EitherT.fromOptionF(
          sql"""
            SELECT u.id, u.name, u.email, u.password
            FROM users u
            INNER JOIN atproto_identities ai ON ai.user_id = u.id
            WHERE ai.did = $did
          """
            .query[U]
            .option
            .transact(xa),
          AuthError.UserNotFound
        )

      override def createFromAtproto(
          account: AtprotoAccount
      ): EitherT[F, AuthError, U] =
        EitherT.liftF(generateAlphaNumericString[F](64)).flatMap { junk =>
          val unusablePassword = s"!atproto!$junk"
          val program = for {
            _ <-
              sql"""
                INSERT INTO users (name, email, password)
                VALUES (${account.handle}, ${account.did}, $unusablePassword)
              """.update.run
            _ <-
              sql"""
                INSERT INTO atproto_identities (user_id, did, handle)
                VALUES (
                  (SELECT id FROM users WHERE email = ${account.did}),
                  ${account.did},
                  ${account.handle}
                )
              """.update.run
            user <-
              sql"""
                SELECT u.id, u.name, u.email, u.password
                FROM users u
                INNER JOIN atproto_identities ai ON ai.user_id = u.id
                WHERE ai.did = ${account.did}
              """.query[U].unique
          } yield user

          EitherT.liftF(program.transact(xa))
        }
    }
}
