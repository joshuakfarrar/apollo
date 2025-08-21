package me.joshuakfarrar.apollo.auth

import org.http4s.Charset.`UTF-8`
import org.http4s.headers.`Content-Type`
import org.http4s.{Charset, EntityEncoder, MediaType}
import scalatags.generic.Frag

object ScalatagsInstances:
  implicit def scalatagsEncoder[F[_], C <: Frag[_, String]](implicit
      charset: Charset = `UTF-8`
  ): EntityEncoder[F, C] =
    contentEncoder(MediaType.text.html)

  private def contentEncoder[F[_], C <: Frag[_, String]](
      mediaType: MediaType
  )(implicit charset: Charset = `UTF-8`): EntityEncoder[F, C] =
    EntityEncoder
      .stringEncoder[F]
      .contramap[C](content => content.render)
      .withContentType(`Content-Type`(mediaType, charset))
