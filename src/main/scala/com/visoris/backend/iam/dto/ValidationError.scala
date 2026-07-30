package com.visoris.backend.iam.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class ValidationError(
  field: String,
  message: String
)

object ValidationError:
  given Encoder[ValidationError] = deriveEncoder
