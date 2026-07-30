package com.visoris.backend.iam.domain

import java.time.Instant

final case class User(
  id: Long,
  email: String,
  passwordHash: String,
  fullName: String,
  professionalDocument: Option[String],
  createdAt: Instant
)
