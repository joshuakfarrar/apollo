package io.github.joshuakfarrar.apollo.http4s.atproto

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.{
  AtprotoAccount,
  AtprotoOAuthRequest,
  AtprotoOAuthRequestStore,
  AuthError
}
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString
import org.typelevel.log4cats.LoggerFactory

import java.time.Instant

/** Public-client atproto OAuth configuration.
  *
  * @param clientId
  *   the HTTPS URL where this app serves its client metadata document —
  *   AtprotoRoutes serves it at /oauth/client-metadata.json, so this is
  *   normally s"$publicOrigin/oauth/client-metadata.json"
  * @param redirectUri
  *   normally s"$publicOrigin/atproto/callback"
  */
final case class AtprotoOAuthConfig(
    clientId: Uri,
    redirectUri: Uri,
    clientName: String = "Apollo",
    scope: String = "atproto",
    plcDirectory: Uri = Uri.unsafeFromString("https://plc.directory")
)

trait AtprotoOAuthClient[F[_]] {
  /** The client metadata document this app must serve at `clientId`. */
  def clientMetadata: String

  /** Resolve the identity, push the authorization request (PAR), persist
    * flow state, and return the authorization URL to redirect the user
    * to.
    */
  def start(identifier: String): EitherT[F, AuthError, Uri]

  /** Redeem the callback: single-use state lookup, issuer check, token
    * exchange with the flow's DPoP key, and subject verification.
    */
  def callback(
      state: String,
      iss: String,
      code: String
  ): EitherT[F, AuthError, AtprotoAccount]
}

