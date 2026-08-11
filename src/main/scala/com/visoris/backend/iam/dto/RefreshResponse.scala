package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class RefreshResponse(
  expiresIn: Int
)

object RefreshResponse:
  given Encoder[RefreshResponse] = deriveEncoder
