package com.visoris.backend.appointments.dto

import com.visoris.backend.appointments.domain.{Appointment, AppointmentWithDetails, ExamStatus, PaymentStatus, ReportStatus}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import java.time.Instant
import scala.util.Try

final case class ValidationError(field: String, message: String)
object ValidationError:
  given Encoder[ValidationError] = deriveEncoder
  given Decoder[ValidationError] = deriveDecoder

final case class CreateAppointmentRequest(
  clinicId: Long,
  patientId: Long,
  scheduledAt: Either[String, Instant],
  procedureName: String,
  price: Option[BigDecimal],
  paymentStatus: Option[PaymentStatus] = None
):
  def sanitizedProcedureName: String = procedureName.trim

object CreateAppointmentRequest:
  given Decoder[CreateAppointmentRequest] = Decoder.instance { cursor =>
    for
      clinicId <- cursor.downField("clinic_id").as[Long].orElse(cursor.downField("clinicId").as[Long])
      patientId <- cursor.downField("patient_id").as[Long].orElse(cursor.downField("patientId").as[Long])
      rawScheduledAt <- cursor.downField("scheduled_at").as[String].orElse(cursor.downField("scheduledAt").as[String]).orElse(Right(""))
      scheduledAt = Try(Instant.parse(rawScheduledAt.trim)).toEither.left.map(_ => s"Data e hora inválida: '$rawScheduledAt'. Formato esperado: ISO 8601.")
      procedureName <- cursor.downField("procedure_name").as[String].orElse(cursor.downField("procedureName").as[String]).orElse(Right(""))
      price <- cursor.downField("price").as[Option[BigDecimal]]
      paymentStatus <- cursor.downField("payment_status").as[Option[PaymentStatus]].orElse(cursor.downField("paymentStatus").as[Option[PaymentStatus]])
    yield CreateAppointmentRequest(clinicId, patientId, scheduledAt, procedureName, price, paymentStatus)
  }

final case class AppointmentResponse(
  id: String,
  userId: String,
  clinicId: String,
  patientId: String,
  scheduledAt: Instant,
  procedureName: String,
  examStatus: String,
  reportStatus: String,
  paymentStatus: String,
  price: Option[BigDecimal],
  createdAt: Instant,
  updatedAt: Instant,
  patientName: String,
  patientType: String,
  clientName: String,
  clinicName: String
)

object AppointmentResponse:
  given Encoder[AppointmentResponse] = deriveEncoder

  def fromDomain(a: Appointment, patientName: String, patientType: String, clientName: String, clinicName: String): AppointmentResponse =
    AppointmentResponse(
      id = a.id.toString,
      userId = a.userId.toString,
      clinicId = a.clinicId.toString,
      patientId = a.patientId.toString,
      scheduledAt = a.scheduledAt,
      procedureName = a.procedureName,
      examStatus = a.examStatus.toString,
      reportStatus = a.reportStatus.toString,
      paymentStatus = a.paymentStatus.toString,
      price = a.price,
      createdAt = a.createdAt,
      updatedAt = a.updatedAt,
      patientName = patientName,
      patientType = patientType,
      clientName = clientName,
      clinicName = clinicName
    )

  def fromDetails(d: AppointmentWithDetails): AppointmentResponse =
    AppointmentResponse(
      id = d.id.toString,
      userId = d.userId.toString,
      clinicId = d.clinicId.toString,
      patientId = d.patientId.toString,
      scheduledAt = d.scheduledAt,
      procedureName = d.procedureName,
      examStatus = d.examStatus.toString,
      reportStatus = d.reportStatus.toString,
      paymentStatus = d.paymentStatus.toString,
      price = d.price,
      createdAt = d.createdAt,
      updatedAt = d.updatedAt,
      patientName = d.patientName,
      patientType = d.patientType.toString,
      clientName = d.clientName,
      clinicName = d.clinicName
    )

final case class CreateAppointmentResponse(
  appointment: AppointmentResponse
)

object CreateAppointmentResponse:
  given Encoder[CreateAppointmentResponse] = deriveEncoder

final case class AppointmentListResponse(
  appointments: List[AppointmentResponse]
)

object AppointmentListResponse:
  given Encoder[AppointmentListResponse] = deriveEncoder

final case class UpdateAppointmentStatusRequest(
  examStatus: Option[ExamStatus],
  reportStatus: Option[ReportStatus],
  paymentStatus: Option[PaymentStatus]
)

object UpdateAppointmentStatusRequest:
  given Decoder[UpdateAppointmentStatusRequest] = Decoder.instance { cursor =>
    for
      examStatus <- cursor.downField("exam_status").as[Option[ExamStatus]].orElse(cursor.downField("examStatus").as[Option[ExamStatus]])
      reportStatus <- cursor.downField("report_status").as[Option[ReportStatus]].orElse(cursor.downField("reportStatus").as[Option[ReportStatus]])
      paymentStatus <- cursor.downField("payment_status").as[Option[PaymentStatus]].orElse(cursor.downField("paymentStatus").as[Option[PaymentStatus]])
    yield UpdateAppointmentStatusRequest(examStatus, reportStatus, paymentStatus)
  }