object AtprotoOAuthClient:
  private val DpopNonceHeader = CIString("DPoP-Nonce")

  def apply[F[_]: Async: LoggerFactory](
      client: Client[F],
      config: AtprotoOAuthConfig,
      store: AtprotoOAuthRequestStore[F]
  ): AtprotoOAuthClient[F] =
    new AtprotoOAuthClient[F] {
      private val log = LoggerFactory[F].getLogger

      override val clientMetadata: String =
        ujson
          .Obj(
            "client_id" -> config.clientId.renderString,
            "client_name" -> config.clientName,
            "application_type" -> "web",
            "grant_types" -> ujson.Arr("authorization_code", "refresh_token"),
            "response_types" -> ujson.Arr("code"),
            "redirect_uris" -> ujson.Arr(config.redirectUri.renderString),
            "scope" -> config.scope,
            "token_endpoint_auth_method" -> "none",
            "dpop_bound_access_tokens" -> true
          )
          .render(indent = 2)

      /** POST a form with a DPoP proof, honoring the RFC 9449 nonce
        * dance: on `use_dpop_nonce`, retry once with the server-issued
        * nonce from the DPoP-Nonce header.
        */
      private def postWithDpop(
          endpoint: Uri,
          form: UrlForm,
          key: Jose.Es256KeyPair
      ): F[(Status, String)] = {
        def attempt(nonce: Option[String]): F[(Status, String, Option[String])] =
          Dpop.proof(key, Method.POST, endpoint, nonce).flatMap { proof =>
            val request = Request[F](Method.POST, endpoint)
              .withHeaders(Header.Raw(CIString("DPoP"), proof))
              .withEntity(form)
            client.run(request).use { response =>
              response.bodyText.compile.string.map { body =>
                val nextNonce =
                  response.headers.get(DpopNonceHeader).map(_.head.value)
                (response.status, body, nextNonce)
              }
            }
          }

        def wantsNonce(status: Status, body: String): Boolean =
          (status == Status.BadRequest || status == Status.Unauthorized) &&
            scala.util
              .Try(ujson.read(body).obj.get("error").exists(_.str == "use_dpop_nonce"))
              .getOrElse(false)

        attempt(None).flatMap {
          case (status, body, Some(nonce)) if wantsNonce(status, body) =>
            attempt(Some(nonce)).map { case (s, b, _) => (s, b) }
          case (status, body, _) => (status, body).pure[F]
        }
      }

      override def start(identifier: String): EitherT[F, AuthError, Uri] =
        for {
          resolved <- AtprotoResolver.resolve(
            client,
            config.plcDirectory,
            identifier
          )
          key <- EitherT.liftF(Jose.generate[F])
          state <- EitherT.liftF(Jose.secureRandomToken[F](24))
          verifier <- EitherT.liftF(Jose.secureRandomToken[F](48))
          parForm = UrlForm(
            "client_id" -> config.clientId.renderString,
            "response_type" -> "code",
            "redirect_uri" -> config.redirectUri.renderString,
            "state" -> state,
            "scope" -> config.scope,
            "code_challenge" -> Pkce.challenge(verifier),
            "code_challenge_method" -> "S256",
            "login_hint" -> identifier
          )
          parResult <- EitherT.liftF(
            postWithDpop(resolved.parEndpoint, parForm, key)
          )
          requestUri <- EitherT(parResult match {
            case (status, body) if status.isSuccess =>
              Async[F]
                .delay(ujson.read(body)("request_uri").str)
                .map(_.asRight[AuthError])
                .handleErrorWith(e =>
                  log
                    .warn(e)("atproto PAR returned an unparseable response")
                    .as(AuthError.AtprotoAuthFailed.asLeft[String])
                )
            case (status, body) =>
              log
                .warn(
                  s"atproto PAR rejected (HTTP ${status.code}): ${body.take(300)}"
                )
                .as(AuthError.AtprotoAuthFailed.asLeft[String])
          })
          now <- EitherT.liftF(
            Async[F].realTimeInstant
          )
          _ <- EitherT.liftF(
            store.create(
              AtprotoOAuthRequest(
                state = state,
                identifier = identifier,
                did = resolved.did,
                handle = resolved.handle,
                issuer = resolved.issuer,
                tokenEndpoint = resolved.tokenEndpoint.renderString,
                pkceVerifier = verifier,
                dpopPrivateKey = Jose.encodePrivateKey(key.privateKey),
                dpopPublicJwk = key.publicJwk.render(),
                createdAt = now
              )
            )
          )
        } yield resolved.authorizationEndpoint
          .withQueryParam("client_id", config.clientId.renderString)
          .withQueryParam("request_uri", requestUri)

      override def callback(
          state: String,
          iss: String,
          code: String
      ): EitherT[F, AuthError, AtprotoAccount] =
        for {
          pending <- store.consume(state)
          // RFC 9207: the issuer that redirected back must be the one we
          // pushed the request to
          _ <- EitherT.cond[F](
            iss == pending.issuer,
            (),
            AuthError.AtprotoAuthFailed
          )
          key <- EitherT.liftF(Async[F].delay {
            Jose.Es256KeyPair(
              Jose.decodePrivateKey(pending.dpopPrivateKey),
              ujson.read(pending.dpopPublicJwk).asInstanceOf[ujson.Obj]
            )
          })
          tokenEndpoint <- EitherT.fromEither[F](
            Uri
              .fromString(pending.tokenEndpoint)
              .leftMap(_ => AuthError.AtprotoAuthFailed)
          )
          tokenForm = UrlForm(
            "grant_type" -> "authorization_code",
            "code" -> code,
            "redirect_uri" -> config.redirectUri.renderString,
            "client_id" -> config.clientId.renderString,
            "code_verifier" -> pending.pkceVerifier
          )
          tokenResult <- EitherT.liftF(
            postWithDpop(tokenEndpoint, tokenForm, key)
          )
          subject <- EitherT(tokenResult match {
            case (status, body) if status.isSuccess =>
              Async[F]
                .delay(ujson.read(body)("sub").str)
                .map(_.asRight[AuthError])
                .handleErrorWith(e =>
                  log
                    .warn(e)("atproto token response was unparseable")
                    .as(AuthError.AtprotoAuthFailed.asLeft[String])
                )
            case (status, body) =>
              log
                .warn(
                  s"atproto token exchange rejected (HTTP ${status.code}): ${body.take(300)}"
                )
                .as(AuthError.AtprotoAuthFailed.asLeft[String])
          })
          // the tokens must belong to the DID we resolved at the start
          _ <- EitherT.cond[F](
            subject == pending.did,
            (),
            AuthError.AtprotoAuthFailed
          )
        } yield AtprotoAccount(pending.did, pending.handle)
    }
