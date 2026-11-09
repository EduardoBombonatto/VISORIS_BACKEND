package com.visoris.backend.reports.domain

import io.circe.Json
import java.time.Instant

final case class Report(
  id: Long,
  appointmentId: Long,
  userId: Long,
  attendanceLocationName: Option[String],
  patientSnapshot: Option[Json],
  clinicSnapshot: Option[Json],
  content: String,
  pdfUrl: Option[String],
  signedAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant
)

final case class ReportImage(
  id: Long,
  reportId: Long,
  imageUrl: String,
  caption: Option[String],
  displayOrder: Int,
  createdAt: Instant
)

final case class ReportWithImages(
  report: Report,
  images: List[ReportImage]
)
