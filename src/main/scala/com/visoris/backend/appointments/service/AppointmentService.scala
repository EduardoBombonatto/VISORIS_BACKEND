package com.visoris.backend.appointments.service

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.appointments.domain.{ExamStatus, PaymentStatus, ReportStatus}
import com.visoris.backend.appointments.dto.{AppointmentResponse, CreateAppointmentRequest, ValidationError}
import com.visoris.backend.appointments.repository.AppointmentRepository
import com.visoris.backend.clinics.repository.DoctorClinicRepository
import com.visoris.backend.patients.repository.PatientRepository
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import java.time.Instant
import org.typelevel.log4cats.Logger

sealed trait AppointmentsError
object AppointmentsError:
  final case class Validation(errors: List[ValidationError]) extends AppointmentsError
  case object NotFound extends AppointmentsError
  final case class Internal(message: String) extends AppointmentsError

trait AppointmentService[F[_]]:
  def create(request: CreateAppointmentRequest, userId: Long): F[Either[AppointmentsError, AppointmentResponse]]
  def listFiltered(
    userId: Long,
    startDate: Option[Instant],
    endDate: Option[Instant],
    clinicId: Option[Long]
  ): F[Either[AppointmentsError, List[AppointmentResponse]]]
  def updateStatus(
    id: Long,
    userId: Long,
    examStatus: Option[ExamStatus],
    reportStatus: Option[ReportStatus],
    paymentStatus: Option[PaymentStatus]
  ): F[Either[AppointmentsError, AppointmentResponse]]

