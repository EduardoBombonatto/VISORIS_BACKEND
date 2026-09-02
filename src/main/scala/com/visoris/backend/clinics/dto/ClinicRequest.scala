package com.visoris.backend.clinics.dto

import io.circe.Decoder

final case class ClinicRequest(
  name: String,
  cnpj: Option[String] = None,
  phone: Option[String] = None,
  address: Option[String] = None
):
  def sanitized: ClinicRequest =
    ClinicRequest(
      name = name.trim,
      cnpj = cnpj.map(_.filter(_.isDigit)).filter(_.nonEmpty),
      phone = phone.map(_.filter(_.isDigit)).filter(_.nonEmpty),
      address = address.map(_.trim).filter(_.nonEmpty)
    )

object ClinicRequest:
  given Decoder[ClinicRequest] = Decoder.instance { cursor =>
    for
      name <- cursor.downField("name").as[String].orElse(Right(""))
      cnpj <- cursor.downField("cnpj").as[Option[String]]
      phone <- cursor.downField("phone").as[Option[String]]
      address <- cursor.downField("address").as[Option[String]]
    yield ClinicRequest(name, cnpj, phone, address)
  }

type CreateClinicRequest = ClinicRequest
val CreateClinicRequest: ClinicRequest.type = ClinicRequest
