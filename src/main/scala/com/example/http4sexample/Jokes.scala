package com.example.http4sexample

import cats.Applicative
import cats.effect.Sync
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import org.http4s._
import org.http4s.{EntityDecoder, EntityEncoder}
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.Method._
import org.http4s.circe._

trait Jokes[F[_]]{
  def get: F[Jokes.Joke]
}

object Jokes {
  def apply[F[_]](implicit ev: Jokes[F]): Jokes[F] = ev

  final case class Joke(joke: String) extends AnyVal
  object Joke {
    implicit val jokeDecoder: Decoder[Joke] = deriveDecoder[Joke]
    implicit def jokeEntityDecoder[F[_]: Sync]: EntityDecoder[F, Joke] =
      jsonOf
    implicit val jokeEncoder: Encoder[Joke] = deriveEncoder[Joke]
    implicit def jokeEntityEncoder[F[_]: Applicative]: EntityEncoder[F, Joke] =
      jsonEncoderOf
  }

  def impl[F[_]: Sync](C: Client[F])(implicit tracing: puretracing.Puretracing[F]): Jokes[F] = new Jokes[F]{
    val dsl = new Http4sClientDsl[F]{}
    import dsl._
    def get: F[Jokes.Joke] = 
      tracing.withSpan("get-joke") {
        C.expect[Joke](GET(uri"http://localhost:10111"))
      }
  }
}