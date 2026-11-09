package com.visoris.backend.reports.service

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.appointments.domain.ReportStatus
import com.visoris.backend.appointments.repository.AppointmentRepository
import com.visoris.backend.clinics.domain.Clinic
import com.visoris.backend.clinics.repository.ClinicRepository
import com.visoris.backend.patients.domain.Patient
import com.visoris.backend.patients.repository.PatientRepository
import com.visoris.backend.reports.domain.{ReportImage, ReportWithImages}
import com.visoris.backend.reports.dto.{CreateReportRequest, ReportImageRequest, UpdateReportRequest, ValidationError}
import com.visoris.backend.reports.repository.{ReportImageRepository, ReportRepository}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json

trait ReportService[F[_]]:
  def getByAppointmentId(appointmentId: Long, userId: Long): F[Either[ReportsError, ReportWithImages]]
  def createDraft(request: CreateReportRequest, userId: Long): F[Either[ReportsError, ReportWithImages]]
  def updateContent(id: Long, request: UpdateReportRequest, userId: Long): F[Either[ReportsError, ReportWithImages]]
  def addImage(reportId: Long, request: ReportImageRequest, userId: Long): F[Either[ReportsError, ReportImage]]
  def signReport(id: Long, userId: Long): F[Either[ReportsError, ReportWithImages]]