object AppointmentService:

  final case class ValidCreateAppointment(
    clinicId: Long,
    patientId: Long,
    scheduledAt: Instant,
    procedureName: String,
    price: Option[BigDecimal],
    paymentStatus: PaymentStatus = PaymentStatus.UNPAID
  )

  def validateCreateRequest(request: CreateAppointmentRequest): Either[List[ValidationError], ValidCreateAppointment] =
    val clinicErrors =
      if request.clinicId <= 0 then List(ValidationError("clinic_id", "ID da clínica inválido.")) else Nil
    val patientErrors =
      if request.patientId <= 0 then List(ValidationError("patient_id", "ID do paciente inválido.")) else Nil
    val scheduledAtErrors = request.scheduledAt match
      case Left(msg) => List(ValidationError("scheduled_at", msg))
      case Right(_)  => Nil
    val proc = request.sanitizedProcedureName
    val procErrors =
      if proc.isEmpty then List(ValidationError("procedure_name", "Nome do procedimento não pode ser vazio."))
      else if proc.length > 255 then List(ValidationError("procedure_name", "Nome do procedimento deve ter no máximo 255 caracteres."))
      else Nil
    val priceErrors = request.price match
      case Some(p) if p < BigDecimal(0) => List(ValidationError("price", "O preço não pode ser negativo."))
      case _ => Nil

    val allErrors = clinicErrors ++ patientErrors ++ scheduledAtErrors ++ procErrors ++ priceErrors
    if allErrors.nonEmpty then Left(allErrors)
    else
      Right(
        ValidCreateAppointment(
          clinicId = request.clinicId,
          patientId = request.patientId,
          scheduledAt = request.scheduledAt.toOption.get,
          procedureName = proc,
          price = request.price,
          paymentStatus = request.paymentStatus.getOrElse(PaymentStatus.UNPAID)
        )
      )

  def make[F[_]: Async: Logger](
    appointmentRepo: AppointmentRepository[F],
    doctorClinicRepo: DoctorClinicRepository[F],
    patientRepo: PatientRepository[F],
    transactor: Transactor[F]
  ): AppointmentService[F] = new AppointmentService[F]:

    def create(request: CreateAppointmentRequest, userId: Long): F[Either[AppointmentsError, AppointmentResponse]] =
      validateCreateRequest(request) match
        case Left(errors) =>
          Logger[F].info(s"Appointment creation validation failed: ${errors.length} error(s)") *>
            Async[F].pure(Left(AppointmentsError.Validation(errors)))
        case Right(valid) =>
          Logger[F].info(s"Appointment create attempt for clinicId=${valid.clinicId} patientId=${valid.patientId} by userId=$userId") *>
            createProgram(valid, userId)
              .transact(transactor)
              .flatTap {
                case Right(resp) =>
                  Logger[F].info(s"Appointment created id=${resp.id} for userId=$userId")
                case Left(AppointmentsError.NotFound) =>
                  Logger[F].warn(s"Appointment create rejected (404): clinic or patient not accessible by userId=$userId")
                case Left(AppointmentsError.Internal(msg)) =>
                  Logger[F].error(s"Appointment create internal error: $msg")
                case Left(_) => Async[F].unit
              }

    def listFiltered(
      userId: Long,
      startDate: Option[Instant],
      endDate: Option[Instant],
      clinicId: Option[Long]
    ): F[Either[AppointmentsError, List[AppointmentResponse]]] =
      val dateValidationError = for
        s <- startDate
        e <- endDate
        if s.isAfter(e)
      yield List(ValidationError("startDate", "A data inicial (startDate) não pode ser posterior à data final (endDate)."))

      dateValidationError match
        case Some(errors) =>
          Async[F].pure(Left(AppointmentsError.Validation(errors)))
        case None =>
          val program: EitherT[ConnectionIO, AppointmentsError, List[AppointmentResponse]] = for
            _ <- clinicId match
              case Some(cid) =>
                for
                  isLinked <- EitherT.right[AppointmentsError](doctorClinicRepo.isLinked(userId, cid))
                  _ <- EitherT.cond[ConnectionIO](isLinked, (), AppointmentsError.NotFound)
                yield ()
              case None => EitherT.pure[ConnectionIO, AppointmentsError](())
            list <- EitherT.right[AppointmentsError](
              appointmentRepo.findAllFiltered(userId, startDate, endDate, clinicId)
            )
          yield list.map(AppointmentResponse.fromDetails)

          program.value.transact(transactor)

    def updateStatus(
      id: Long,
      userId: Long,
      examStatus: Option[ExamStatus],
      reportStatus: Option[ReportStatus],
      paymentStatus: Option[PaymentStatus]
    ): F[Either[AppointmentsError, AppointmentResponse]] =
      if examStatus.isEmpty && reportStatus.isEmpty && paymentStatus.isEmpty then
        Async[F].pure(Left(AppointmentsError.Validation(List(ValidationError("status", "Pelo menos um status deve ser informado para atualização.")))))
      else
        val program: EitherT[ConnectionIO, AppointmentsError, AppointmentResponse] = for
          updatedOpt <- EitherT.right[AppointmentsError](
            appointmentRepo.updateStatus(id, userId, examStatus, reportStatus, paymentStatus)
          )
          _ <- EitherT.fromOption[ConnectionIO](updatedOpt, AppointmentsError.NotFound)
          detailsOpt <- EitherT.right[AppointmentsError](appointmentRepo.findDetailsById(id, userId))
          response <- EitherT.fromOption[ConnectionIO](
            detailsOpt.map(AppointmentResponse.fromDetails),
            AppointmentsError.NotFound
          )
        yield response

        program.value.transact(transactor)

    private def createProgram(
      valid: ValidCreateAppointment,
      userId: Long
    ): ConnectionIO[Either[AppointmentsError, AppointmentResponse]] =
      val program: EitherT[ConnectionIO, AppointmentsError, AppointmentResponse] = for
        isClinicLinked <- EitherT.right[AppointmentsError](doctorClinicRepo.isLinked(userId, valid.clinicId))
        _ <- EitherT.cond[ConnectionIO](isClinicLinked, (), AppointmentsError.NotFound)

        patientOwnerOpt <- EitherT.right[AppointmentsError](patientRepo.findOwnerUserId(valid.patientId))
        _ <- EitherT.cond[ConnectionIO](patientOwnerOpt.contains(userId), (), AppointmentsError.NotFound)

        created <- EitherT.right[AppointmentsError](
          appointmentRepo.insert(
            userId = userId,
            clinicId = valid.clinicId,
            patientId = valid.patientId,
            scheduledAt = valid.scheduledAt,
            procedureName = valid.procedureName,
            price = valid.price,
            paymentStatus = valid.paymentStatus
          )
        )

        detailsOpt <- EitherT.right[AppointmentsError](appointmentRepo.findDetailsById(created.id, userId))
        response <- EitherT.fromOption[ConnectionIO](
          detailsOpt.map(AppointmentResponse.fromDetails),
          AppointmentsError.NotFound
        )
      yield response

      program.value
