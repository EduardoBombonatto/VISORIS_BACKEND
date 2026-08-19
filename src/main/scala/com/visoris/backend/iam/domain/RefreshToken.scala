package com.visoris.backend.iam.domain

import java.time.Instant

final case class RefreshToken(
  id: Long,
  userId: Long,
  token: String,
  expiresAt: Instant,
  isRevoked: Boolean,
  revokedAt: Option[Instant],
  revokedReason: Option[String],
  deviceInfo: Option[String],
  ipAddress: Option[String],
  clinicId: Option[Long],
  role: Option[String],
  createdAt: Instant
)