object ReportService:

  val MaxImageUrlLength = 1024
  val MaxCaptionLength  = 500

  final case class ValidCreateReport(appointmentId: Long, content: String)

  def validateCreateRequest(request: CreateReportRequest): Either[List[ValidationError], ValidCreateReport] =
    val apptErrors = request.appointmentId match
      case Some(id) if id > 0 => Nil
      case _                  => List(ValidationError("appointment_id", "O campo 'appointment_id' é obrigatório."))

    val contentErrors = request.content match
      case Some(_) => Nil
      case None    => List(ValidationError("content", "O campo 'content' é obrigatório."))

    val allErrors = apptErrors ++ contentErrors
    if allErrors.nonEmpty then Left(allErrors)
    else Right(ValidCreateReport(request.appointmentId.get, request.content.get))

  def validateUpdateRequest(request: UpdateReportRequest): Either[List[ValidationError], String] =
    request.content match
      case Some(c) => Right(c)
      case None    => Left(List(ValidationError("content", "O campo 'content' é obrigatório.")))

  def validateImageRequest(request: ReportImageRequest): Either[List[ValidationError], ReportImageRequest] =
    val sanitized = request.sanitized
    val urlErrors =
      if sanitized.imageUrl.isEmpty then List(ValidationError("image_url", "A URL da imagem é obrigatória."))
      else if sanitized.imageUrl.length > MaxImageUrlLength then
        List(ValidationError("image_url", s"A URL da imagem não pode ter mais de $MaxImageUrlLength caracteres."))
      else Nil

    val captionErrors = sanitized.caption match
      case Some(c) if c.length > MaxCaptionLength =>
        List(ValidationError("caption", s"A legenda não pode ter mais de $MaxCaptionLength caracteres."))
      case _ => Nil

    val orderErrors =
      if sanitized.displayOrder < 0 then
        List(ValidationError("display_order", "A ordem de exibição deve ser maior ou igual a zero."))
      else Nil

    val allErrors = urlErrors ++ captionErrors ++ orderErrors
    if allErrors.nonEmpty then Left(allErrors)
    else Right(sanitized)

  private def toClinicSnapshot(c: Clinic): Json =
    Json.obj(
      "id"      -> Json.fromString(c.id.toString),
      "name"    -> Json.fromString(c.name),
      "cnpj"    -> c.cnpj.fold(Json.Null)(Json.fromString),
      "phone"   -> c.phone.fold(Json.Null)(Json.fromString),
      "address" -> c.address.fold(Json.Null)(Json.fromString)
    )

  private def toPatientSnapshot(p: Patient): Json =
    Json.obj(
      "id"                -> Json.fromString(p.id.toString),
      "clientId"          -> p.clientId.fold(Json.Null)(id => Json.fromString(id.toString)),
      "name"              -> Json.fromString(p.name),
      "patientType"       -> Json.fromString(p.patientType.toString),
      "birthDate"         -> p.birthDate.fold(Json.Null)(d => Json.fromString(d.toString)),
      "biologicalDetails" -> p.biologicalDetails
    )

  def make[F[_]: Async](
    reportRepo: ReportRepository[F],
    reportImageRepo: ReportImageRepository[F],
    appointmentRepo: AppointmentRepository[F],
    clinicRepo: ClinicRepository[F],
    patientRepo: PatientRepository[F],
    transactor: Transactor[F]
  ): ReportService[F] = new ReportService[F]:

    def getByAppointmentId(appointmentId: Long, userId: Long): F[Either[ReportsError, ReportWithImages]] =
      val program: EitherT[ConnectionIO, ReportsError, ReportWithImages] = for
        reportOpt <- EitherT.right[ReportsError](reportRepo.findByAppointmentIdAndUserId(appointmentId, userId))
        report    <- EitherT.fromOption[ConnectionIO](reportOpt, ReportsError.NotFound)
        images    <- EitherT.right[ReportsError](reportImageRepo.findByReportId(report.id))
      yield ReportWithImages(report, images)

      program.value.transact(transactor)

    def createDraft(request: CreateReportRequest, userId: Long): F[Either[ReportsError, ReportWithImages]] =
      validateCreateRequest(request) match
        case Left(errors) => Async[F].pure(Left(ReportsError.Validation(errors)))
        case Right(valid) =>
          val program: EitherT[ConnectionIO, ReportsError, ReportWithImages] = for
            apptOpt <- EitherT.right[ReportsError](appointmentRepo.findById(valid.appointmentId, userId))
            appt    <- EitherT.fromOption[ConnectionIO](apptOpt, ReportsError.NotFound)
            existingOpt <- EitherT.right[ReportsError](
              reportRepo.findByAppointmentIdAndUserId(valid.appointmentId, userId)
            )
            _ <- EitherT.cond[ConnectionIO](
              existingOpt.isEmpty,
              (),
              ReportsError.Conflict("Já existe um laudo associado a este agendamento.")
            )
            clinicOpt  <- EitherT.right[ReportsError](clinicRepo.findById(appt.clinicId))
            patientOpt <- EitherT.right[ReportsError](patientRepo.findById(appt.patientId))
            created <- EitherT.right[ReportsError](
              reportRepo.insert(
                appointmentId = appt.id,
                userId = userId,
                attendanceLocationName = clinicOpt.map(_.name),
                patientSnapshot = patientOpt.map(toPatientSnapshot),
                clinicSnapshot = clinicOpt.map(toClinicSnapshot),
                content = valid.content
              )
            )
            _ <- EitherT.right[ReportsError](
              appointmentRepo.updateStatus(appt.id, userId, None, Some(ReportStatus.DRAFT), None)
            )
          yield ReportWithImages(created, Nil)

          program.value.transact(transactor)

    def updateContent(id: Long, request: UpdateReportRequest, userId: Long): F[Either[ReportsError, ReportWithImages]] =
      validateUpdateRequest(request) match
        case Left(errors) => Async[F].pure(Left(ReportsError.Validation(errors)))
        case Right(newContent) =>
          val program: EitherT[ConnectionIO, ReportsError, ReportWithImages] = for
            existingOpt <- EitherT.right[ReportsError](reportRepo.findByIdAndUserId(id, userId))
            existing    <- EitherT.fromOption[ConnectionIO](existingOpt, ReportsError.NotFound)
            _ <- EitherT.cond[ConnectionIO](
              existing.signedAt.isEmpty,
              (),
              ReportsError.Conflict("Laudo já assinado não pode ser alterado.")
            )
            updatedOpt <- EitherT.right[ReportsError](reportRepo.updateContent(id, userId, newContent))
            updated    <- EitherT.fromOption[ConnectionIO](updatedOpt, ReportsError.NotFound)
            images     <- EitherT.right[ReportsError](reportImageRepo.findByReportId(id))
          yield ReportWithImages(updated, images)

          program.value.transact(transactor)

    def addImage(reportId: Long, request: ReportImageRequest, userId: Long): F[Either[ReportsError, ReportImage]] =
      validateImageRequest(request) match
        case Left(errors) => Async[F].pure(Left(ReportsError.Validation(errors)))
        case Right(valid) =>
          val program: EitherT[ConnectionIO, ReportsError, ReportImage] = for
            existingOpt <- EitherT.right[ReportsError](reportRepo.findByIdAndUserId(reportId, userId))
            existing    <- EitherT.fromOption[ConnectionIO](existingOpt, ReportsError.NotFound)
            _ <- EitherT.cond[ConnectionIO](
              existing.signedAt.isEmpty,
              (),
              ReportsError.Conflict("Não é permitido adicionar imagens a um laudo já assinado.")
            )
            createdImg <- EitherT.right[ReportsError](
              reportImageRepo.insert(reportId, valid.imageUrl, valid.caption, valid.displayOrder)
            )
          yield createdImg

          program.value.transact(transactor)

    def signReport(id: Long, userId: Long): F[Either[ReportsError, ReportWithImages]] =
      val program: EitherT[ConnectionIO, ReportsError, ReportWithImages] = for
        existingOpt <- EitherT.right[ReportsError](reportRepo.findByIdAndUserId(id, userId))
        existing    <- EitherT.fromOption[ConnectionIO](existingOpt, ReportsError.NotFound)
        _ <- EitherT.cond[ConnectionIO](
          existing.signedAt.isEmpty,
          (),
          ReportsError.Conflict("Laudo já foi assinado anteriormente.")
        )
        signedOpt <- EitherT.right[ReportsError](reportRepo.sign(id, userId))
        signed    <- EitherT.fromOption[ConnectionIO](signedOpt, ReportsError.NotFound)
        _ <- EitherT.right[ReportsError](
          appointmentRepo.updateStatus(signed.appointmentId, userId, None, Some(ReportStatus.COMPLETED), None)
        )
        images <- EitherT.right[ReportsError](reportImageRepo.findByReportId(id))
      yield ReportWithImages(signed, images)

      program.value.transact(transactor)
