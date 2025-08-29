package me.joshuakfarrar.apollo.auth

import scalatags.Text
import scalatags.Text.all._

object DefaultAuthForm:
  enum Focus:
    case Login
    case Register

  private def activeLink(focus: Focus, displayIf: Focus) =
    s"nav-link${if (focus == displayIf) " active" else ""}"

  private def activePane(focus: Focus, displayIf: Focus) =
    s"tab-pane fade${if (focus == displayIf) " show active" else ""}"

  def page(
      focus: Focus = Focus.Login,
      flash: Option[String] = None
  ): Text.TypedTag[String] = div(
    h2(id := "title", "apollo"),
    p(
      "uwu >:3 potato"
    ),
    div(
      style := "max-width: 26rem",
      flash.map { message =>
        div(`class` := "alert alert-danger", role := "alert", message + ".")
      },
      ul(
        `class` := "nav nav-pills nav-justified mb-3",
        li(
          `class` := "nav-item",
          role := "presentation",
          button(
            `class` := activeLink(focus, Focus.Login),
            `type` := "button",
            role := "tab",
            data.bs.toggle := "pill",
            data.bs.target := "#pills-login",
            aria.controls := "pills-login",
            aria.selected := true,
            "Login"
          )
        ),
        li(
          `class` := "nav-item",
          role := "presentation",
          button(
            `class` := activeLink(focus, Focus.Register),
            `type` := "button",
            role := "tab",
            data.bs.toggle := "pill",
            data.bs.target := "#pills-register",
            aria.controls := "pills-register",
            aria.selected := false,
            "Register"
          )
        )
      ),
      div(
        `class` := "tab-content",
        div(
          `class` := activePane(focus, Focus.Login),
          `id` := "pills-login",
          role := "tabpanel",
          form(
            `action` := "/login",
            `method` := "POST",
            `enctype` := "multipart/form-data",
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "email",
                `name` := "email",
                `id` := "loginName",
                `placeholder` := "Email"
              )
            ),
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "password",
                `name` := "password",
                `id` := "loginPassword",
                `placeholder` := "Password"
              )
            ),
            div(
              `class` := "row mb-4",
              div(
                `class` := "col-md-6 d-flex justify-content-center",
                div(
                  `class` := "form-check mb-3 mb-md-0",
                  input(
                    `class` := "form-check-input",
                    `type` := "checkbox",
                    `value` := "",
                    `id` := "loginCheck",
                    checked := true
                  ),
                  label(
                    `class` := "form-label",
                    `for` := "loginCheck",
                    " Remember me"
                  )
                )
              ),
              div(
                `class` := "col-md-6 d-flex justify-content-center",
                a(href := "#", "Forgot password?")
              )
            ),
            div(
              `class` := "d-grid mx-auto",
              button(
                `class` := "btn btn-primary btn-block mb-4",
                `type` := "submit",
                "Sign in"
              )
            )
          )
        ),
        div(
          `class` := activePane(focus, Focus.Register),
          `id` := "pills-register",
          role := "tabpanel",
          form(
            `action` := "/register",
            `method` := "POST",
            `enctype` := "multipart/form-data",
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "text",
                `name` := "name",
                `id` := "registerName",
                `placeholder` := "Full name"
              )
            ),
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "email",
                `name` := "email",
                `id` := "registerEmail",
                `placeholder` := "E-mail address"
              )
            ),
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "password",
                `name` := "password",
                `id` := "registerPassword",
                `placeholder` := "Password"
              )
            ),
            div(
              `class` := "form-outline mb-4",
              input(
                `class` := "form-control",
                `type` := "password",
                `name` := "confirmPassword",
                `id` := "registerConfirmPassword",
                `placeholder` := "Confirm password"
              )
            ),
            div(
              `class` := "d-grid mx-auto",
              button(
                `class` := "btn btn-primary btn-block mb-4",
                `type` := "submit",
                "Sign up"
              )
            )
          )
        )
      )
    )
  )
