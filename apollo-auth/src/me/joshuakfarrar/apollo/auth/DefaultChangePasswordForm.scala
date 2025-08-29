package me.joshuakfarrar.apollo.auth

import scalatags.Text
import scalatags.Text.all.*

object DefaultChangePasswordForm:
  def page(
      flash: Option[String] = None
  ): Text.TypedTag[String] = div(
    h2(id := "title", "Password reset"),
    p(
      "Change your password"
    ),
    div(
      style := "max-width: 26rem",
      flash.map { message =>
        div(`class` := "alert alert-danger", role := "alert", message + ".")
      },
      div(
        `class` := "tab-content",
        div(
          `class` := "",
          `id` := "pills-login",
          role := "tabpanel",
          form(
            `method` := "POST",
            `enctype` := "multipart/form-data",
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "password",
                `name` := "password",
                `id` := "resetPassword",
                `placeholder` := "Password"
              )
            ),
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "password",
                `name` := "confirmPassword",
                `id` := "resetConfirmPassword",
                `placeholder` := "Confirm password"
              )
            ),
            div(
              `class` := "d-grid mx-auto",
              button(
                `class` := "btn btn-primary btn-block mb-4",
                `type` := "submit",
                "Change password"
              )
            )
          )
        )
      )
    )
  )
