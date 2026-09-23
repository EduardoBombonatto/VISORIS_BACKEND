package com.visoris.backend.templates.domain

import java.time.Instant

final case class Template(
  id: Long,
  userId: Long,
  title: String,
  content: String,
  createdAt: Instant,
  updatedAt: Instant
)

final case class TemplateSummary(
  id: Long,
  userId: Long,
  title: String,
  createdAt: Instant
)
