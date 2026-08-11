package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class UserData(
  id: String,
  fullName: String,
  professionalDocument: Option[String]
)

object UserData:
  given Encoder[UserData] = deriveEncoder

final case class RegisterResponse(
  user: UserData,
  workspaces: List[String]
)

object RegisterResponse:
  given Encoder[RegisterResponse] = deriveEncoder
