package com.visoris.backend.clinics.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class ClinicListResponse(
  clinics: List[ClinicResponse]
)

object ClinicListResponse:
  given Encoder[ClinicListResponse] = deriveEncoder
