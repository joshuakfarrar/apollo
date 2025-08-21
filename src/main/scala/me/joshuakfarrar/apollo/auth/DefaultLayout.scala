package me.joshuakfarrar.apollo.auth

import scalatags.Text
import scalatags.Text.all.*

object DefaultLayout:
  def render(page: Text.TypedTag[String]): Text.TypedTag[String] = html(
    head(
      link(href := "/normalize.css/8.0.1/normalize.css", rel := "stylesheet"),
      link(
        href := "/bootstrap/5.3.3/dist/css/bootstrap.min.css",
        rel := "stylesheet"
      ),
      script(
        `type` := "text/javascript",
        src := "/jquery/4.0.0-beta.2/dist/jquery.min.js"
      ),
      script(
        `type` := "text/javascript",
        src := "/bootstrap/5.3.3/dist/js/bootstrap.min.js"
      )
    ),
    body(page)
  )
