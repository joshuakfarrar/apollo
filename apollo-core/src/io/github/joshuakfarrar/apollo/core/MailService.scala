package io.github.joshuakfarrar.apollo.core

import cats.effect.std.Console

trait MailService[F[_], Msg, Res] {
  def confirmationEmail(to: String, code: String): Msg
  def resetEmail(to: String, code: String): Msg
  def invitationEmail(to: String, code: String): Msg
  def send(msg: Msg): F[Res]
}

object MailService:
  /** Prints mail to the console instead of sending it, so the reset flow
    * works before a real mail provider is configured. Codes appear in the
    * server log; never use this in production.
    */
  def console[F[_]: Console]: MailService[F, String, Unit] =
    new MailService[F, String, Unit]:
      def confirmationEmail(to: String, code: String): String =
        s"To: $to — confirm your account: /confirm/$code"

      def resetEmail(to: String, code: String): String =
        s"To: $to — reset your password: /reset/$code"

      def invitationEmail(to: String, code: String): String =
        s"To: $to — you're invited! Create your account: /invite/$code"

      def send(msg: String): F[Unit] =
        Console[F].println(s"[apollo mail] $msg")
