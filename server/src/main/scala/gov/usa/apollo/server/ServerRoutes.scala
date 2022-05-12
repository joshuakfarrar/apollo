package gov.usa.apollo.server

import cats.data.NonEmptyList
import cats.implicits._
import cats.effect.Sync
import org.http4s.CacheDirective.`no-cache`
import org.http4s.{HttpRoutes, StaticFile}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Cache-Control`

object ServerRoutes {

  def jokeRoutes[F[_]: Sync](J: Jokes[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "joke" =>
        for {
          joke <- J.get
          resp <- Ok(joke)
        } yield resp
    }
  }

  def helloWorldRoutes[F[_]: Sync](H: HelloWorld[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name =>
        for {
          greeting <- H.hello(HelloWorld.Name(name))
          resp <- Ok(greeting)
        } yield resp
    }
  }

  def staticFiles[F[_]: Sync](): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    val assetPaths = List("css", "fonts", "img", "js")
    val supportedStaticFiles = List("html", "js", "map", "css", "png", "ico", "ttf", "woff", "woff2", "svg")

    def staticFile(path: String) =
      StaticFile.fromResource[F](path)
        .map(_.putHeaders(`Cache-Control`(NonEmptyList.of(`no-cache`()))))
        .fold(NotFound())(_.pure[F])
        .flatten

    HttpRoutes.of[F] {
      case GET -> Root =>
        staticFile("./index.html")
      case req @ GET -> _ ~ ext if supportedStaticFiles.contains(ext) =>
        staticFile(req.pathInfo.toString)
      case req @ GET -> path /: _ ~ ext if assetPaths.contains(path) && supportedStaticFiles.contains(ext) =>
        staticFile(req.pathInfo.toRelative.toString)
    }
  }
}