/*
 * Copyright (C) 2018-2019 Tamer Abdulradi. All rights reserved.
 */

package com.example.http4sexample

import cats.effect.{ConcurrentEffect, Sync, Timer}
import cats.implicits._
import fs2.Stream
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import puretracing.http4s.Http4sTracingConfig
import scala.concurrent.ExecutionContext.global

object Http4sexampleServer {
  def stream[F[_]: ConcurrentEffect](tracing: Http4sTracingConfig[F])(implicit T: Timer[F]): Stream[F, Nothing] = {
    type TF[A] = tracing.TF[A]
    
    implicit val tracingEffectSyncInstance: Sync[TF] = tracing.syncInstanceForTracingEffect
    implicit val puretracingInstance: puretracing.Puretracing[TF] = tracing.puretracingInstance

    for {
      rawClient <- BlazeClientBuilder[F](global).stream
      tracingApp = tracing.transformServer("http4s-example", rawClient) { tracingClient =>
        (
          Http4sexampleRoutes.helloWorldRoutes(HelloWorld.impl[TF]) <+>
          Http4sexampleRoutes.jokeRoutes[TF](Jokes.impl[TF](tracingClient))
        ).orNotFound
      }
      loggingApp = Logger.httpApp(true, true)(tracingApp) // TODO: Tracing aware logger
      exitCode <- BlazeServerBuilder[F]
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(loggingApp)
        .serve
        .drain
    } yield exitCode
  }.drain
}