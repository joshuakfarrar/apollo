package me.joshuakfarrar.apollo.core

import cats.data.EitherT

trait MailService[F[_], Msg, Res] {
  def confirmationEmail(to: String, code: String): Msg
  def resetEmail(to: String, code: String): Msg
  def send(msg: Msg): EitherT[F, Throwable, Res]
}
