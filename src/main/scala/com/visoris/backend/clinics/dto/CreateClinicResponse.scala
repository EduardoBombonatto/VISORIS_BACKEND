package com.visoris.backend.clinics.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class CreateClinicResponse(
  clinic: ClinicResponse
)

object CreateClinicResponse:
  given Encoder[CreateClinicResponse] = deriveEncoder
