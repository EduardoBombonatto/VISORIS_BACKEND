package com.visoris.backend.clinics.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.clinics.domain.{Clinic, Cnpj}
import com.visoris.backend.clinics.dto.{ClinicRequest, ValidationError}
import com.visoris.backend.clinics.repository.{ClinicRepository, DoctorClinicRepository}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

sealed trait ClinicsError
object ClinicsError:
  final case class Validation(errors: List[ValidationError]) extends ClinicsError
  final case class Internal(message: String) extends ClinicsError
  case object NotFound extends ClinicsError

sealed trait CreateResult
object CreateResult:
  final case class Created(clinic: Clinic) extends CreateResult
  final case class Reused(clinic: Clinic) extends CreateResult

trait ClinicsService[F[_]]:
  def listForDoctor(userId: Long): F[Either[ClinicsError, List[Clinic]]]
  def createOrReuse(request: ClinicRequest, userId: Long): F[Either[ClinicsError, CreateResult]]

object ClinicsService:

  val MinNameLength = 2
  val MaxNameLength = 255
  val MaxAddressLength = 500

  def validateRequest(
    request: ClinicRequest
  ): Either[List[ValidationError], (String, Option[String], Option[String], Option[String])] =
    val sanitized = request.sanitized
    val rawName = sanitized.name

    val nameErrors =
      if rawName.isEmpty then List(ValidationError("name", "Nome da clínica é obrigatório."))
      else if rawName.length < MinNameLength || rawName.length > MaxNameLength then
        List(ValidationError("name", s"Nome da clínica deve ter entre $MinNameLength e $MaxNameLength caracteres."))
      else Nil

    val phoneErrors = request.phone.toList.flatMap { raw =>
      val digits = raw.filter(_.isDigit)
      if digits.length < 10 || digits.length > 11 then
        List(ValidationError("phone", "Telefone deve ter entre 10 e 11 dígitos (DDD + número)."))
      else Nil
    }

    val cnpjErrors = request.cnpj.toList.flatMap { raw =>
      Cnpj.validate(raw).left.toOption.map(msg => ValidationError("cnpj", msg))
    }

    val addressErrors = request.address.toList.flatMap { raw =>
      val trimmed = raw.trim
      if trimmed.length > MaxAddressLength then
        List(ValidationError("address", s"Endereço deve ter no máximo $MaxAddressLength caracteres."))
      else Nil
    }

    val allErrors = nameErrors ++ phoneErrors ++ cnpjErrors ++ addressErrors
    if allErrors.nonEmpty then Left(allErrors)
    else Right((sanitized.name, sanitized.cnpj, sanitized.phone, sanitized.address))

  def make[F[_]: Async: Logger](
    clinicRepo: ClinicRepository[F],
    doctorClinicRepo: DoctorClinicRepository[F],
    transactor: Transactor[F]
  ): ClinicsService[F] = new ClinicsService[F]:

    def listForDoctor(userId: Long): F[Either[ClinicsError, List[Clinic]]] =
      Logger[F].info(s"Listing clinics for doctorId=$userId") *>
        doctorClinicRepo.findByDoctor(userId).transact(transactor).map(Right(_))

    def createOrReuse(request: ClinicRequest, userId: Long): F[Either[ClinicsError, CreateResult]] =
      validateRequest(request) match
        case Left(errors) =>
          Logger[F].info(s"Clinic create validation failed for doctorId=$userId — ${errors.length} error(s)") *>
            Async[F].pure(Left(ClinicsError.Validation(errors)))
        case Right(normalized) =>
          val (name, cnpj, phone, address) = normalized
          Logger[F].info(
            s"Clinic create/reuse attempt for doctorId=$userId cnpj=${maskCnpj(cnpj)}"
          ) *>
            doCreateOrReuse(name, cnpj, phone, address, userId)


    private def maskCnpj(cnpj: Option[String]): String =
      cnpj match
        case Some(value) if value.length >= 4 => s"${value.take(4)}...${value.takeRight(2)}"
        case Some(value)                       => value
        case None                              => "none"

    private def doCreateOrReuse(
      name: String,
      cnpj: Option[String],
      phone: Option[String],
      address: Option[String],
      userId: Long
    ): F[Either[ClinicsError, CreateResult]] =
      createOrReuseProgram(name, cnpj, phone, address, userId)
        .transact(transactor)
        .flatTap {
          case Right(CreateResult.Created(clinic)) =>
            Logger[F].info(s"Clinic created id=${clinic.id} for doctorId=$userId")
          case Right(CreateResult.Reused(clinic)) =>
            Logger[F].info(s"Clinic reused id=${clinic.id} for doctorId=$userId")
          case Left(ClinicsError.Internal(msg)) =>
            Logger[F].error(s"Clinic create error for doctorId=$userId: $msg")
          case Left(_) => Async[F].unit
        }

    private def createOrReuseProgram(
      name: String,
      cnpj: Option[String],
      phone: Option[String],
      address: Option[String],
      userId: Long
    ): ConnectionIO[Either[ClinicsError, CreateResult]] =
      for
        existing <- cnpj.traverse(clinicRepo.findByCnpj).map(_.flatten)
        outcome <- existing match
          case Some(clinic) =>
            doctorClinicRepo.insert(userId, clinic.id).as(Right(CreateResult.Reused(clinic)))
          case None =>
            for
              inserted <- clinicRepo.insertClinicIfAbsent(name, cnpj, phone, address)
              result <- inserted match
                case Some(clinic) =>
                  doctorClinicRepo.insert(userId, clinic.id).as(Right(CreateResult.Created(clinic)))
                case None =>
                  cnpj match
                    case Some(value) =>
                      clinicRepo.findByCnpj(value).flatMap {
                        case Some(clinic) =>
                          doctorClinicRepo.insert(userId, clinic.id).as(Right(CreateResult.Reused(clinic)))
                        case None =>
                          Left(ClinicsError.Internal("Conflito de CNPJ não resolvido.")).pure[ConnectionIO]
                      }
                    case None =>
                      Left(ClinicsError.Internal("Falha ao criar clínica.")).pure[ConnectionIO]
            yield result
      yield outcome
