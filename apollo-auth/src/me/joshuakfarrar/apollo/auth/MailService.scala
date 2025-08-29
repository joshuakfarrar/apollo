package me.joshuakfarrar.apollo.auth

import cats.data.EitherT
import cats.effect.{Async, Concurrent}
import fs2.io.net.Network

trait MailService[F[_], Msg, Res] {
  def confirmationEmail(to: String, code: String): Msg
  def resetEmail(to: String, code: String): Msg
  def send(msg: Msg): EitherT[F, Throwable, Res]
}