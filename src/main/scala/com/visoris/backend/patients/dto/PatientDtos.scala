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
  biologicalDetails: Json,
  invalidBirthDate: Boolean = false
):
  def sanitizedName: String = name.trim

object PatientRequest:
  private def parseBirthDate(rawOpt: Option[String]): (Option[LocalDate], Boolean) =
    rawOpt.map(_.trim).filter(_.nonEmpty) match
      case None => (None, false)
      case Some(s) =>
        Try(LocalDate.parse(s)).toOption match
          case Some(d) => (Some(d), false)
          case None    => (None, true)

  given Decoder[PatientRequest] = Decoder.instance { cursor =>
    for
      clientId <- cursor.downField("client_id").as[Long].orElse(cursor.downField("clientId").as[Long])
      name <- cursor.downField("name").as[String].orElse(Right(""))
      rawType <- cursor.downField("patient_type").as[String].orElse(cursor.downField("patientType").as[String]).orElse(Right(""))
      patientType = PatientType.fromString(rawType)
      rawBirthDate <- cursor.downField("birth_date").as[Option[String]].orElse(cursor.downField("birthDate").as[Option[String]])
      (birthDate, invalidBirthDate) = parseBirthDate(rawBirthDate)
      bioDetails <- cursor.downField("biological_details").as[Json].orElse(cursor.downField("biologicalDetails").as[Json]).orElse(Right(Json.obj()))
    yield PatientRequest(clientId, name, patientType, birthDate, bioDetails, invalidBirthDate)
  }

final case class UpdatePatientRequest(
  name: String,
  patientType: Either[String, PatientType],
  birthDate: Option[LocalDate],
  biologicalDetails: Json,
  invalidBirthDate: Boolean = false
):
  def sanitizedName: String = name.trim

object UpdatePatientRequest:
  private def parseBirthDate(rawOpt: Option[String]): (Option[LocalDate], Boolean) =
    rawOpt.map(_.trim).filter(_.nonEmpty) match
      case None => (None, false)
      case Some(s) =>
        Try(LocalDate.parse(s)).toOption match
          case Some(d) => (Some(d), false)
          case None    => (None, true)

  given Decoder[UpdatePatientRequest] = Decoder.instance { cursor =>
    for
      name <- cursor.downField("name").as[String].orElse(Right(""))
      rawType <- cursor.downField("patient_type").as[String].orElse(cursor.downField("patientType").as[String]).orElse(Right(""))
      patientType = PatientType.fromString(rawType)
      rawBirthDate <- cursor.downField("birth_date").as[Option[String]].orElse(cursor.downField("birthDate").as[Option[String]])
      (birthDate, invalidBirthDate) = parseBirthDate(rawBirthDate)
      bioDetails <- cursor.downField("biological_details").as[Json].orElse(cursor.downField("biologicalDetails").as[Json]).orElse(Right(Json.obj()))
    yield UpdatePatientRequest(name, patientType, birthDate, bioDetails, invalidBirthDate)
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
