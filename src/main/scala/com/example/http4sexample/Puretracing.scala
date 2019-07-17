/*
 * Copyright (C) 2018-2019 Tamer Abdulradi. All rights reserved.
 */

package puretracing

import cats.effect.Sync
import cats.~>
import org.http4s.client.Client
import cats.arrow.FunctionK
import cats.data.Kleisli

// Implemented for ReaderT[IO, _] and ZIO[Tracing, _, _]
// Doesn't handle Bracket related logic
trait TracingContext[F[_], S] {
  def currentSpan: F[S]
  def useSpan[A](use: F[A])(span: S): F[A]
}
// Implemented by cats.effect.Bracket and ZIO.bracket
trait SpanLifeCycle[F[_], S] {
  /** Creates a child span, guaranteed to be properly terminated */
  def createAndFinish[A](
    createSpan: F[S],
    useSpan: S => F[A],
    handleCompleted: (S, A) => F[Unit],
    handleCanceled: S => F[Unit],
    handleError: S => F[Unit]
  ): F[A]
}

// Should be implemented for OpenTracing, OpenCensus, and the upcoming OpenTelemetry
trait SpanExporter[S] {
  def toText(span: S): Map[String, String]
}
trait SpanFactory[F[_], S] { outer =>
  def root(operationName: String): F[S]
  def childOfLocal(operationName: String, parent: S): F[S]
  def childOfTextRemoteOrElseRoot(operationName: String, remote: Iterable[(String, String)]): F[S]

  final def mapK[G[_]](transform: F ~> G): SpanFactory[G, S] = new SpanFactory[G, S] {
    override def root(operationName: String): G[S] = transform(outer.root(operationName))
    override def childOfLocal(operationName: String, parent: S): G[S] = transform(outer.childOfLocal(operationName, parent))
    override def childOfTextRemoteOrElseRoot(operationName: String, remote: Iterable[(String, String)]): G[S] = transform(outer.childOfTextRemoteOrElseRoot(operationName, remote))
  }
}
trait SpanFinaliser[F[_], S] { outer =>
  def default(span: S): F[Unit]
  def fromHttpCode(span: S, statusCode: Int): F[Unit]
  def error(span: S): F[Unit]
  def cancelled(span: S): F[Unit]

  final def mapK[G[_]](transform: F ~> G): SpanFinaliser[G, S] = new SpanFinaliser[G, S] {
    override def default(span: S): G[Unit] = transform(outer.default(span))
    override def fromHttpCode(span: S, statusCode: Int): G[Unit] = transform(outer.fromHttpCode(span, statusCode))
    override def error(span: S): G[Unit] = transform(outer.error(span))
    override def cancelled(span: S): G[Unit] = transform(outer.cancelled(span))
  }
}

/**
  * Highlevel, exposed to users
  * Extended by Opentracing and Opencensus to provide extra operations exposed to users
  *
  * No Span typeparam should be visible in signatures
  **/
trait Puretracing[F[_]] {
  /** Creates a child span, guaranteed to be properly terminated */
  def withSpan[A](operationName: String)(use: F[A]): F[A]
}

// Facades to guide the implementation of a new modules
trait TracingBackendModule[F[_], Span] { outer => // OpenTracing and OpenCensus
  def spanExporter: SpanExporter[Span]
  def spanFactory: SpanFactory[F, Span]
  def spanFinalizer: SpanFinaliser[F, Span]

  final def mapK[G[_]](transform: F ~> G): TracingBackendModule[G, Span] = new TracingBackendModule[G, Span] {
    override def spanExporter: SpanExporter[Span] = outer.spanExporter
    override def spanFactory: SpanFactory[G, Span] = outer.spanFactory.mapK(transform)
    override def spanFinalizer: SpanFinaliser[G, Span] = outer.spanFinalizer.mapK(transform)
  }
}

/**
  * Convenience version that allows implementors to do side effect (i.e call underlying Java implementation)
  * However, implementations are not allowed to block or throw exceptions.
  *
  * Puretracing takes care to suspend all side-effects before calling any methods here
  */
trait UnsafeTracingBackendModule[Span] extends TracingBackendModule[Function0, Span]

trait TracingConfig[F[_]] {
  type TF[_] // Tracing Effect
  type Span
  def puretracingInstance: Puretracing[TF]
}

