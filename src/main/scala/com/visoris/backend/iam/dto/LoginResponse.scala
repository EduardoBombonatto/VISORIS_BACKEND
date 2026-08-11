package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class WorkspaceData(
  clinicId: String,
  name: String,
  role: String
)

object WorkspaceData:
  given Encoder[WorkspaceData] = deriveEncoder

final case class LoginResponse(
  user: UserData,
  workspaces: List[WorkspaceData]
)

object LoginResponse:
  given Encoder[LoginResponse] = deriveEncoder
