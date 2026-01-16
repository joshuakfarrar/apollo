package me.joshuakfarrar.apollo.http4s

import cats.effect.kernel.Sync
import upickle.default.{readBinary, writeBinary}
import java.util.Base64

object FlashOps {
  def serialize[F[_]: Sync](
      map: Map[String, String]
  )(using S: Sync[F]): F[Either[Throwable, String]] =
    S.attempt(S.delay(Base64.getEncoder.encodeToString(writeBinary(map))))

  def deserialize[F[_]](
      string: String
  )(using S: Sync[F]): F[Either[Throwable, Map[String, String]]] = S.attempt(
    S.delay(readBinary[Map[String, String]](Base64.getDecoder.decode(string)))
  )
}
