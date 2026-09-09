package com.visoris.backend.patients.service

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all.*

import com.visoris.backend.patients.domain.{Patient, PatientType}
import com.visoris.backend.patients.dto.{PatientRequest, ValidationError}
import com.visoris.backend.patients.repository.{ClientRepository, PatientRepository}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import java.time.LocalDate
import org.typelevel.log4cats.Logger

sealed trait PatientsError
object PatientsError:
  final case class Validation(errors: List[ValidationError]) extends PatientsError
  case object NotFound extends PatientsError
  final case class Internal(message: String) extends PatientsError

trait PatientService[F[_]]:
  def create(request: PatientRequest, userId: Long): F[Either[PatientsError, Patient]]
  def listByClient(clientId: Long, userId: Long, limit: Long, offset: Long): F[Either[PatientsError, List[Patient]]]

object PatientService:

  val MinNameLength = 1
  val MaxNameLength = 255
  val DefaultLimit = 50L
  val MaxLimit = 100L

  final case class ValidPatient(
    clientId: Long,
    name: String,
    patientType: PatientType,
    birthDate: Option[LocalDate],
    biologicalDetails: Json
  )

  def validateRequest(request: PatientRequest): Either[List[ValidationError], ValidPatient] =
    val rawName = request.sanitizedName

    val nameErrors =
      if rawName.isEmpty then List(ValidationError("name", "Nome do paciente é obrigatório."))
      else if rawName.length < MinNameLength || rawName.length > MaxNameLength then
        List(ValidationError("name", s"Nome deve ter entre $MinNameLength e $MaxNameLength caracteres."))
      else Nil

    val typeErrors = request.patientType match
      case Left(msg) => List(ValidationError("patient_type", msg))
      case Right(_)  => Nil

    val birthDateErrors = request.birthDate match
      case Some(date) if date.isAfter(LocalDate.now()) =>
        List(ValidationError("birth_date", "Data de nascimento não pode estar no futuro."))
      case _ => Nil

    val clientErrors =
      if request.clientId <= 0 then List(ValidationError("client_id", "ID do cliente inválido."))
      else Nil

    val allErrors = nameErrors ++ typeErrors ++ birthDateErrors ++ clientErrors
    if allErrors.nonEmpty then Left(allErrors)
    else
      Right(
        ValidPatient(
          clientId = request.clientId,
          name = rawName,
          patientType = request.patientType.toOption.get,
          birthDate = request.birthDate,
          biologicalDetails = request.biologicalDetails
        )
      )

  def make[F[_]: Async: Logger](
    patientRepo: PatientRepository[F],
    clientRepo: ClientRepository[F],
    transactor: Transactor[F]
  ): PatientService[F] = new PatientService[F]:

    def create(request: PatientRequest, userId: Long): F[Either[PatientsError, Patient]] =
      validateRequest(request) match
        case Left(errors) =>
          Logger[F].info(s"Patient creation validation failed: ${errors.length} error(s)") *>
            Async[F].pure(Left(PatientsError.Validation(errors)))
        case Right(valid) =>
          Logger[F].info(s"Patient create attempt for clientId=${valid.clientId} by userId=$userId") *>
            createProgram(valid, userId)
              .transact(transactor)
              .flatTap {
                case Right(patient) =>
                  Logger[F].info(s"Patient created id=${patient.id} for clientId=${patient.clientId.getOrElse(0L)}")
                case Left(PatientsError.NotFound) =>
                  Logger[F].warn(s"Patient create rejected (404): clientId=${valid.clientId} not linked to userId=$userId")
                case Left(PatientsError.Internal(msg)) =>
                  Logger[F].error(s"Patient create internal error: $msg")
                case Left(_) => Async[F].unit
              }

    def listByClient(clientId: Long, userId: Long, limit: Long, offset: Long): F[Either[PatientsError, List[Patient]]] =
      Logger[F].info(s"Listing patients for clientId=$clientId by userId=$userId") *>
        listProgram(clientId, userId, limit, offset)
          .transact(transactor)

    private def createProgram(
      valid: ValidPatient,
      userId: Long
    ): ConnectionIO[Either[PatientsError, Patient]] =
      val program: EitherT[ConnectionIO, PatientsError, Patient] = for
        clientOpt <- EitherT.right[PatientsError](clientRepo.findById(valid.clientId))
        client <- EitherT.fromOption[ConnectionIO](clientOpt, PatientsError.NotFound)
        _ <- EitherT.cond[ConnectionIO](client.userId == userId, (), PatientsError.NotFound)
        created <- EitherT.right[PatientsError](
          patientRepo.insert(
            clientId = valid.clientId,
            name = valid.name,
            patientType = valid.patientType,
            birthDate = valid.birthDate,
            biologicalDetails = valid.biologicalDetails
          )
        )
      yield created

      program.value

    private def listProgram(
      clientId: Long,
      userId: Long,
      limit: Long,
      offset: Long
    ): ConnectionIO[Either[PatientsError, List[Patient]]] =
      val safeLimit = if limit <= 0 then DefaultLimit else math.min(limit, MaxLimit)
      val safeOffset = math.max(0L, offset)

      val program: EitherT[ConnectionIO, PatientsError, List[Patient]] = for
        clientOpt <- EitherT.right[PatientsError](clientRepo.findById(clientId))
        client <- EitherT.fromOption[ConnectionIO](clientOpt, PatientsError.NotFound)
        _ <- EitherT.cond[ConnectionIO](client.userId == userId, (), PatientsError.NotFound)
        patients <- EitherT.right[PatientsError](patientRepo.findByClientId(clientId, safeLimit, safeOffset))
      yield patients

      program.value
