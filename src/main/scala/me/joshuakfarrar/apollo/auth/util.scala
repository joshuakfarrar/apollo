package me.joshuakfarrar.apollo.auth

import cats.Monad
import cats.effect.std.Random
import cats.implicits.*

object util:
  def generateAlphaNumericString[F[_]: Monad](length: Int)(using
      R: F[Random[F]]
  ): F[String] =
    R.flatMap(_.nextAlphaNumeric.replicateA(length).map(_.mkString))
