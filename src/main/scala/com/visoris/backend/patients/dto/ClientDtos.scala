package com.visoris.backend.patients.dto

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import java.time.Instant

final case class ValidationError(field: String, message: String)

object ValidationError:
  given Encoder[ValidationError] = deriveEncoder
  given Decoder[ValidationError] = deriveDecoder

final case class ClientRequest(
  clinicId: Long,
  fullName: String,
  documentCpf: String,
  email: String,
  phone: String
):
  def sanitized: ClientRequest =
    ClientRequest(
      clinicId = clinicId,
      fullName = fullName.trim,
      documentCpf = documentCpf.filter(_.isDigit),
      email = email.trim,
      phone = phone.filter(_.isDigit)
    )

object ClientRequest:
  given Decoder[ClientRequest] = Decoder.instance { cursor =>
    for
      clinicId <- cursor.downField("clinic_id").as[Long].orElse(cursor.downField("clinicId").as[Long])
      fullName <- cursor.downField("full_name").as[String].orElse(cursor.downField("fullName").as[String]).orElse(Right(""))
      documentCpf <- cursor.downField("document_cpf").as[String].orElse(cursor.downField("documentCpf").as[String]).orElse(Right(""))
      email <- cursor.downField("email").as[String].orElse(Right(""))
      phone <- cursor.downField("phone").as[String].orElse(Right(""))
    yield ClientRequest(clinicId, fullName, documentCpf, email, phone)
  }

final case class CreateClientResponse(
  id: String
)

object CreateClientResponse:
  given Encoder[CreateClientResponse] = deriveEncoder

final case class ClientResponse(
  id: String,
  clinicId: String,
  fullName: String,
  documentCpf: Option[String],
  email: Option[String],
  phone: Option[String],
  createdAt: Instant
)

object ClientResponse:
  given Encoder[ClientResponse] = deriveEncoder

final case class ClientListResponse(
  clients: List[ClientResponse]
)

object ClientListResponse:
  given Encoder[ClientListResponse] = deriveEncoder
