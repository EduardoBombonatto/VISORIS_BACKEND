package com.visoris.backend.patients.service

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all.*

import com.visoris.backend.patients.domain.Client
import com.visoris.backend.patients.dto.{ClientRequest, ValidationError}
import com.visoris.backend.patients.repository.ClientRepository
import com.visoris.backend.shared.utils.SecurityUtils
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import java.sql.SQLException
import org.typelevel.log4cats.Logger

sealed trait ClientsError
object ClientsError:
  final case class Validation(errors: List[ValidationError]) extends ClientsError
  case object NotFound extends ClientsError
  case object ConflictCpf extends ClientsError
  case object ConflictEmail extends ClientsError
  case object ConflictPhone extends ClientsError
  case object ConflictPatients extends ClientsError
  final case class Internal(message: String) extends ClientsError

trait ClientService[F[_]]:
  def create(request: ClientRequest, userId: Long): F[Either[ClientsError, Client]]
  def listByUser(userId: Long, limit: Long, offset: Long): F[Either[ClientsError, List[Client]]]
  def update(clientId: Long, request: ClientRequest, userId: Long): F[Either[ClientsError, Client]]
  def delete(clientId: Long, userId: Long): F[Either[ClientsError, Unit]]

object ClientService:

  val MinNameLength = 2
  val MaxNameLength = 255
  val DefaultLimit = 50L
  val MaxLimit = 100L
  private val EmailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r

  private def isValidCpf(digits: String): Boolean =
    if digits.length != 11 || digits.distinct.length == 1 then false
    else
      val d = digits.map(_ - '0')
      val s1 = (0 until 9).map(i => d(i) * (10 - i)).sum
      val r1 = (s1 * 10) % 11
      val c1 = if r1 == 10 then 0 else r1
      val s2 = (0 until 10).map(i => d(i) * (11 - i)).sum
      val r2 = (s2 * 10) % 11
      val c2 = if r2 == 10 then 0 else r2
      d(9) == c1 && d(10) == c2

  def validateRequest(request: ClientRequest): Either[List[ValidationError], ClientRequest] =
    val sanitized = request.sanitized
    val rawName = sanitized.fullName

    val nameErrors =
      if rawName.isEmpty then List(ValidationError("full_name", "Nome do tutor é obrigatório."))
      else if SecurityUtils.containsNullByte(rawName) then
        List(ValidationError("full_name", "Nome do tutor contém caracteres inválidos."))
      else if rawName.length < MinNameLength || rawName.length > MaxNameLength then
        List(ValidationError("full_name", s"Nome deve ter entre $MinNameLength e $MaxNameLength caracteres."))
      else Nil

    val cpfErrors =
      if sanitized.documentCpf.isEmpty then List(ValidationError("document_cpf", "CPF é obrigatório."))
      else if sanitized.documentCpf.length != 11 then
        List(ValidationError("document_cpf", "CPF deve conter exatamente 11 dígitos."))
      else if !isValidCpf(sanitized.documentCpf) then
        List(ValidationError("document_cpf", "CPF inválido."))
      else Nil

    val emailErrors =
      if sanitized.email.isEmpty then List(ValidationError("email", "E-mail é obrigatório."))
      else if SecurityUtils.containsNullByte(sanitized.email) then
        List(ValidationError("email", "E-mail contém caracteres inválidos."))
      else if !EmailRegex.matches(sanitized.email) then
        List(ValidationError("email", "Formato de e-mail inválido."))
      else if sanitized.email.length > 255 then
        List(ValidationError("email", "E-mail não pode ter mais de 255 caracteres."))
      else Nil

    val phoneErrors =
      if sanitized.phone.isEmpty then List(ValidationError("phone", "Telefone é obrigatório."))
      else if sanitized.phone.length < 10 || sanitized.phone.length > 11 then
        List(ValidationError("phone", "Telefone deve ter entre 10 e 11 dígitos (DDD + número)."))
      else Nil

    val allErrors = nameErrors ++ cpfErrors ++ emailErrors ++ phoneErrors
    if allErrors.nonEmpty then Left(allErrors)
    else Right(sanitized)

  def make[F[_]: Async: Logger](
    clientRepo: ClientRepository[F],
    transactor: Transactor[F]
  ): ClientService[F] = new ClientService[F]:

    private def mapUniqueViolation(err: Throwable): Either[ClientsError, Nothing] =
      err match
        case e: SQLException if e.getSQLState == "23505" =>
          val msg = Option(e.getMessage).getOrElse("").toLowerCase
          if msg.contains("cpf") then Left(ClientsError.ConflictCpf)
          else if msg.contains("email") then Left(ClientsError.ConflictEmail)
          else if msg.contains("phone") then Left(ClientsError.ConflictPhone)
          else Left(ClientsError.ConflictCpf)
        case _ =>
          Left(ClientsError.Internal("Erro interno ao processar cliente."))

    def create(request: ClientRequest, userId: Long): F[Either[ClientsError, Client]] =
      validateRequest(request) match
        case Left(errors) =>
          Logger[F].info(s"Client creation validation failed: ${errors.length} error(s)") *>
            Async[F].pure(Left(ClientsError.Validation(errors)))
        case Right(valid) =>
          Logger[F].info(s"Client create attempt by userId=$userId") *>
            createProgram(valid, userId)
              .transact(transactor)
              .attempt
              .map(_.fold(mapUniqueViolation, identity))
              .flatTap {
                case Right(client) =>
                  Logger[F].info(s"Client created id=${client.id} for userId=${client.userId}")
                case Left(ClientsError.ConflictCpf) =>
                  Logger[F].warn(s"Client create rejected (409): CPF already exists for userId=$userId")
                case Left(ClientsError.ConflictEmail) =>
                  Logger[F].warn(s"Client create rejected (409): E-mail already exists for userId=$userId")
                case Left(ClientsError.ConflictPhone) =>
                  Logger[F].warn(s"Client create rejected (409): Phone already exists for userId=$userId")
                case Left(ClientsError.Internal(msg)) =>
                  Logger[F].error(s"Client create internal error: $msg")
                case Left(_) => Async[F].unit
              }

    def listByUser(userId: Long, limit: Long, offset: Long): F[Either[ClientsError, List[Client]]] =
      Logger[F].info(s"Listing clients for userId=$userId") *>
        listProgram(userId, limit, offset)
          .transact(transactor)

    def update(clientId: Long, request: ClientRequest, userId: Long): F[Either[ClientsError, Client]] =
      validateRequest(request) match
        case Left(errors) =>
          Logger[F].info(s"Client update validation failed: ${errors.length} error(s)") *>
            Async[F].pure(Left(ClientsError.Validation(errors)))
        case Right(valid) =>
          Logger[F].info(s"Client update attempt for clientId=$clientId by userId=$userId") *>
            updateProgram(clientId, valid, userId)
              .transact(transactor)
              .attempt
              .map(_.fold(mapUniqueViolation, identity))
              .flatTap {
                case Right(client) =>
                  Logger[F].info(s"Client updated id=${client.id} for userId=${client.userId}")
                case Left(ClientsError.NotFound) =>
                  Logger[F].warn(s"Client update rejected (404): clientId=$clientId not found for userId=$userId")
                case Left(ClientsError.ConflictCpf) =>
                  Logger[F].warn(s"Client update rejected (409): CPF already exists for userId=$userId")
                case Left(ClientsError.ConflictEmail) =>
                  Logger[F].warn(s"Client update rejected (409): E-mail already exists for userId=$userId")
                case Left(ClientsError.ConflictPhone) =>
                  Logger[F].warn(s"Client update rejected (409): Phone already exists for userId=$userId")
                case Left(ClientsError.Internal(msg)) =>
                  Logger[F].error(s"Client update internal error: $msg")
                case Left(_) => Async[F].unit
              }

    def delete(clientId: Long, userId: Long): F[Either[ClientsError, Unit]] =
      Logger[F].info(s"Client delete attempt for clientId=$clientId by userId=$userId") *>
        deleteProgram(clientId, userId)
          .transact(transactor)
          .flatTap {
            case Right(_) =>
              Logger[F].info(s"Client deleted id=$clientId for userId=$userId")
            case Left(ClientsError.NotFound) =>
              Logger[F].warn(s"Client delete rejected (404): clientId=$clientId not found for userId=$userId")
            case Left(ClientsError.ConflictPatients) =>
              Logger[F].warn(s"Client delete rejected (409): clientId=$clientId has linked patients")
            case Left(ClientsError.Internal(msg)) =>
              Logger[F].error(s"Client delete internal error: $msg")
            case Left(_) => Async[F].unit
          }

    private def createProgram(
      valid: ClientRequest,
      userId: Long
    ): ConnectionIO[Either[ClientsError, Client]] =
      val program: EitherT[ConnectionIO, ClientsError, Client] = for
        conflicts <- EitherT.right[ClientsError](clientRepo.findConflicts(userId, valid.documentCpf, valid.email, valid.phone))
        _ <- EitherT.cond[ConnectionIO](!conflicts.exists(_.documentCpf.contains(valid.documentCpf)), (), ClientsError.ConflictCpf)
        _ <- EitherT.cond[ConnectionIO](!conflicts.exists(_.email.contains(valid.email)), (), ClientsError.ConflictEmail)
        _ <- EitherT.cond[ConnectionIO](!conflicts.exists(_.phone.contains(valid.phone)), (), ClientsError.ConflictPhone)
        created <- EitherT.right[ClientsError](
          clientRepo.insert(
            userId = userId,
            fullName = valid.fullName,
            documentCpf = Some(valid.documentCpf),
            email = Some(valid.email),
            phone = Some(valid.phone)
          )
        )
      yield created

      program.value

    private def updateProgram(
      clientId: Long,
      valid: ClientRequest,
      userId: Long
    ): ConnectionIO[Either[ClientsError, Client]] =
      val program: EitherT[ConnectionIO, ClientsError, Client] = for
        clientOpt <- EitherT.right[ClientsError](clientRepo.findById(clientId))
        client <- EitherT.fromOption[ConnectionIO](clientOpt, ClientsError.NotFound)
        _ <- EitherT.cond[ConnectionIO](client.userId == userId, (), ClientsError.NotFound)
        conflicts <- EitherT.right[ClientsError](clientRepo.findConflictsExcluding(clientId, userId, valid.documentCpf, valid.email, valid.phone))
        _ <- EitherT.cond[ConnectionIO](!conflicts.exists(_.documentCpf.contains(valid.documentCpf)), (), ClientsError.ConflictCpf)
        _ <- EitherT.cond[ConnectionIO](!conflicts.exists(_.email.contains(valid.email)), (), ClientsError.ConflictEmail)
        _ <- EitherT.cond[ConnectionIO](!conflicts.exists(_.phone.contains(valid.phone)), (), ClientsError.ConflictPhone)
        updatedOpt <- EitherT.right[ClientsError](
          clientRepo.update(
            id = clientId,
            userId = userId,
            fullName = valid.fullName,
            documentCpf = Some(valid.documentCpf),
            email = Some(valid.email),
            phone = Some(valid.phone)
          )
        )
        updated <- EitherT.fromOption[ConnectionIO](updatedOpt, ClientsError.NotFound)
      yield updated

      program.value

    private def deleteProgram(
      clientId: Long,
      userId: Long
    ): ConnectionIO[Either[ClientsError, Unit]] =
      val program: EitherT[ConnectionIO, ClientsError, Unit] = for
        clientOpt <- EitherT.right[ClientsError](clientRepo.findById(clientId))
        client <- EitherT.fromOption[ConnectionIO](clientOpt, ClientsError.NotFound)
        _ <- EitherT.cond[ConnectionIO](client.userId == userId, (), ClientsError.NotFound)
        linked <- EitherT.right[ClientsError](clientRepo.hasPatients(clientId))
        _ <- EitherT.cond[ConnectionIO](!linked, (), ClientsError.ConflictPatients)
        _ <- EitherT.right[ClientsError](clientRepo.delete(clientId, userId))
      yield ()

      program.value

    private def listProgram(
      userId: Long,
      limit: Long,
      offset: Long
    ): ConnectionIO[Either[ClientsError, List[Client]]] =
      val safeLimit = if limit <= 0 then DefaultLimit else math.min(limit, MaxLimit)
      val safeOffset = math.max(0L, offset)

      val program: EitherT[ConnectionIO, ClientsError, List[Client]] = for
        clients <- EitherT.right[ClientsError](clientRepo.findByUserId(userId, safeLimit, safeOffset))
      yield clients

      program.value
