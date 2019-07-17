package com.example.http4sexample

import io.jaegertracing.Configuration
import io.opentracing.Span
import io.opentracing.noop.NoopSpan
import puretracing.zio
import puretracing.zio.http4s.ZIOHttp4sTracing
import scalaz.zio._
import scalaz.zio.console._
import scalaz.zio.interop.catz._
import puretracing.zio.Tracing
import scalaz.zio.blocking.Blocking
import scalaz.zio.clock.Clock
import scalaz.zio.random.Random
import scalaz.zio.system.System

object ZIOMain extends CatsApp {
  type AppEffect[A] = TaskR[Environment with Tracing[Span], A]

  override def run(args: List[String]): ZIO[ZIOMain.Environment, Nothing, Int] =
    for {
      jaegerTracer <- UIO(Configuration.fromEnv("zio-puretacing-example").getTracer)
      tracingModule = puretracing.zio.uioTracingModule(puretracing.opentracing.unsafeOpentracingBackendModule(jaegerTracer))
      server = ZIO.runtime[Environment with Tracing[Span]].flatMap { implicit runtime =>
        Http4sexampleServer.stream[AppEffect](
          ZIOHttp4sTracing(tracingModule)
        ).compile.drain
      }
      tracingPropagator <- puretracing.zio.Propagator.make(NoopSpan.INSTANCE: Span)
      program <-
        server.provideSome[Environment] { base =>
          new Clock with Console with System with Random with Blocking with Tracing[Span] {
            override def tracing: zio.Propagator[Span] = tracingPropagator
            override val clock: Clock.Service[Any] = base.clock
            override val console: Console.Service[Any] = base.console
            override val system: System.Service[Any] = base.system
            override val random: Random.Service[Any] = base.random
            override val blocking: Blocking.Service[Any] = base.blocking
          }
        }.foldM(
          err => putStrLn(s"Execution failed with: $err") *> ZIO.succeed(1),
          _ => ZIO.succeed(0)
        )
    } yield program
}