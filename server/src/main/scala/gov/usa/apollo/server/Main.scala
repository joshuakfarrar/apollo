package gov.usa.apollo.server

import cats.effect.{ExitCode, IO, IOApp}

object Main extends IOApp {
  def run(args: List[String]) =
    ServerServer.stream[IO].compile.drain.as(ExitCode.Success)
}
