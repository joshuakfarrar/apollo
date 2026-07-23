package io.github.joshuakfarrar.apollo.core

import cats.effect.IO
import cats.effect.std.Random
import cats.implicits.*
import munit.CatsEffectSuite

class UtilSuite extends CatsEffectSuite:

  private def withRandom[A](f: Random[IO] ?=> IO[A]): IO[A] =
    Random.scalaUtilRandom[IO].flatMap { r =>
      given Random[IO] = r
      f
    }

  test("generateAlphaNumericString produces the requested length") {
    withRandom {
      util.generateAlphaNumericString[IO](256)
    }.map { s =>
      assertEquals(s.length, 256)
      assert(s.forall(_.isLetterOrDigit))
    }
  }

  test("generateAlphaNumericString produces distinct values") {
    withRandom {
      (
        util.generateAlphaNumericString[IO](32),
        util.generateAlphaNumericString[IO](32)
      ).tupled
    }.map { (a, b) =>
      assertNotEquals(a, b)
    }
  }
