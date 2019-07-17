package com.example.http4sexample

import cats.effect._
import cats.syntax.all._
import io.circe.Json
import org.http4s.client.Client
import org.http4s._
import org.http4s.circe._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import scala.concurrent.ExecutionContext.global
import org.http4s.dsl.io._

object JokesProxy extends IOApp {
  def proxy(client: Client[IO]) = HttpApp[IO] { _ =>
    client.expect[Json](uri"https://icanhazdadjoke.com/").flatMap(j => Ok(j))
  }

  def run(args: List[String]) = {
    for {
      client <- BlazeClientBuilder[IO](global).stream
      loggingApp = Logger.httpApp(true, true)(proxy(client))
      exitCode <- BlazeServerBuilder[IO]
        .bindHttp(10111, "0.0.0.0")
        .withHttpApp(loggingApp)
        .serve
        .drain
    } yield exitCode
  }.drain.compile.drain.as(ExitCode.Success)
}