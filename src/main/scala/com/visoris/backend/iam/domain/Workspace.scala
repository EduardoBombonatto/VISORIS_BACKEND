package com.visoris.backend.iam.domain

final case class Workspace(
  clinicId: Long,
  name: String,
  role: String
)
