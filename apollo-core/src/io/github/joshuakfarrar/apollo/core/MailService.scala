package io.github.joshuakfarrar.apollo.core

import cats.Applicative
import cats.data.EitherT
import cats.effect.std.Console

trait MailService[F[_], Msg, Res] {
  def confirmationEmail(to: String, code: String): Msg
  def resetEmail(to: String, code: String): Msg
  def send(msg: Msg): EitherT[F, Throwable, Res]
}

object MailService:
  /** Prints mail to the console instead of sending it, so the reset flow
    * works before a real mail provider is configured. Codes appear in the
    * server log; never use this in production.
    */
  def console[F[_]: Console: Applicative]: MailService[F, String, Unit] =
    new MailService[F, String, Unit]:
      def confirmationEmail(to: String, code: String): String =
        s"To: $to — confirm your account: /confirm/$code"

      def resetEmail(to: String, code: String): String =
        s"To: $to — reset your password: /reset/$code"

      def send(msg: String): EitherT[F, Throwable, Unit] =
        EitherT.liftF(Console[F].println(s"[apollo mail] $msg"))