object catsEffect {
  import cats.Applicative
  import cats.data.ReaderT
  import cats.syntax.all._
  import cats.effect.{Bracket, ExitCase}

  def bracketLifeCycle[F[_], S, E](implicit F: Bracket[F, E]): SpanLifeCycle[F, S] = new SpanLifeCycle[F, S]{
    override def createAndFinish[A](createSpan: F[S], useSpan: S => F[A], handleCompleted: (S, A) => F[Unit], handleCanceled: S => F[Unit], handleError: S => F[Unit]): F[A] =
      F.bracketCase(createSpan)(span => useSpan(span).flatTap(handleCompleted(span, _))) { (span, exitCase) =>
        exitCase match {
          case ExitCase.Completed => F.unit
          case ExitCase.Canceled => handleCanceled(span)
          case ExitCase.Error(_) => handleError(span)
        }
      }
  }

  def readerTTracingContext[F[_]: Applicative, S]: TracingContext[ReaderT[F, S, ?], S] =
    new TracingContext[ReaderT[F, S, ?], S] {
      override def currentSpan: ReaderT[F, S, S] = ReaderT.ask[F, S]
      override def useSpan[A](use: ReaderT[F, S, A])(span: S): ReaderT[F, S, A] = ReaderT.local[F, A, S](_ => span)(use)
    }

  def suspendModule[F[_], S](unsafe: UnsafeTracingBackendModule[S])(implicit F: Sync[F]): TracingBackendModule[F, S] =
    unsafe.mapK(new ~>[Function0, F] {
      override def apply[A](getA: () => A): F[A] = F.delay(getA())
    })

  object http4s {
    import org.http4s._, syntax._
    import org.http4s.client.Client
    import cats.effect.Concurrent
    import cats.effect.Resource

    object Http4sTracingViaReaderT {
      def apply[F[_]: Concurrent, S](tracingBackendModule: TracingBackendModule[F, S]): Http4sTracingViaReaderT[F] = new Http4sTracingViaReaderT[F] {
        override type Span = S
        override def tracer: TracingBackendModule[F, S] = tracingBackendModule
        override def syncInstanceForTracingEffect(implicit F: Sync[F]): Sync[TF] = Sync.catsKleisliSync
      }
    }
    abstract class Http4sTracingViaReaderT[F[_]: Concurrent] extends puretracing.http4s.Http4sTracingConfig[F] {
      def tracer: TracingBackendModule[F, Span]

      lazy val tracerTF = tracer.mapK(new FunctionK[F, TF] {
        override def apply[A](fa: F[A]): TF[A] = Kleisli.liftF(fa)
      })

      val F = Sync[F]

      override type TF[A] = ReaderT[F, Span, A]

      override val puretracingInstance: Puretracing[TF] = new Puretracing[TF] {
        def withSpan[A](operationName: String)(use: TF[A]): TF[A] = 
          bracketLifeCycle[TF, Span, Throwable].createAndFinish(
            tracingContext.currentSpan.flatMap(tracerTF.spanFactory.childOfLocal(operationName, _)), 
            tracingContext.useSpan(use), 
            (span, _) => tracerTF.spanFinalizer.default(span),
            tracerTF.spanFinalizer.cancelled,
            tracerTF.spanFinalizer.error
          )
      }
      
      def tracingContext: TracingContext[TF, Span] = readerTTracingContext[F, Span]

      def transformClient(client: Client[F]): Client[TF] = Client[TF] { originalRequest =>
        for {
          span <- Resource.liftF(tracingContext.currentSpan)
          tracingHeaders = Headers(tracer.spanExporter.toText(span).map { case (k, v) => Header(k,v) }.toList)
          newReq = originalRequest.withHeaders(originalRequest.headers ++ tracingHeaders)
          f = ReaderT.applyK[F, Span](span)
          g = ReaderT.liftK[F, Span]
          result <- client.run(newReq.mapK(f)).mapK(g).map(_.mapK(g))
        } yield result
      }

