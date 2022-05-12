package gov.usa.apollo.client

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import cats.effect.{ExitCode, IO, IOApp}
import japgolly.scalajs.react.vdom.PackageBase.VdomAttr
import org.scalajs.dom.document

object Main extends IOApp {

  val Page =
    ScalaComponent.builder[Unit]
      .renderStatic(
        <.div(
          <.section(
            ^.cls := "usa-banner site-banner",
            ^.aria.label := "Official government website",
            <.div(
              ^.cls := "usa-accordion",
              <.header(^.cls := "usa-banner__header",
                <.div(^.cls := "usa-banner__inner",
                  <.div(^.cls := "grid-col-auto",
                    <.img(^.cls := "usa-banner__header-flag", ^.src := "/img/us_flag_small.png", ^.alt := "U.S. flag")
                  ),
                  <.div(^.cls := "grid-col-fill tablet:grid-col-auto",
                    <.p(^.cls := "usa-banner__header-text", "An official website of the United States government"),
                    <.p(^.cls := "usa-banner__header-action", ^.aria.hidden := true, "Here's how you know")
                  ),
                  <.button(^.cls := "usa-accordion__button usa-banner__button",
                    ^.aria.expanded := false,
                    ^.aria.controls := "gov-banner",
                    <.span(^.cls := "usa-banner__button-text", "Here's how you know")
                  )
                )
              ),
              <.div(^.cls := "usa-banner__content", ^.id := "gov-banner", ^.hidden := true,
                <.div(^.cls := "grid-row grid-gap-lg",
                  <.div(^.cls := "usa-banner__guidance tablet:grid-col-6",
                    <.img(
                      ^.cls := "usa-banner__icon usa-media-block__img",
                      ^.src := "/img/icon-dot-gov.svg",
                      ^.role := "img",
                      ^.aria.hidden := true
                    ),
                    <.div(^.cls := "usa-media-block__body",
                      <.p(<.strong("Official websites use. gov"), <.br(), "A ", <.strong(".gov"), " website belongs to an official government organization in the United States.")
                    )
                  ),
                  <.div(^.cls := "usa-banner__guidance tablet:grid-col-6",
                    <.img(
                      ^.cls := "usa-banner__icon usa-media-block__img",
                      ^.src := "/img/icon-https.svg",
                      ^.role := "img",
                      ^.aria.hidden := true
                    ),
                    <.div(^.cls := "usa-media-block__body",
                      <.p(<.strong("Secure .gov websites use HTTPS"), <.br(), "A ", <.strong("lock"), " ( ", SvgTag("svg")(
                        ^.cls := "usa-icon",
                        ^.aria.hidden := true,
                        VdomAttr[Boolean]("focusable") := false,
                        ^.role := "img",
                        HtmlTag("use")(VdomAttr("href") := "/img/sprite.svg#lock")
                      ), " ) or ", <.strong("https"), "  means you’ve safely connected to the .gov website. Share sensitive information only on official, secure websites.")
                    )
                  )
                )
              )
            )
          ),
          <.header(
            ^.cls := "usa-header usa-header--basic",
            <.div(
              ^.cls := "usa-nav-container",
              <.div(
                ^.cls := "usa-navbar",
                <.div(
                  ^.cls := "usa-logo",
                  ^.id := "-logo",
                  <.em(
                    ^.cls := "usa-logo__text",
                    <.a(^.title := "Apollo", ^.href := "/", <.span("Apollo"))
                  )
                ),
                <.button(^.cls := "usa-menu-btn", <.span("Menu"))
              ),
              <.nav(
                ^.aria.label := "Primary navigation",
                ^.cls := "usa-nav",
                <.button(
                  ^.cls := "usa-nav__close",
                  <.img(^.role := "img", ^.alt := "Close", ^.src := "/img/usa-icons/close.svg")
                ),
                <.ul(
                  ^.cls := "usa-nav__primary usa-accordion",
                  <.li(
                    ^.cls := "usa-nav__primary-item",
                    <.a(^.href := "#", ^.cls := "usa-nav-link", <.span("About"))
                  )
                )
              )
            )
          ),
          <.div(^.cls := "usa-overlay"),
          <.div(^.cls := "flex-auto",
            <.main(^.id := "main-content",
              <.section(^.cls := "usa-section home-hero bg-primary text-white",
                <.div(^.cls := "grid-container",
                  <.div(
                    ^.cls := "grid-row",
                    <.div(^.cls := "grid-col-auto",
                      <.h1(^.cls := "mb-4", "Apollo is a type-safe framework for ambitious web applications.")
                    )
                  )
                )
              ),
              <.section(
                ^.cls := "usa-section padding-y-4",
                <.div(
                  ^.cls := "grid-container",
                  <.div(
                    ^.cls := "grid-row",
                    <.div(^.cls := "grid-col-fill",
                      <.h2(^.cls := "margin-top-0", "Why?"),
                      <.p(
                        ^.cls := "font-body-lg line-height-sans-3",
                        "Strong typing guarantees your code is free of silly mistakes; no more mixing up strings or numbers, forgetting what keys an object has, or worrying about typos in your method names. Apollo takes care of all this tedious book-keeping for you, letting you focus on the actual, more interesting problem your application is trying to solve."
                      )
                    )
                  )
                )
              ),
              <.section(
                ^.cls := "usa-section padding-y-4 bg-base-lightest",
                <.div(
                  ^.cls := "grid-container",
                  <.div(
                    ^.cls := "grid-row",
                    <.div(^.cls := "grid-col-fill",
                      <.h2(^.cls := "margin-top-0", "What's in the box"),
                      <.ul(^.cls := "usa-card-group",
                        <.li(^.cls := "usa-card usa-card--flag desktop:grid-col-6",
                          <.div(^.cls := "usa-card__container",
                            <.div(^.cls := "usa-card__header",
                              <.h2(^.cls := "usa-card__heading", "Scala.js")
                            ),
                            <.div(^.cls := "usa-card__media usa-card__media--inset",
                              <.div(^.cls := "usa-card__img",
                                <.img(^.src := "/img/scala-js-logo.svg")
                              )
                            ),
                            <.div(^.cls := "usa-card__body",
                              <.p("Scala.js compiles Scala source code to equivalent Javascript code. That lets you write Scala code that you can run in a web browser, or other environments (Chrome plugins, Node.js, etc.) where Javascript is supported.")
                            ),
                            <.div(^.cls := "usa-card__footer",
                              <.button(^.cls := "usa-button", "Learn more")
                            )
                          )
                        ),
                        <.li(^.cls := "usa-card usa-card--flag desktop:grid-col-6",
                          <.div(^.cls := "usa-card__container",
                            <.div(^.cls := "usa-card__header",
                              <.h2(^.cls := "usa-card__heading", "http4s")
                            ),
                            <.div(^.cls := "usa-card__media usa-card__media--inset",
                              <.div(^.cls := "usa-card__img",
                                <.img(^.src := "/img/http4s-logo-text-light.svg")
                              )
                            ),
                            <.div(^.cls := "usa-card__body",
                              <.p("Http4s is a minimal, idiomatic Scala interface for HTTP services. Http4s is Scala's answer to Ruby's Rack, Python's WSGI, Haskell's WAI, and Java's Servlets.")
                            ),
                            <.div(^.cls := "usa-card__footer",
                              <.button(^.cls := "usa-button", "Learn more")
                            )
                          )
                        )
                      )
                    )
                  )
                )
              ),
              <.footer(^.cls := "usa-footer site-footer padding-y-4",
                <.div(^.cls := "grid-container footer-content",
                  <.h2(^.cls := "margin-top-0", "Become part of the community"),
                  <.p("Apollo is an active open source community of government engineers."),
                  <.div(^.cls := "grid-row footer-contact-links",
                    <.div(^.cls := "tablet:grid-col",
                      <.div(^.cls := "usa-media-block",
                        <.div(^.cls := "usa-media-block__img circle-5 bg-accent-cool display-flex flex-row flex-align-center flex-justify-center text-white",
                          SvgTag("svg")(
                            ^.cls := "usa-icon",
                            ^.aria.hidden := true,
                            VdomAttr[Boolean]("focusable") := false,
                            ^.role := "img",
                            HtmlTag("use")(VdomAttr("href") := "/img/sprite.svg#help")
                          )
                        ),
                        <.div(^.cls := "usa-media-block__body",
                          <.h3(^.cls := "margin-y-0 font-body-sm text-normal", "Running into an issue?"),
                          <.a(^.href := "https://github.com/joshuakfarrar/apollo/issues/new", "Ask questions on GitHub")
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
      .build

  def run(args: List[String]): IO[ExitCode] =
    IO { Page().renderIntoDOM(document.querySelector("#application")) }.as(ExitCode.Success)
}