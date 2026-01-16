package me.joshuakfarrar.apollo.core

import cats.Monad
import cats.effect.std.Random
import cats.implicits.*

object util:
  def generateAlphaNumericString[F[_]: Monad](length: Int)(using
      R: Random[F]
  ): F[String] =
    R.nextAlphaNumeric.replicateA(length).map(_.mkString)