      override def transformServer(operationName: String, client: Client[F])(mkApp: Client[TF] => HttpApp[TF]): HttpApp[F] = {
        val tracingClient = transformClient(client)
        val app = mkApp(tracingClient)
        HttpApp { req =>
          val remote = req.headers.toList.map(h => h.name.value -> h.value)

          bracketLifeCycle[F, Span, Throwable].createAndFinish(
            tracer.spanFactory.childOfTextRemoteOrElseRoot(operationName, remote),
            span => {
              val f: TF ~> F = ReaderT.applyK(span)
              new KleisliHttpAppOps(app).translate(f)(ReaderT.liftK).run(req)
            },
            (span, response) => tracer.spanFinalizer.fromHttpCode(span, response.status.code),
            tracer.spanFinalizer.cancelled,
            tracer.spanFinalizer.error
          )
        }
      }
    }
  }
}

object opencensus {
  import cats.effect.Sync
  import io.opencensus.trace.{Span, EndSpanOptions, Status}

  trait HttpCodeMapper extends (Int => Status)
  object HttpCodeMapper {
    val default: HttpCodeMapper = {
      case x if Range(200, 300).contains(x) => Status.OK
      case 401 => Status.PERMISSION_DENIED
      case 404 => Status.NOT_FOUND
      case x if Range(400, 500).contains(x) => Status.INVALID_ARGUMENT
      case x if Range(500, 600).contains(x) => Status.INTERNAL
      case _ => Status.UNKNOWN
    }
  }

  class SpanFinaliser[F[_]](httpCodeMapper: HttpCodeMapper = HttpCodeMapper.default)(implicit F: Sync[F]) extends puretracing.SpanFinaliser[F, Span] {
    def end(span: Span, status: Status): F[Unit] = F.delay(span.end(EndSpanOptions.builder().setStatus(status).build()))
    override def default(span: Span): F[Unit] = F.delay(span.end())
    override def error(span: Span): F[Unit] = end(span, Status.INTERNAL)
    override def cancelled(span: Span): F[Unit] = end(span, Status.CANCELLED)
    override def fromHttpCode(span: Span, statusCode: Int): F[Unit] = end(span, httpCodeMapper(statusCode))
  }
}

object opentracing {
  import io.opentracing.Tracer
  import io.opentracing.Span
  import io.opentracing.propagation.{Format, TextMapExtractAdapter, TextMapInjectAdapter}
  import scala.collection.JavaConverters._

  type TracingContext[F[_]] = puretracing.TracingContext[F, Span]
  type TracingBackendModule[F[_]] = puretracing.TracingBackendModule[F, Span]

  def unsafeOpentracingBackendModule(tracer: Tracer): UnsafeTracingBackendModule[Span] =
    new UnsafeTracingBackendModule[Span] {
      override def spanExporter: SpanExporter[Span] = span => {
        val carrier = new java.util.HashMap[String, String]() // Warning, mutability ahead!
        tracer.inject(span.context, Format.Builtin.HTTP_HEADERS, new TextMapInjectAdapter(carrier))
        carrier.asScala.toMap // back to immutability
      }

      override def spanFactory: SpanFactory[Function0, Span] = new SpanFactory[Function0, Span] {
        private def startSpan(operationName: String)(build: Tracer.SpanBuilder => Tracer.SpanBuilder): Span =
          build(tracer.buildSpan(operationName)).start()

        override def root(operationName: String): () => Span = () => startSpan(operationName)(identity)
        override def childOfLocal(operationName: String, parent: Span): Function0[Span] = () => startSpan(operationName)(_.asChildOf(parent))
        override def childOfTextRemoteOrElseRoot(operationName: String, remote: Iterable[(String, String)]): () => Span =
          () => startSpan(operationName) { builder =>
            val remoteCtx = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapExtractAdapter(remote.toMap.asJava))
            builder.asChildOf(remoteCtx)
          }
      }
      override def spanFinalizer: SpanFinaliser[Function0, Span] = new SpanFinaliser[Function0, Span] {
        def finish(span: Span): () => Unit = () => span.finish()
        override def default(span: Span): () => Unit = finish(span)
        override def error(span: Span): () => Unit = finish(span)
        override def cancelled(span: Span): () => Unit = finish(span)
        override def fromHttpCode(span: Span, statusCode: Int): () => Unit = finish(span)
      }
    }
}

object http4s {
  import org.http4s._
  import org.http4s.client.Client

