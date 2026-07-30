package com.visoris.backend.iam.repository

import com.visoris.backend.iam.domain.RefreshToken
import doobie.ConnectionIO
import java.time.Instant

trait RefreshTokenRepository:
  def create(userId: Long, token: String, expiresAt: Instant, deviceInfo: Option[String], ipAddress: Option[String]): ConnectionIO[Unit]
  def findByToken(token: String): ConnectionIO[Option[RefreshToken]]
