package io.github.joshuakfarrar.apollo.http4s.atproto

import cats.effect.IO
import cats.implicits.*
import munit.CatsEffectSuite

class JoseSuite extends CatsEffectSuite:

  test("ES256 sign/verify round-trips") {
    Jose.generate[IO].map { key =>
      val jwt = Jose.signJwt(
        key.privateKey,
        ujson.Obj("typ" -> "dpop+jwt", "alg" -> "ES256", "jwk" -> key.publicJwk),
        ujson.Obj("htm" -> "POST", "htu" -> "https://example.test/par")
      )
      assert(Jose.verifyJwt(jwt, key.publicJwk))
    }
  }

  test("a tampered payload fails verification") {
    Jose.generate[IO].map { key =>
      val jwt = Jose.signJwt(
        key.privateKey,
        ujson.Obj("alg" -> "ES256"),
        ujson.Obj("htm" -> "POST")
      )
      val Array(h, _, s) = jwt.split('.')
      val forged = h + "." + Jose.b64url("""{"htm":"GET"}""".getBytes) + "." + s
      assert(!Jose.verifyJwt(forged, key.publicJwk))
    }
  }

  test("a signature from a different key fails verification") {
    (Jose.generate[IO], Jose.generate[IO]).flatMapN { (a, b) =>
      IO {
        val jwt = Jose.signJwt(a.privateKey, ujson.Obj("alg" -> "ES256"), ujson.Obj("x" -> 1))
        assert(!Jose.verifyJwt(jwt, b.publicJwk))
      }
    }
  }

  test("private keys survive PKCS#8 encode/decode") {
    Jose.generate[IO].map { key =>
      val restored = Jose.decodePrivateKey(Jose.encodePrivateKey(key.privateKey))
      val jwt = Jose.signJwt(restored, ujson.Obj("alg" -> "ES256"), ujson.Obj("n" -> 42))
      assert(Jose.verifyJwt(jwt, key.publicJwk))
    }
  }

  test("PKCE S256 matches the RFC 7636 test vector") {
    assertEquals(
      Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    )
  }
