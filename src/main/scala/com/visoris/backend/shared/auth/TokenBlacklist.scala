package com.visoris.backend.shared.auth

import cats.effect.{Ref, Async}
import cats.syntax.all.*
import java.time.Instant

trait TokenBlacklist[F[_]]:
  def blacklist(tokenHash: String): F[Unit]
  def isBlacklisted(tokenHash: String): F[Boolean]

object TokenBlacklist:
  def make[F[_]: Async]: F[TokenBlacklist[F]] =
    Ref.of[F, Map[String, Instant]](Map.empty).map { store =>
      new TokenBlacklist[F]:
        def blacklist(tokenHash: String): F[Unit] =
          Async[F].delay(Instant.now).flatMap { now =>
            store.update(_ + (tokenHash -> now))
          }

        def isBlacklisted(tokenHash: String): F[Boolean] =
          store.get.map(_.contains(tokenHash))
    }
