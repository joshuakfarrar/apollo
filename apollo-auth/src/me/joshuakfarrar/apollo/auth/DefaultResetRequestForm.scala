package me.joshuakfarrar.apollo.auth

import scalatags.Text
import scalatags.Text.all.*

object DefaultResetRequestForm:
  case class Flash(cssClass: String, message: String)

  def page(
      flash: Option[Flash] = None
  ): Text.TypedTag[String] = div(
    h2(id := "title", "Password reset"),
    p(
      "Request a password reset"
    ),
    div(
      style := "max-width: 26rem",
      flash.map { f =>
        div(`class` := "alert " + f.cssClass, role := "alert", f.message + ".")
      },
      div(
        `class` := "tab-content",
        div(
          `class` := "",
          `id` := "pills-login",
          role := "tabpanel",
          form(
            `action` := "/reset",
            `method` := "POST",
            `enctype` := "multipart/form-data",
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "email",
                `name` := "email",
                `id` := "loginName",
                `placeholder` := "Email",
                `required` := true
              )
            ),
            div(
              `class` := "d-grid mx-auto",
              button(
                `class` := "btn btn-primary btn-block mb-4",
                `type` := "submit",
                "Submit request"
              )
            ),
            div(
              `class` := "text-center",
              a(
                href := "/",
                `class` := "btn btn-link p-0 me-2",
                "Back to login"
              )
            )
          )
        )
      )
    )
  )
