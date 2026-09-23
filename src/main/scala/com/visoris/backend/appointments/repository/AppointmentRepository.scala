package com.visoris.backend.appointments.repository

import com.visoris.backend.appointments.domain.{Appointment, AppointmentWithDetails, ExamStatus, PaymentStatus, ReportStatus}
import com.visoris.backend.appointments.repository.DoobieInstances.given
import com.visoris.backend.patients.repository.DoobieInstances.given
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor
import java.time.Instant

trait AppointmentRepository[F[_]]:
  def insert(
    userId: Long,
    clinicId: Long,
    patientId: Long,
    scheduledAt: Instant,
    procedureName: String,
    price: Option[BigDecimal],
    paymentStatus: PaymentStatus = PaymentStatus.UNPAID
  ): ConnectionIO[Appointment]

  def findById(id: Long, userId: Long): ConnectionIO[Option[Appointment]]

  def findDetailsById(id: Long, userId: Long): ConnectionIO[Option[AppointmentWithDetails]]

  def findAllFiltered(
    userId: Long,
    startDate: Option[Instant],
    endDate: Option[Instant],
    clinicId: Option[Long]
  ): ConnectionIO[List[AppointmentWithDetails]]

  def updateStatus(
    id: Long,
    userId: Long,
    examStatus: Option[ExamStatus],
    reportStatus: Option[ReportStatus],
    paymentStatus: Option[PaymentStatus]
  ): ConnectionIO[Option[Appointment]]

object AppointmentRepository:
  def make[F[_]](transactor: Transactor[F]): AppointmentRepository[F] = new AppointmentRepository[F]:

    def insert(
      userId: Long,
      clinicId: Long,
      patientId: Long,
      scheduledAt: Instant,
      procedureName: String,
      price: Option[BigDecimal],
      paymentStatus: PaymentStatus = PaymentStatus.UNPAID
    ): ConnectionIO[Appointment] =
      sql"""INSERT INTO appointments (
              user_id, clinic_id, patient_id, scheduled_at, procedure_name,
              exam_status, report_status, payment_status, price
            ) VALUES (
              $userId, $clinicId, $patientId, $scheduledAt, $procedureName,
              'SCHEDULED'::exam_status_enum, 'PENDING'::report_status_enum, $paymentStatus, $price
            )
            RETURNING id, user_id, clinic_id, patient_id, scheduled_at, procedure_name,
                      exam_status, report_status, payment_status, price, created_at, updated_at"""
        .query[Appointment]
        .unique

    def findById(id: Long, userId: Long): ConnectionIO[Option[Appointment]] =
      sql"""SELECT id, user_id, clinic_id, patient_id, scheduled_at, procedure_name,
                   exam_status, report_status, payment_status, price, created_at, updated_at
            FROM appointments
            WHERE id = $id AND user_id = $userId"""
        .query[Appointment]
        .option

    def findDetailsById(id: Long, userId: Long): ConnectionIO[Option[AppointmentWithDetails]] =
      sql"""SELECT a.id, a.user_id, a.clinic_id, a.patient_id, a.scheduled_at, a.procedure_name,
                   a.exam_status, a.report_status, a.payment_status, a.price, a.created_at, a.updated_at,
                   p.name, p.patient_type, c.full_name, cl.name
            FROM appointments a
            JOIN patients p ON a.patient_id = p.id
            JOIN clients c ON p.client_id = c.id
            JOIN clinics cl ON a.clinic_id = cl.id
            WHERE a.id = $id AND a.user_id = $userId"""
        .query[AppointmentWithDetails]
        .option

    def findAllFiltered(
      userId: Long,
      startDate: Option[Instant],
      endDate: Option[Instant],
      clinicId: Option[Long]
    ): ConnectionIO[List[AppointmentWithDetails]] =
      val filters = Fragments.whereAndOpt(
        Some(fr"a.user_id = $userId"),
        startDate.map(sd => fr"a.scheduled_at >= $sd"),
        endDate.map(ed => fr"a.scheduled_at <= $ed"),
        clinicId.map(cid => fr"a.clinic_id = $cid")
      )

      val query = fr"""
        SELECT a.id, a.user_id, a.clinic_id, a.patient_id, a.scheduled_at, a.procedure_name,
               a.exam_status, a.report_status, a.payment_status, a.price, a.created_at, a.updated_at,
               p.name, p.patient_type, c.full_name, cl.name
        FROM appointments a
        JOIN patients p ON a.patient_id = p.id
        JOIN clients c ON p.client_id = c.id
        JOIN clinics cl ON a.clinic_id = cl.id
      """ ++ filters ++ fr"ORDER BY a.scheduled_at ASC"

      query.query[AppointmentWithDetails].to[List]

    def updateStatus(
      id: Long,
      userId: Long,
      examStatus: Option[ExamStatus],
      reportStatus: Option[ReportStatus],
      paymentStatus: Option[PaymentStatus]
    ): ConnectionIO[Option[Appointment]] =
      val setFragments = List(
        examStatus.map(s => fr"exam_status = $s"),
        reportStatus.map(s => fr"report_status = $s"),
        paymentStatus.map(s => fr"payment_status = $s"),
        Some(fr"updated_at = CURRENT_TIMESTAMP")
      ).flatten

      cats.data.NonEmptyList.fromList(setFragments) match
        case Some(nel) =>
          val query = fr"""
            UPDATE appointments
          """ ++ Fragments.set(nel) ++ fr"""
            WHERE id = $id AND user_id = $userId
            RETURNING id, user_id, clinic_id, patient_id, scheduled_at, procedure_name,
                      exam_status, report_status, payment_status, price, created_at, updated_at
          """

          query.query[Appointment].option
        case None =>
          findById(id, userId)
