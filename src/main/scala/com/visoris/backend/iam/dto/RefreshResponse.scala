package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class RefreshResponse(
  accessToken: String,
  expiresIn: Int
)

object RefreshResponse:
  given Encoder[RefreshResponse] = deriveEncoder
