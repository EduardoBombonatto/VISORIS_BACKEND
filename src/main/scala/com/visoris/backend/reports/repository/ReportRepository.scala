package com.visoris.backend.reports.repository

import com.visoris.backend.patients.repository.DoobieInstances.given
import com.visoris.backend.reports.domain.Report
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor
import io.circe.Json

trait ReportRepository[F[_]]:
  def insert(
    appointmentId: Long,
    userId: Long,
    attendanceLocationName: Option[String],
    patientSnapshot: Option[Json],
    clinicSnapshot: Option[Json],
    content: String
  ): ConnectionIO[Report]

  def findByIdAndUserId(id: Long, userId: Long): ConnectionIO[Option[Report]]

  def findByAppointmentIdAndUserId(appointmentId: Long, userId: Long): ConnectionIO[Option[Report]]

  def updateContent(id: Long, userId: Long, content: String): ConnectionIO[Option[Report]]

  def sign(id: Long, userId: Long): ConnectionIO[Option[Report]]

object ReportRepository:
  def make[F[_]](transactor: Transactor[F]): ReportRepository[F] = new ReportRepository[F]:

    def insert(
      appointmentId: Long,
      userId: Long,
      attendanceLocationName: Option[String],
      patientSnapshot: Option[Json],
      clinicSnapshot: Option[Json],
      content: String
    ): ConnectionIO[Report] =
      sql"""INSERT INTO reports (
              appointment_id, user_id, attendance_location_name,
              patient_snapshot, clinic_snapshot, content
            ) VALUES (
              $appointmentId, $userId, $attendanceLocationName,
              $patientSnapshot, $clinicSnapshot, $content
            )
            RETURNING id, appointment_id, user_id, attendance_location_name,
                      patient_snapshot, clinic_snapshot, content, pdf_url,
                      signed_at, created_at, updated_at"""
        .query[Report]
        .unique

    def findByIdAndUserId(id: Long, userId: Long): ConnectionIO[Option[Report]] =
      sql"""SELECT id, appointment_id, user_id, attendance_location_name,
                   patient_snapshot, clinic_snapshot, content, pdf_url,
                   signed_at, created_at, updated_at
            FROM reports
            WHERE id = $id AND user_id = $userId"""
        .query[Report]
        .option

    def findByAppointmentIdAndUserId(appointmentId: Long, userId: Long): ConnectionIO[Option[Report]] =
      sql"""SELECT id, appointment_id, user_id, attendance_location_name,
                   patient_snapshot, clinic_snapshot, content, pdf_url,
                   signed_at, created_at, updated_at
            FROM reports
            WHERE appointment_id = $appointmentId AND user_id = $userId"""
        .query[Report]
        .option

    def updateContent(id: Long, userId: Long, content: String): ConnectionIO[Option[Report]] =
      sql"""UPDATE reports
            SET content = $content,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = $id AND user_id = $userId
            RETURNING id, appointment_id, user_id, attendance_location_name,
                      patient_snapshot, clinic_snapshot, content, pdf_url,
                      signed_at, created_at, updated_at"""
        .query[Report]
        .option

    def sign(id: Long, userId: Long): ConnectionIO[Option[Report]] =
      sql"""UPDATE reports
            SET signed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = $id AND user_id = $userId
            RETURNING id, appointment_id, user_id, attendance_location_name,
                      patient_snapshot, clinic_snapshot, content, pdf_url,
                      signed_at, created_at, updated_at"""
        .query[Report]
        .option
