package com.visoris.backend.shared.auth

import cats.effect.Sync
import org.mindrot.jbcrypt.BCrypt
import scala.util.Try

object PasswordHasher:
  private val cost = 12
  // Ephemeral cost-12 hash computed once on the blocking pool for missing-user timing mitigation
  private lazy val dummyHash: String =
    BCrypt.hashpw(java.util.UUID.randomUUID().toString, BCrypt.gensalt(cost))

  def hash[F[_]: Sync](password: String): F[String] =
    Sync[F].blocking(BCrypt.hashpw(password, BCrypt.gensalt(cost)))

  def verify[F[_]: Sync](password: String, hash: String): F[Boolean] =
    Sync[F].blocking(Try(BCrypt.checkpw(password, hash)).getOrElse(false))

  def verifyDummy[F[_]: Sync](password: String): F[Boolean] =
    Sync[F].blocking(Try(BCrypt.checkpw(password, dummyHash)).getOrElse(false))
