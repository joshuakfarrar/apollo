package io.github.joshuakfarrar.apollo.http4s

import cats.effect.Async
import cats.implicits.*
import io.github.joshuakfarrar.apollo.core.HasEmail
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.twirl.*
import play.twirl.api.Html

/** An optional scaffold page for `/` that shows whether a user is signed
  * in, with a log-out button. Apollo's auth flows redirect to `/`, so
  * mount this until the application has a real home page:
  *
  * {{{
  * WelcomeRoutes.routes(apollo) <+> AuthRoutes.routes(apollo)
  * }}}
  */
object WelcomeRoutes:
  def routes[F[_]: Async, U: HasEmail, I, E](
      apollo: Apollo[F, U, I, E],
      template: (String, Option[String]) => Html = (csrfToken, user) =>
        html.welcome(csrfToken, user)
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F]:
      case request @ GET -> Root =>
        request.attributes.lookup(apollo.config.csrfTokenKey) match {
          case Some(token) =>
            SessionAuth.optionalUser(request, apollo.services.user) {
              maybeUser =>
                Ok(
                  template(
                    token,
                    maybeUser.map(implicitly[HasEmail[U]].email)
                  )
                )
            }
          case None => Forbidden()
        }
