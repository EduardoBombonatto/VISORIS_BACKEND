package com.visoris.backend.patients.service

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all.*

import com.visoris.backend.patients.domain.{Patient, PatientType}
import com.visoris.backend.patients.dto.{PatientRequest, UpdatePatientRequest, ValidationError}
import com.visoris.backend.patients.repository.{ClientRepository, PatientRepository}
import com.visoris.backend.shared.utils.SecurityUtils
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import io.circe.{Json, JsonObject}
import java.time.LocalDate
import org.typelevel.log4cats.Logger

sealed trait PatientsError
object PatientsError:
  final case class Validation(errors: List[ValidationError]) extends PatientsError
  case object NotFound extends PatientsError
  case object ConflictAppointments extends PatientsError
  final case class Internal(message: String) extends PatientsError

trait PatientService[F[_]]:
  def create(request: PatientRequest, userId: Long): F[Either[PatientsError, Patient]]
  def listByClient(clientId: Long, userId: Long, limit: Long, offset: Long): F[Either[PatientsError, List[Patient]]]
  def update(patientId: Long, request: UpdatePatientRequest, userId: Long): F[Either[PatientsError, Patient]]
  def delete(patientId: Long, userId: Long): F[Either[PatientsError, Unit]]

object PatientService:

  val MinNameLength = 1
  val MaxNameLength = 255
  val MaxBiologicalDetailsBytes = 16384
  val DefaultLimit = 50L
  val MaxLimit = 100L

  final case class ValidPatient(
    clientId: Long,
    name: String,
    patientType: PatientType,
    birthDate: Option[LocalDate],
    biologicalDetails: Json
  )

  final case class ValidUpdatePatient(
    name: String,
    patientType: PatientType,
    birthDate: Option[LocalDate],
    biologicalDetails: Json
  )

  private final case class PetBioFields(
    age: Option[String],
    breed: Option[String],
    coatColor: Option[String]
  )

  private def extractPetBioFields(biologicalDetails: Json): PetBioFields =
    val cursor = biologicalDetails.hcursor
    val age = cursor.downField("age").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val breed = cursor.downField("breed").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val coatColor = cursor
      .downField("coat_color")
      .as[String]
      .orElse(cursor.downField("coatColor").as[String])
      .toOption
      .map(_.trim)
      .filter(_.nonEmpty)
    PetBioFields(age, breed, coatColor)

  private def validatePetFields(birthDate: Option[LocalDate], pet: PetBioFields): List[ValidationError] =
    val birthOrAgeError =
      if birthDate.isEmpty && pet.age.isEmpty then
        List(ValidationError("birth_date", "Informe a data de nascimento ou a idade do animal."))
      else if pet.age.exists(_.length > 100) then
        List(ValidationError("age", "Idade não pode ter mais de 100 caracteres."))
      else Nil

    val breedError =
      if pet.breed.isEmpty then List(ValidationError("breed", "Raça é obrigatória para o animal."))
      else if pet.breed.exists(_.length > 100) then List(ValidationError("breed", "Raça não pode ter mais de 100 caracteres."))
      else Nil

    val coatColorError =
      if pet.coatColor.isEmpty then List(ValidationError("coat_color", "Cor da pelagem é obrigatória para o animal."))
      else if pet.coatColor.exists(_.length > 100) then List(ValidationError("coat_color", "Cor da pelagem não pode ter mais de 100 caracteres."))
      else Nil

    birthOrAgeError ++ breedError ++ coatColorError

  private def validatePatientFields(
    name: String,
    patientTypeEither: Either[String, PatientType],
    birthDate: Option[LocalDate],
    biologicalDetails: Json,
    invalidBirthDate: Boolean
  ): Either[List[ValidationError], (String, PatientType, Option[LocalDate], Json)] =
    val rawName = name.trim
    val nameErrors =
      if rawName.isEmpty then List(ValidationError("name", "Nome do paciente é obrigatório."))
      else if SecurityUtils.containsNullByte(rawName) then
        List(ValidationError("name", "Nome do paciente contém caracteres inválidos."))
      else if rawName.length < MinNameLength || rawName.length > MaxNameLength then
        List(ValidationError("name", s"Nome deve ter entre $MinNameLength e $MaxNameLength caracteres."))
      else Nil

    val typeErrors = patientTypeEither.left.toOption.map(ValidationError("patient_type", _)).toList
    val pType = patientTypeEither.toOption

    val birthDateErrors =
      (if invalidBirthDate then List(ValidationError("birth_date", "Formato de data de nascimento inválido. Esperado: YYYY-MM-DD.")) else Nil) ++
        (birthDate match
          case Some(date) if date.isAfter(LocalDate.now()) =>
            List(ValidationError("birth_date", "Data de nascimento não pode estar no futuro."))
          case Some(date) if date.getYear < 1900 =>
            List(ValidationError("birth_date", "Data de nascimento fora do intervalo permitido."))
          case _ => Nil)

    val serializedBio = biologicalDetails.noSpaces
    val bioFormatErrors =
      if !biologicalDetails.isObject then
        List(ValidationError("biological_details", "Detalhes biológicos devem ser um objeto JSON."))
      else if SecurityUtils.containsNullByte(serializedBio) then
        List(ValidationError("biological_details", "Detalhes biológicos contêm caracteres inválidos."))
      else if serializedBio.length > MaxBiologicalDetailsBytes then
        List(ValidationError("biological_details", s"Detalhes biológicos excedem o limite máximo de $MaxBiologicalDetailsBytes bytes."))
      else Nil

    val petFields = extractPetBioFields(biologicalDetails)
    val bioErrors = pType match
      case Some(PatientType.PET) => validatePetFields(birthDate, petFields)
      case _                     => Nil

    val allErrors = nameErrors ++ typeErrors ++ birthDateErrors ++ bioFormatErrors ++ bioErrors
    if allErrors.nonEmpty then Left(allErrors)
    else
      val baseObj = biologicalDetails.asObject.getOrElse(JsonObject.empty)
      val withBreed = petFields.breed.fold(baseObj)(b => baseObj.add("breed", Json.fromString(b)))
      val withCoat = petFields.coatColor.fold(withBreed)(c => withBreed.add("coat_color", Json.fromString(c)))
      val withAge = petFields.age.fold(withCoat)(a => withCoat.add("age", Json.fromString(a)))
      Right((rawName, pType.get, birthDate, Json.fromJsonObject(withAge)))

  def validateRequest(request: PatientRequest): Either[List[ValidationError], ValidPatient] =
    val clientErrors =
      if request.clientId <= 0 then List(ValidationError("client_id", "ID do cliente inválido."))
      else Nil

    validatePatientFields(request.name, request.patientType, request.birthDate, request.biologicalDetails, request.invalidBirthDate) match
      case Left(errors) => Left(clientErrors ++ errors)
      case Right((name, pType, bDate, bio)) =>
        if clientErrors.nonEmpty then Left(clientErrors)
        else Right(ValidPatient(request.clientId, name, pType, bDate, bio))

  def validateUpdateRequest(request: UpdatePatientRequest): Either[List[ValidationError], ValidUpdatePatient] =
    validatePatientFields(request.name, request.patientType, request.birthDate, request.biologicalDetails, request.invalidBirthDate).map {
      case (name, pType, bDate, bio) => ValidUpdatePatient(name, pType, bDate, bio)
    }

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

    def update(patientId: Long, request: UpdatePatientRequest, userId: Long): F[Either[PatientsError, Patient]] =
      validateUpdateRequest(request) match
        case Left(errors) =>
          Logger[F].info(s"Patient update validation failed: ${errors.length} error(s)") *>
            Async[F].pure(Left(PatientsError.Validation(errors)))
        case Right(valid) =>
          Logger[F].info(s"Patient update attempt for patientId=$patientId by userId=$userId") *>
            updateProgram(patientId, valid, userId)
              .transact(transactor)
              .flatTap {
                case Right(patient) =>
                  Logger[F].info(s"Patient updated id=${patient.id}")
                case Left(PatientsError.NotFound) =>
                  Logger[F].warn(s"Patient update rejected (404): patientId=$patientId not found for userId=$userId")
                case Left(PatientsError.Internal(msg)) =>
                  Logger[F].error(s"Patient update internal error: $msg")
                case Left(_) => Async[F].unit
              }

    def delete(patientId: Long, userId: Long): F[Either[PatientsError, Unit]] =
      Logger[F].info(s"Patient delete attempt for patientId=$patientId by userId=$userId") *>
        deleteProgram(patientId, userId)
          .transact(transactor)
          .flatTap {
            case Right(_) =>
              Logger[F].info(s"Patient deleted id=$patientId for userId=$userId")
            case Left(PatientsError.NotFound) =>
              Logger[F].warn(s"Patient delete rejected (404): patientId=$patientId not found for userId=$userId")
            case Left(PatientsError.ConflictAppointments) =>
              Logger[F].warn(s"Patient delete rejected (409): patientId=$patientId has linked appointments")
            case Left(PatientsError.Internal(msg)) =>
              Logger[F].error(s"Patient delete internal error: $msg")
            case Left(_) => Async[F].unit
          }

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

    private def updateProgram(
      patientId: Long,
      valid: ValidUpdatePatient,
      userId: Long
    ): ConnectionIO[Either[PatientsError, Patient]] =
      val program: EitherT[ConnectionIO, PatientsError, Patient] = for
        ownerOpt <- EitherT.right[PatientsError](patientRepo.findOwnerUserId(patientId))
        _ <- EitherT.cond[ConnectionIO](ownerOpt.contains(userId), (), PatientsError.NotFound)
        updatedOpt <- EitherT.right[PatientsError](
          patientRepo.update(
            id = patientId,
            name = valid.name,
            patientType = valid.patientType,
            birthDate = valid.birthDate,
            biologicalDetails = valid.biologicalDetails
          )
        )
        updated <- EitherT.fromOption[ConnectionIO](updatedOpt, PatientsError.NotFound)
      yield updated

      program.value

    private def deleteProgram(
      patientId: Long,
      userId: Long
    ): ConnectionIO[Either[PatientsError, Unit]] =
      val program: EitherT[ConnectionIO, PatientsError, Unit] = for
        ownerOpt <- EitherT.right[PatientsError](patientRepo.findOwnerUserId(patientId))
        _ <- EitherT.cond[ConnectionIO](ownerOpt.contains(userId), (), PatientsError.NotFound)
        linked <- EitherT.right[PatientsError](patientRepo.hasAppointments(patientId))
        _ <- EitherT.cond[ConnectionIO](!linked, (), PatientsError.ConflictAppointments)
        _ <- EitherT.right[PatientsError](patientRepo.delete(patientId))
      yield ()

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
