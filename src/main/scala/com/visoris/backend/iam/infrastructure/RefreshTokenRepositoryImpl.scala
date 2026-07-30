package com.visoris.backend.iam.infrastructure

import com.visoris.backend.iam.domain.RefreshToken
import com.visoris.backend.iam.repository.RefreshTokenRepository
import cats.syntax.functor.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import java.time.Instant

class RefreshTokenRepositoryImpl extends RefreshTokenRepository:

  def create(
    userId: Long,
    token: String,
    expiresAt: Instant,
    deviceInfo: Option[String],
    ipAddress: Option[String]
  ): ConnectionIO[Unit] =
    sql"""
      INSERT INTO refresh_tokens (user_id, token, expires_at, is_revoked, device_info, ip_address)
      VALUES ($userId, $token, $expiresAt, false, $deviceInfo, $ipAddress)
    """.update.run.void

  def findByToken(token: String): ConnectionIO[Option[RefreshToken]] =
    sql"""
      SELECT id, user_id, token, expires_at, is_revoked, device_info, ip_address, created_at
      FROM refresh_tokens WHERE token = $token
    """.query[RefreshToken].option
