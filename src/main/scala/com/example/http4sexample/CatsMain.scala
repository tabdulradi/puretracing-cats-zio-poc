package com.example.http4sexample

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import io.jaegertracing.Configuration
import io.opentracing.Span
import puretracing.catsEffect.http4s.Http4sTracingViaReaderT

object CatsMain extends IOApp {
  def run(args: List[String]) =
    for {
      jaegerTracer <- IO(Configuration.fromEnv("cats-puretacing-example").getTracer)
      tracingModule = puretracing.catsEffect.suspendModule[IO, Span](puretracing.opentracing.unsafeOpentracingBackendModule(jaegerTracer))
      res <- Http4sexampleServer.stream(
        Http4sTracingViaReaderT(tracingModule)
      ).compile.drain.as(ExitCode.Success)
    } yield res
}