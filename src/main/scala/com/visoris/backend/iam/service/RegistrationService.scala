package com.visoris.backend.iam.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.dto.{RegisterRequest, ValidationError}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.shared.auth.{CustomClaims, OpaqueTokenGenerator, PasswordHasher, TokenService}
import com.visoris.backend.shared.utils.SecurityUtils
import doobie.implicits.*
import doobie.util.transactor.Transactor
import java.sql.SQLException
import org.typelevel.log4cats.Logger
import java.time.Instant

final case class RegistrationResult(
  accessToken: String,
  user: User,
  refreshToken: String
)

sealed trait RegistrationError
object RegistrationError:
  final case class Validation(errors: List[ValidationError]) extends RegistrationError
  final case class DuplicateField(field: String, message: String) extends RegistrationError
  final case class Internal(message: String) extends RegistrationError

trait RegistrationService[F[_]]:
  def register(
    request: RegisterRequest,
    deviceInfo: Option[String],
    ipAddress: Option[String]
  ): F[Either[RegistrationError, RegistrationResult]]

object RegistrationService:
  def make[F[_]: Async: Logger](
    tokenService: TokenService,
    transactor: Transactor[F],
    userRepo: UserRepository[F],
    refreshTokenRepo: RefreshTokenRepository[F]
  ): RegistrationService[F] = new RegistrationService[F]:
    def register(
      request: RegisterRequest,
      deviceInfo: Option[String],
      ipAddress: Option[String]
    ): F[Either[RegistrationError, RegistrationResult]] =
      val normalizedEmail = request.email.trim.toLowerCase
      val maskedEmail = SecurityUtils.maskEmail(normalizedEmail)

      val validationErrors = request.validate
      if validationErrors.nonEmpty then
        Logger[F].info(s"Registration validation failed for email=$maskedEmail — ${validationErrors.length} error(s)") *>
          Async[F].pure(Left(RegistrationError.Validation(validationErrors)))
      else
        Logger[F].info(s"Registration attempt for email=$maskedEmail") *>
          checkPreConditions(normalizedEmail, request.professionalDocument.map(_.trim).filter(_.nonEmpty)).flatMap {
            case Left(error) =>
              logPreConditionError(error, maskedEmail) *> Async[F].pure(Left(error))
            case Right(professionalDocument) =>
              doRegister(normalizedEmail, maskedEmail, request, professionalDocument, deviceInfo, ipAddress)
          }

    private def logPreConditionError(
      error: RegistrationError,
      maskedEmail: String
    ): F[Unit] =
      error match
        case RegistrationError.DuplicateField("email", _) =>
          Logger[F].info(s"Registration rejected — duplicate email: $maskedEmail")
        case RegistrationError.DuplicateField("professionalDocument", _) =>
          Logger[F].info(s"Registration rejected — duplicate professional document")
        case _ => Async[F].unit

    private def checkPreConditions(
      normalizedEmail: String,
      professionalDocument: Option[String]
    ): F[Either[RegistrationError, Option[String]]] =
      userRepo.findByEmail(normalizedEmail).transact(transactor).flatMap {
        case Some(_) =>
          Async[F].pure(Left(RegistrationError.DuplicateField("email", "Este e-mail já está cadastrado.")))
        case None =>
          checkProfessionalDocumentDuplicate(professionalDocument)
      }

    private def checkProfessionalDocumentDuplicate(
      doc: Option[String]
    ): F[Either[RegistrationError, Option[String]]] =
      doc match
        case Some(d) =>
          userRepo.findByProfessionalDocument(d).transact(transactor).map {
            case Some(_) => Left(RegistrationError.DuplicateField("professionalDocument", "Documento profissional já cadastrado."))
            case None => Right(doc)
          }
        case None => Async[F].pure(Right(doc))

    private def doRegister(
      normalizedEmail: String,
      maskedEmail: String,
      request: RegisterRequest,
      professionalDocument: Option[String],
      deviceInfo: Option[String],
      ipAddress: Option[String]
    ): F[Either[RegistrationError, RegistrationResult]] =
      (for
        now <- Async[F].delay(Instant.now)
        passwordHash <- PasswordHasher.hash[F](request.password)
        userId <- transactor.trans.apply(sql"SELECT next_id()".query[Long].unique)
        accessToken <- tokenService.createAccessToken[F](
          CustomClaims(
            userId = userId.toString,
            email = normalizedEmail,
            roles = List("DOCTOR"),
            tokenType = "ACCESS"
          )
        )
        refreshTokenPlain <- OpaqueTokenGenerator.generate[F]
        refreshExpires = now.plusSeconds(7 * 24 * 60 * 60)

        user = User(
          id = userId,
          email = normalizedEmail,
          passwordHash = passwordHash,
          fullName = request.fullName.trim,
          professionalDocument = professionalDocument,
          createdAt = now
        )

        persistResult <- (for
          _ <- userRepo.create(user)
          _ <- refreshTokenRepo.create(user.id, refreshTokenPlain, refreshExpires, deviceInfo, ipAddress)
        yield ()).transact(transactor).attempt.map {
          case Right(_) =>
            Right(RegistrationResult(accessToken, user, refreshTokenPlain))
          case Left(e: SQLException) if e.getSQLState == "23505" =>
            val msg = Option(e.getMessage).getOrElse("").toLowerCase
            if msg.contains("professional_document") then
              Left(RegistrationError.DuplicateField("professionalDocument", "Documento profissional já cadastrado."))
            else
              Left(RegistrationError.DuplicateField("email", "Este e-mail já está cadastrado."))
          case Left(_) =>
            Left(RegistrationError.Internal("Erro interno ao criar conta. Tente novamente."))
        }
      yield persistResult).flatTap {
        case Left(RegistrationError.Internal(msg)) =>
          Logger[F].error(s"Registration internal error for email=$maskedEmail: $msg")
        case Left(_) => Async[F].unit
        case Right(_) => Logger[F].info(s"Registration successful for email=$maskedEmail")
      }
