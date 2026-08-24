package com.visoris.backend.clinics.dto

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class ClinicResponse(
  id: String,
  name: String,
  cnpj: Option[String],
  phone: Option[String],
  address: Option[String]
)

object ClinicResponse:
  given Encoder[ClinicResponse] = deriveEncoder
