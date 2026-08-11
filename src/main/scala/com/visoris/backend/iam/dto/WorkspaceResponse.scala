package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class ActiveWorkspace(
  clinicId: String,
  name: String,
  role: String
)

object ActiveWorkspace:
  given Encoder[ActiveWorkspace] = deriveEncoder

final case class WorkspaceResponse(
  activeWorkspace: ActiveWorkspace
)

object WorkspaceResponse:
  given Encoder[WorkspaceResponse] = deriveEncoder
