package com.visoris.backend.reports.dto

import com.visoris.backend.reports.domain.{ReportImage, ReportWithImages}
import io.circe.{ACursor, Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import java.time.Instant

final case class ValidationError(field: String, message: String)

object ValidationError:
  given Encoder[ValidationError] = deriveEncoder
  given Decoder[ValidationError] = deriveDecoder

final case class CreateReportRequest(
  appointmentId: Option[Long],
  content: Option[String]
)

object CreateReportRequest:
  private def decodeLongOpt(c: ACursor): Decoder.Result[Option[Long]] =
    c.as[Option[Long]].orElse(
      c.as[Option[String]].map(_.flatMap(_.trim.toLongOption))
    )

  given Decoder[CreateReportRequest] = Decoder.instance { cursor =>
    for
      apptSnake <- decodeLongOpt(cursor.downField("appointment_id"))
      apptCamel <- decodeLongOpt(cursor.downField("appointmentId"))
      content   <- cursor.downField("content").as[Option[String]]
    yield CreateReportRequest(apptSnake.orElse(apptCamel), content)
  }

  given Encoder[CreateReportRequest] = deriveEncoder

final case class UpdateReportRequest(
  content: Option[String]
)

object UpdateReportRequest:
  given Decoder[UpdateReportRequest] = Decoder.instance { cursor =>
    for content <- cursor.downField("content").as[Option[String]]
    yield UpdateReportRequest(content)
  }

  given Encoder[UpdateReportRequest] = deriveEncoder

final case class ReportImageRequest(
  imageUrl: String,
  caption: Option[String],
  displayOrder: Int
):
  def sanitized: ReportImageRequest =
    ReportImageRequest(
      imageUrl = imageUrl.trim,
      caption = caption.map(_.trim).filter(_.nonEmpty),
      displayOrder = displayOrder
    )

object ReportImageRequest:
  given Decoder[ReportImageRequest] = Decoder.instance { cursor =>
    for
      imageUrl <- cursor
        .downField("image_url")
        .as[String]
        .orElse(cursor.downField("imageUrl").as[String])
        .orElse(Right(""))
      caption <- cursor.downField("caption").as[Option[String]]
      orderOpt <- cursor
        .downField("display_order")
        .as[Option[Int]]
        .orElse(cursor.downField("displayOrder").as[Option[Int]])
    yield ReportImageRequest(imageUrl, caption, orderOpt.getOrElse(0))
  }

  given Encoder[ReportImageRequest] = deriveEncoder

final case class ReportImageResponse(
  id: String,
  reportId: String,
  imageUrl: String,
  caption: Option[String],
  displayOrder: Int,
  createdAt: Instant
)

object ReportImageResponse:
  given Encoder[ReportImageResponse] = deriveEncoder
  given Decoder[ReportImageResponse] = deriveDecoder

  def fromDomain(img: ReportImage): ReportImageResponse =
    ReportImageResponse(
      id = img.id.toString,
      reportId = img.reportId.toString,
      imageUrl = img.imageUrl,
      caption = img.caption,
      displayOrder = img.displayOrder,
      createdAt = img.createdAt
    )

final case class ReportResponse(
  id: String,
  appointmentId: String,
  userId: String,
  attendanceLocationName: Option[String],
  patientSnapshot: Option[Json],
  clinicSnapshot: Option[Json],
  content: String,
  pdfUrl: Option[String],
  signedAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant,
  images: List[ReportImageResponse]
)

object ReportResponse:
  given Encoder[ReportResponse] = deriveEncoder
  given Decoder[ReportResponse] = deriveDecoder

  def fromDomain(rwi: ReportWithImages): ReportResponse =
    val r = rwi.report
    ReportResponse(
      id = r.id.toString,
      appointmentId = r.appointmentId.toString,
      userId = r.userId.toString,
      attendanceLocationName = r.attendanceLocationName,
      patientSnapshot = r.patientSnapshot,
      clinicSnapshot = r.clinicSnapshot,
      content = r.content,
      pdfUrl = r.pdfUrl,
      signedAt = r.signedAt,
      createdAt = r.createdAt,
      updatedAt = r.updatedAt,
      images = rwi.images.map(ReportImageResponse.fromDomain)
    )
