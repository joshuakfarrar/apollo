package io.github.joshuakfarrar.apollo.http4s

import cats.effect.IO
import munit.CatsEffectSuite

class FlashOpsSuite extends CatsEffectSuite:

  test("serialize/deserialize round-trips a flash map") {
    val flash = Map(
      "message" -> "hello, world",
      "cssClass" -> "alert-success",
      "location" -> "register"
    )
    FlashOps.serialize[IO](flash).flatMap {
      case Left(err) => IO(fail(s"serialize failed: $err"))
      case Right(encoded) =>
        FlashOps
          .deserialize[IO](encoded)
          .map(decoded => assertEquals(decoded, Right(flash)))
    }
  }

  test("deserialize rejects garbage") {
    FlashOps
      .deserialize[IO]("!!!definitely-not-base64!!!")
      .map(result => assert(result.isLeft))
  }
