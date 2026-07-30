package com.visoris.backend.shared.auth

import cats.effect.Sync
import org.mindrot.jbcrypt.BCrypt

object PasswordHasher:
  private val cost = 12

  def hash[F[_]: Sync](password: String): F[String] =
    Sync[F].delay(BCrypt.hashpw(password, BCrypt.gensalt(cost)))

  def verify[F[_]: Sync](password: String, hash: String): F[Boolean] =
    Sync[F].delay(BCrypt.checkpw(password, hash))
