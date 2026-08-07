package io.github.joshuakfarrar.apollo.http4s.atproto

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.AuthError
import org.http4s.Uri
import org.http4s.client.Client
import org.typelevel.log4cats.LoggerFactory

/** Everything the OAuth flow needs to know about an identity, resolved
  * per the atproto identity + OAuth specs:
  *
  * handle --(DNS TXT `_atproto.` | HTTPS /.well-known/atproto-did)--> DID
  * DID --(plc.directory | did:web)--> DID document --> PDS
  * PDS --(/.well-known/oauth-protected-resource)--> authorization server
  * issuer --(/.well-known/oauth-authorization-server)--> endpoints
  */
final case class ResolvedIdentity(
    did: String,
    handle: String,
    pds: Uri,
    issuer: String,
    authorizationEndpoint: Uri,
    tokenEndpoint: Uri,
    parEndpoint: Uri
)

object AtprotoResolver:

  /** Resolution failures are expected business outcomes (bad handle,
    * vanished PDS) and surface as AtprotoAuthFailed; the underlying
    * cause is logged at debug.
    */
  def resolve[F[_]: Async: LoggerFactory](
      client: Client[F],
      plcDirectory: Uri,
      identifier: String
  ): EitherT[F, AuthError, ResolvedIdentity] = {
    val log = LoggerFactory[F].getLogger

    def failWith[A](context: String)(error: Throwable): F[Either[AuthError, A]] =
      log
        .debug(error)(s"atproto resolution failed: $context")
        .as(AuthError.AtprotoAuthFailed.asLeft[A])

    def step[A](context: String)(fa: F[A]): EitherT[F, AuthError, A] =
      EitherT(fa.map(_.asRight[AuthError]).handleErrorWith(failWith(context)))

    def fetchText(uri: Uri): F[String] =
      client.expect[String](uri)

    def fetchJson(uri: Uri): F[ujson.Value] =
      client.expect[String](uri).map(ujson.read(_))

    def dnsTxtDid(handle: String): F[Option[String]] = Async[F].blocking {
      try {
        val env = new java.util.Hashtable[String, String]()
        env.put(
          "java.naming.factory.initial",
          "com.sun.jndi.dns.DnsContextFactory"
        )
        env.put("com.sun.jndi.dns.timeout.initial", "2000")
        env.put("com.sun.jndi.dns.timeout.retries", "1")
        val attributes = new javax.naming.directory.InitialDirContext(env)
          .getAttributes(s"_atproto.$handle", Array("TXT"))
        Option(attributes.get("TXT")).flatMap { attr =>
          (0 until attr.size)
            .map(attr.get(_).toString.replace("\"", "").trim)
            .collectFirst {
              case value if value.startsWith("did=") =>
                value.stripPrefix("did=")
            }
        }
      } catch { case _: Throwable => None }
    }

    def wellKnownDid(handle: String): F[Option[String]] =
      Uri.fromString(s"https://$handle/.well-known/atproto-did") match {
        case Left(_) => Async[F].pure(None)
        case Right(uri) =>
          fetchText(uri).attempt.map {
            case Right(body) if body.trim.startsWith("did:") =>
              Some(body.trim)
            case _ => None
          }
      }

    def resolveHandle(handle: String): EitherT[F, AuthError, String] =
      EitherT {
        dnsTxtDid(handle).flatMap {
          case Some(did) => did.asRight[AuthError].pure[F]
          case None =>
            wellKnownDid(handle).map {
              case Some(did) => did.asRight[AuthError]
              case None      => AuthError.AtprotoAuthFailed.asLeft[String]
            }
        }
      }

    def didDocumentUri(did: String): EitherT[F, AuthError, Uri] =
      if (did.startsWith("did:plc:"))
        EitherT.rightT(plcDirectory / did)
      else if (did.startsWith("did:web:")) {
        val host = did.stripPrefix("did:web:")
        EitherT.fromEither(
          Uri
            .fromString(s"https://$host/.well-known/did.json")
            .leftMap(_ => AuthError.AtprotoAuthFailed)
        )
      } else EitherT.leftT(AuthError.AtprotoAuthFailed)

    def pdsFromDoc(doc: ujson.Value): Option[Uri] =
      doc.obj
        .get("service")
        .map(_.arr)
        .flatMap {
          _.find { svc =>
            svc.obj.get("id").exists(_.str.endsWith("#atproto_pds")) ||
            svc.obj
              .get("type")
              .exists(_.str == "AtprotoPersonalDataServer")
          }
        }
        .flatMap(svc =>
          Uri.fromString(svc("serviceEndpoint").str).toOption
        )

    def handleFromDoc(doc: ujson.Value): Option[String] =
      doc.obj
        .get("alsoKnownAs")
        .map(_.arr)
        .flatMap(_.collectFirst {
          case aka if aka.str.startsWith("at://") =>
            aka.str.stripPrefix("at://")
        })

    def parseUri(s: String): EitherT[F, AuthError, Uri] =
      EitherT.fromEither(
        Uri.fromString(s).leftMap(_ => AuthError.AtprotoAuthFailed)
      )

    for {
      did <-
        if (identifier.startsWith("did:")) EitherT.rightT[F, AuthError](identifier)
        else resolveHandle(identifier.toLowerCase)
      docUri <- didDocumentUri(did)
      doc <- step(s"DID document for $did")(fetchJson(docUri))
      docHandle <- EitherT.fromOption[F](
        handleFromDoc(doc),
        AuthError.AtprotoAuthFailed
      )
      // bidirectional verification: the DID document must claim the
      // handle the user typed (unless they typed the DID itself)
      _ <- EitherT.cond[F](
        identifier.startsWith("did:") ||
          docHandle.equalsIgnoreCase(identifier),
        (),
        AuthError.AtprotoAuthFailed
      )
      pds <- EitherT.fromOption[F](
        pdsFromDoc(doc),
        AuthError.AtprotoAuthFailed
      )
      resource <- step(s"protected resource metadata for $pds")(
        fetchJson(pds / ".well-known" / "oauth-protected-resource")
      )
      issuer <- EitherT.fromOption[F](
        resource.obj
          .get("authorization_servers")
          .flatMap(_.arr.headOption)
          .map(_.str),
        AuthError.AtprotoAuthFailed
      )
      issuerUri <- parseUri(issuer)
      metadata <- step(s"authorization server metadata for $issuer")(
        fetchJson(issuerUri / ".well-known" / "oauth-authorization-server")
      )
      // the metadata document must claim the issuer we fetched it from
      _ <- EitherT.cond[F](
        metadata.obj.get("issuer").exists(_.str == issuer),
        (),
        AuthError.AtprotoAuthFailed
      )
      authorize <- parseUri(metadata("authorization_endpoint").str)
      token <- parseUri(metadata("token_endpoint").str)
      par <- parseUri(
        metadata("pushed_authorization_request_endpoint").str
      )
    } yield ResolvedIdentity(
      did = did,
      handle = docHandle,
      pds = pds,
      issuer = issuer,
      authorizationEndpoint = authorize,
      tokenEndpoint = token,
      parEndpoint = par
    )
  }
