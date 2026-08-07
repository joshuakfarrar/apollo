package io.github.joshuakfarrar.apollo.http4s.atproto

import cats.effect.Sync
import cats.implicits.*

import java.math.BigInteger
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{
  ECGenParameterSpec,
  ECParameterSpec,
  ECPoint,
  ECPublicKeySpec,
  PKCS8EncodedKeySpec
}
import java.security.{
  AlgorithmParameters,
  KeyFactory,
  KeyPairGenerator,
  MessageDigest,
  SecureRandom,
  Signature
}
import java.util.Base64

/** Minimal JOSE for atproto OAuth: ES256 (P-256) JWTs, public JWKs, and
  * the DER <-> raw signature conversions JCA makes us do. JDK crypto
  * only; no additional dependencies.
  */
object Jose:
  final case class Es256KeyPair(privateKey: ECPrivateKey, publicJwk: ujson.Obj)

  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder

  def b64url(bytes: Array[Byte]): String = encoder.encodeToString(bytes)
  def b64urlDecode(s: String): Array[Byte] = decoder.decode(s)

  def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  private lazy val p256: ECParameterSpec = {
    val params = AlgorithmParameters.getInstance("EC")
    params.init(new ECGenParameterSpec("secp256r1"))
    params.getParameterSpec(classOf[ECParameterSpec])
  }

  /** Fixed-width 32-byte big-endian encoding of an EC coordinate. */
  private def fixed32(i: BigInteger): Array[Byte] = {
    val raw = i.toByteArray.dropWhile(_ == 0.toByte)
    require(raw.length <= 32, "coordinate exceeds 32 bytes")
    Array.fill[Byte](32 - raw.length)(0) ++ raw
  }

  def generate[F[_]: Sync]: F[Es256KeyPair] = Sync[F].delay {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom())
    val kp = kpg.generateKeyPair()
    val pub = kp.getPublic.asInstanceOf[ECPublicKey]
    Es256KeyPair(
      kp.getPrivate.asInstanceOf[ECPrivateKey],
      ujson.Obj(
        "kty" -> "EC",
        "crv" -> "P-256",
        "x" -> b64url(fixed32(pub.getW.getAffineX)),
        "y" -> b64url(fixed32(pub.getW.getAffineY))
      )
    )
  }

  def encodePrivateKey(key: ECPrivateKey): String =
    Base64.getEncoder.encodeToString(key.getEncoded) // PKCS#8

  def decodePrivateKey(encoded: String): ECPrivateKey =
    KeyFactory
      .getInstance("EC")
      .generatePrivate(
        new PKCS8EncodedKeySpec(Base64.getDecoder.decode(encoded))
      )
      .asInstanceOf[ECPrivateKey]

  def publicKeyFromJwk(jwk: ujson.Value): ECPublicKey = {
    val x = new BigInteger(1, b64urlDecode(jwk("x").str))
    val y = new BigInteger(1, b64urlDecode(jwk("y").str))
    KeyFactory
      .getInstance("EC")
      .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256))
      .asInstanceOf[ECPublicKey]
  }

  /** JCA emits DER ECDSA signatures; JOSE wants raw r||s (32+32 bytes). */
  private def derToRaw(der: Array[Byte]): Array[Byte] = {
    var i = if ((der(1) & 0xff) == 0x81) 3 else 2
    require(der(i) == 0x02, "malformed DER signature")
    val rLen = der(i + 1) & 0xff
    val r = der.slice(i + 2, i + 2 + rLen)
    i = i + 2 + rLen
    require(der(i) == 0x02, "malformed DER signature")
    val sLen = der(i + 1) & 0xff
    val s = der.slice(i + 2, i + 2 + sLen)
    fixed32(new BigInteger(1, r)) ++ fixed32(new BigInteger(1, s))
  }

  private def rawToDer(raw: Array[Byte]): Array[Byte] = {
    def derInt(bytes: Array[Byte]): Array[Byte] = {
      val bi = new BigInteger(1, bytes).toByteArray
      Array[Byte](0x02, bi.length.toByte) ++ bi
    }
    val r = derInt(raw.take(32))
    val s = derInt(raw.drop(32))
    val len = r.length + s.length
    if (len < 128) Array[Byte](0x30, len.toByte) ++ r ++ s
    else Array[Byte](0x30, 0x81.toByte, len.toByte) ++ r ++ s
  }

  def signJwt(
      privateKey: ECPrivateKey,
      header: ujson.Obj,
      payload: ujson.Obj
  ): String = {
    val signingInput =
      b64url(header.render().getBytes("UTF-8")) + "." +
        b64url(payload.render().getBytes("UTF-8"))
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(privateKey)
    signature.update(signingInput.getBytes("US-ASCII"))
    signingInput + "." + b64url(derToRaw(signature.sign()))
  }

  /** Verification half — used by tests to check our own proofs the way a
    * real authorization server would.
    */
  def verifyJwt(jwt: String, jwk: ujson.Value): Boolean =
    jwt.split('.') match {
      case Array(h, p, sig) =>
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKeyFromJwk(jwk))
        verifier.update(s"$h.$p".getBytes("US-ASCII"))
        verifier.verify(rawToDer(b64urlDecode(sig)))
      case _ => false
    }

  def decodeJwtPart(part: String): ujson.Value =
    ujson.read(new String(b64urlDecode(part), "UTF-8"))

  def secureRandomToken[F[_]: Sync](bytes: Int): F[String] = Sync[F].delay {
    val buf = new Array[Byte](bytes)
    new SecureRandom().nextBytes(buf)
    b64url(buf)
  }

object Pkce:
  /** RFC 7636 S256: challenge = b64url(sha256(ascii(verifier))). */
  def challenge(verifier: String): String =
    Jose.b64url(Jose.sha256(verifier.getBytes("US-ASCII")))

object Dpop:
  import org.http4s.{Method, Uri}

  import java.time.Instant
  import java.util.UUID

  /** A DPoP proof JWT for `method` + `url` (query and fragment stripped
    * per RFC 9449), optionally bound to a server-issued nonce.
    */
  def proof[F[_]: Sync](
      key: Jose.Es256KeyPair,
      method: Method,
      url: Uri,
      nonce: Option[String]
  ): F[String] = Sync[F].delay {
    val htu = url.copy(query = org.http4s.Query.empty, fragment = None)
    val header = ujson.Obj(
      "typ" -> "dpop+jwt",
      "alg" -> "ES256",
      "jwk" -> key.publicJwk
    )
    val payload = ujson.Obj(
      "jti" -> UUID.randomUUID().toString,
      "htm" -> method.name,
      "htu" -> htu.renderString,
      "iat" -> ujson.Num(Instant.now().getEpochSecond.toDouble)
    )
    nonce.foreach(n => payload("nonce") = ujson.Str(n))
    Jose.signJwt(key.privateKey, header, payload)
  }
