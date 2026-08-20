package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class LoginResponse(
  user: UserData
)

object LoginResponse:
  given Encoder[LoginResponse] = deriveEncoder
