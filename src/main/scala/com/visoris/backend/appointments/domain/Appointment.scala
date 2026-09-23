package com.visoris.backend.appointments.domain

import com.visoris.backend.patients.domain.PatientType
import java.time.Instant

final case class Appointment(
  id: Long,
  userId: Long,
  clinicId: Long,
  patientId: Long,
  scheduledAt: Instant,
  procedureName: String,
  examStatus: ExamStatus,
  reportStatus: ReportStatus,
  paymentStatus: PaymentStatus,
  price: Option[BigDecimal],
  createdAt: Instant,
  updatedAt: Instant
)

final case class AppointmentWithDetails(
  id: Long,
  userId: Long,
  clinicId: Long,
  patientId: Long,
  scheduledAt: Instant,
  procedureName: String,
  examStatus: ExamStatus,
  reportStatus: ReportStatus,
  paymentStatus: PaymentStatus,
  price: Option[BigDecimal],
  createdAt: Instant,
  updatedAt: Instant,
  patientName: String,
  patientType: PatientType,
  clientName: String,
  clinicName: String
)
