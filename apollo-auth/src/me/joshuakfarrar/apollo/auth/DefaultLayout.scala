package me.joshuakfarrar.apollo.auth

import scalatags.Text
import scalatags.Text.all.*

object DefaultLayout:
  def render(page: Text.TypedTag[String]): Text.TypedTag[String] = html(
    head(
      meta(charset := "utf-8"),
      meta(name := "viewport", content := "width=device-width, initial-scale=1"),
      link(href := "/normalize.css/8.0.1/normalize.css", rel := "stylesheet"),
      link(href := "/bootstrap/5.3.3/dist/css/bootstrap.min.css", rel := "stylesheet"),
      script(`type` := "text/javascript", src := "/jquery/4.0.0-beta.2/dist/jquery.min.js"),
      script(`type` := "text/javascript", src := "/bootstrap/5.3.3/dist/js/bootstrap.min.js")
    ),
    body(
      cls := "bg-body-tertiary",
      div(
        cls := "container min-vh-100 d-flex align-items-center justify-content-center py-5",
        div(
          cls := "row justify-content-center w-100",
          div(
            cls := "col-12 col-sm-10 col-md-8 col-lg-6 col-xl-5",
            div(
              cls := "card border-0 shadow-none rounded-3 bg-white",
              div(
                cls := "card-body p-4 p-md-5",
                page
              )
            )
          )
        )
      )
    )
  )
