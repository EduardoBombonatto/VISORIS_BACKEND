package com.visoris.backend.clinics.dto

import io.circe.Decoder

final case class CreateClinicRequest(
  name: String,
  cnpj: Option[String] = None,
  phone: Option[String] = None,
  address: Option[String] = None
)

object CreateClinicRequest:
  given Decoder[CreateClinicRequest] = Decoder.instance { cursor =>
    for
      name <- cursor.downField("name").as[String].orElse(Right(""))
      cnpj <- cursor.downField("cnpj").as[Option[String]]
      phone <- cursor.downField("phone").as[Option[String]]
      address <- cursor.downField("address").as[Option[String]]
    yield CreateClinicRequest(name, cnpj, phone, address)
  }
