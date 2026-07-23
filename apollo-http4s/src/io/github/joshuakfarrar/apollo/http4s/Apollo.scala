package io.github.joshuakfarrar.apollo.http4s

import io.github.joshuakfarrar.apollo.core.*
import org.http4s.server.middleware.CSRF
import org.typelevel.vault.Key
import play.twirl.api.Html

case class Flash(cssClass: String, message: String)

case class ApolloConfig[F[_]](
    csrfTokenKey: Key[String],
    csrf: CSRF[F, F]
)

case class ApolloTemplates(
    auth: (String, String, Option[Flash]) => Html,
    forgotPassword: (String, Option[Flash]) => Html,
    resetPassword: (String, Option[String]) => Html
)

object ApolloTemplates {
  def defaults: ApolloTemplates = ApolloTemplates(
    auth = (csrf, focus, flash) =>
      html.authForm(csrf, focus, flash.map(f => (f.cssClass, f.message))),
    forgotPassword = (csrf, flash) =>
      html.resetRequestForm(csrf, flash.map(f => (f.cssClass, f.message))),
    resetPassword = (csrf, error) => html.changePasswordForm(csrf, error)
  )
}

/** When `confirmation` is `None` (the default), registration signs the
  * user in immediately and the /confirm route is not served. Provide
  * `Some(service)` to require e-mail confirmation before login.
  */
case class ApolloServices[F[_], U, I, E](
    user: UserService[F, U, I],
    mail: MailService[F, E, Unit],
    session: SessionService[F, U, I],
    reset: ResetService[F, U, I],
    confirmation: Option[ConfirmationService[F, U, I]] = None
)

case class Apollo[F[_], U, I, E](
    config: ApolloConfig[F],
    templates: ApolloTemplates,
    services: ApolloServices[F, U, I, E]
)

object Apollo {
  def apply[F[_], U, I, E](
      config: ApolloConfig[F],
      services: ApolloServices[F, U, I, E]
  ): Apollo[F, U, I, E] = Apollo(config, ApolloTemplates.defaults, services)
}