  trait Http4sTracingConfig[F[_]] extends TracingConfig[F] {
    implicit def syncInstanceForTracingEffect(implicit F: Sync[F]): Sync[TF]
    def transformServer(operationName: String, rawClient: Client[F])(app: Client[TF] => HttpApp[TF]): HttpApp[F]
  }
}

object zio {
  import scalaz.zio._

  trait Tracing[Span] {
    def tracing: Propagator[Span]
  }

  class Propagator[Span](ref: FiberRef[Span]) {
    def get: UIO[Span] = ref.get
    def use[R, E, A](span: Span, use: ZIO[R, E, A]): ZIO[R, E, A] = ref.locally(span)(use)
  }
  object Propagator {
    def make[Span](initial: Span): UIO[Propagator[Span]] =
      FiberRef.make[Span](initial).map(new Propagator(_))
  }

  def uioTracingModule[S](module: UnsafeTracingBackendModule[S]): TracingBackendModule[UIO, S] =
    module.mapK(new ~>[Function0, UIO] {
      override def apply[A](getA: () => A): UIO[A] = UIO.effectTotal(getA())
    })

  def currentSpan[R <: Tracing[S], E, S]: ZIO[R, E, S] = 
    ZIO.accessM[R](_.tracing.get)

  def useSpan[R <: Tracing[S], E, S, A](use: ZIO[R, E, A])(span: S): ZIO[R, E, A] = 
    ZIO.accessM[R](_.tracing.use(span, use))

  object http4s {
    import org.http4s._
    import scalaz.zio.interop.catz._
    import cats.effect.Resource

    case class ZIOHttp4sTracing[R <: Tracing[S] : Runtime, E, S](tracer: TracingBackendModule[UIO, S]) extends puretracing.http4s.Http4sTracingConfig[TaskR[R, ?]] {
      override type TF[A] = TaskR[R, A]
      override type Span = S

      override def syncInstanceForTracingEffect(implicit F: Sync[TF]): Sync[TF] = F

      override val puretracingInstance: Puretracing[TF] = new Puretracing[TF] {
        def withSpan[A](operationName: String)(use: TF[A]): TF[A] = 
          ZIO.bracketExit[R, Throwable, S, A](
            currentSpan[R, Throwable, S].flatMap(tracer.spanFactory.childOfLocal(operationName, _)), {
              case (span, Exit.Success(_)) => tracer.spanFinalizer.default(span)
              case (span, Exit.Failure(Exit.Cause.Interrupt)) => tracer.spanFinalizer.cancelled(span)
              case (span, Exit.Failure(_)) => tracer.spanFinalizer.error(span) // We could also use cause.defects or cause.failures if the backend supports
            }, span => ZIO.accessM[R](_.tracing.use(span, use))
          )
      }

      def transformClientPerRequest(client: Client[TF], tracing: Propagator[Span]): Client[TF] =
        Client[TF] { originalRequest =>
          val newRequest: TF[originalRequest.Self] =
            for {
              span <- tracing.get
              tracingHeaders = Headers(tracer.spanExporter.toText(span).map { case (k, v) => Header(k,v) }.toList)
            } yield originalRequest.withHeaders(originalRequest.headers ++ tracingHeaders)

          Resource.liftF(newRequest).flatMap(client.run)
        }

      override def transformServer(operationName: String, rawClient: Client[TF])(app: Client[TF] => HttpApp[TF]): HttpApp[TF] =
        HttpApp[TF] { req =>
          val remote = req.headers.toList.map(h => h.name.value -> h.value)
          
          ZIO.bracketExit[R, Throwable, S, Response[TF]](
            tracer.spanFactory.childOfTextRemoteOrElseRoot(operationName, remote), {
              case (span, Exit.Success(response)) => tracer.spanFinalizer.fromHttpCode(span, response.status.code)
              case (span, Exit.Failure(Exit.Cause.Interrupt)) => tracer.spanFinalizer.cancelled(span)
              case (span, Exit.Failure(_)) => tracer.spanFinalizer.error(span) // We could also use cause.defects or cause.failures if the backend supports
            }, span => ZIO.accessM[R](env => 
              env.tracing.use(span, app(transformClientPerRequest(rawClient, env.tracing))(req))
            )
          )
        }
    }
  }
}