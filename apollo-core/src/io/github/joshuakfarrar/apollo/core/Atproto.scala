package io.github.joshuakfarrar.apollo.core

import cats.data.EitherT

import java.time.Instant

/** An authenticated atproto ("Atmosphere") account: a DID and the handle
  * it currently resolves to.
  */
case class AtprotoAccount(did: String, handle: String)

/** Links atproto DIDs to local users. `createFromAtproto` provisions a
  * local account for a first-time atproto sign-in and links it.
  */
trait AtprotoIdentityService[F[_], U, I] {
  def findByDid(did: String): EitherT[F, AuthError, U]

  def createFromAtproto(account: AtprotoAccount): EitherT[F, AuthError, U]
}

/** A pending atproto OAuth authorization request: everything the callback
  * needs to finish the flow. Stored server-side between the redirect out
  * and the redirect back; single-use; expires quickly.
  */
case class AtprotoOAuthRequest(
    state: String,
    identifier: String,
    did: String,
    handle: String,
    issuer: String,
    tokenEndpoint: String,
    pkceVerifier: String,
    dpopPrivateKey: String,
    dpopPublicJwk: String,
    createdAt: Instant
)

trait AtprotoOAuthRequestStore[F[_]] {
  def create(request: AtprotoOAuthRequest): F[Unit]

  /** Fetch-and-delete: a state may be redeemed exactly once. */
  def consume(state: String): EitherT[F, AuthError, AtprotoOAuthRequest]
}
