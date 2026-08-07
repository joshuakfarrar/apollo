package io.github.joshuakfarrar.apollo.core

import cats.data.EitherT

/** An authenticated atproto ("Atmosphere") account: a DID and the handle
  * it currently resolves to.
  */
case class AtprotoAccount(did: String, handle: String)

/** Verifies control of an atproto account. The prototype implementation
  * uses handle + app password against the PDS
  * (com.atproto.server.createSession); an OAuth implementation can sit
  * behind the same trait later.
  */
trait AtprotoAuthenticator[F[_]] {
  def verify(
      identifier: String,
      appPassword: String
  ): EitherT[F, AuthError, AtprotoAccount]
}

/** Links atproto DIDs to local users. `createFromAtproto` provisions a
  * local account for a first-time atproto sign-in and links it.
  */
trait AtprotoIdentityService[F[_], U, I] {
  def findByDid(did: String): EitherT[F, AuthError, U]

  def createFromAtproto(account: AtprotoAccount): EitherT[F, AuthError, U]
}
