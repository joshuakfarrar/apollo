package io.github.joshuakfarrar.apollo.core

/** Expected business outcomes of the auth flows. These travel on the
  * typed left of `EitherT[F, AuthError, A]`; infrastructure failures
  * (database down, driver bugs) travel in `F`'s own error channel and
  * never appear here.
  */
enum AuthError:
  case EmailTaken
  case UserNotFound
  case SessionNotFound
  case ConfirmationNotFound
  case ResetNotFound
  case InvitationNotFound
  case AtprotoAuthFailed
