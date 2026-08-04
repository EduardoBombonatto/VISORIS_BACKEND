package com.visoris.backend.shared.auth

import cats.effect.Sync
import java.security.SecureRandom

object OpaqueTokenGenerator:
  private val random = new SecureRandom()

  def generate[F[_]: Sync]: F[String] =
    Sync[F].delay {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      bytes.map(b => f"$b%02x").mkString
    }
