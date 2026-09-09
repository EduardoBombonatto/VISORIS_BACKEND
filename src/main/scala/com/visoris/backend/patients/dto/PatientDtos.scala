package com.visoris.backend.patients.dto

import com.visoris.backend.patients.domain.PatientType
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.deriveEncoder
import java.time.{Instant, LocalDate}
import scala.util.Try

final case class PatientRequest(
  clientId: Long,
  name: String,
  patientType: Either[String, PatientType],
  birthDate: Option[LocalDate],
  biologicalDetails: Json
):
  def sanitizedName: String = name.trim

object PatientRequest:
  given Decoder[PatientRequest] = Decoder.instance { cursor =>
    for
      clientId <- cursor.downField("client_id").as[Long].orElse(cursor.downField("clientId").as[Long])
      name <- cursor.downField("name").as[String].orElse(Right(""))
      rawType <- cursor.downField("patient_type").as[String].orElse(cursor.downField("patientType").as[String]).orElse(Right(""))
      patientType = PatientType.fromString(rawType)
      rawBirthDate <- cursor.downField("birth_date").as[Option[String]].orElse(cursor.downField("birthDate").as[Option[String]])
      birthDate = rawBirthDate.flatMap(s => Try(LocalDate.parse(s.trim)).toOption)
      bioDetails <- cursor.downField("biological_details").as[Json].orElse(cursor.downField("biologicalDetails").as[Json]).orElse(Right(Json.obj()))
    yield PatientRequest(clientId, name, patientType, birthDate, bioDetails)
  }

final case class PatientResponse(
  id: String,
  clientId: Option[String],
  name: String,
  patientType: String,
  birthDate: Option[String],
  biologicalDetails: Json,
  createdAt: Instant
)

object PatientResponse:
  given Encoder[PatientResponse] = deriveEncoder

final case class CreatePatientResponse(
  patient: PatientResponse
)

object CreatePatientResponse:
  given Encoder[CreatePatientResponse] = deriveEncoder

final case class PatientListResponse(
  patients: List[PatientResponse]
)

object PatientListResponse:
  given Encoder[PatientListResponse] = deriveEncoder
