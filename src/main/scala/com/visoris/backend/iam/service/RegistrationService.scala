package com.visoris.backend.iam.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.dto.{RegisterRequest, ValidationError}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.shared.auth.{CustomClaims, OpaqueTokenGenerator, PasswordHasher, TokenService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
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
    private def maskEmail(email: String): String =
      val parts = email.split("@")
      if parts.length == 2 then
        val local = parts(0)
        val domain = parts(1)
        if local.length <= 2 then s"$local**@$domain"
        else s"${local.take(2)}**@$domain"
      else email

    def register(
      request: RegisterRequest,
      deviceInfo: Option[String],
      ipAddress: Option[String]
    ): F[Either[RegistrationError, RegistrationResult]] =
      val normalizedEmail = request.email.toLowerCase
      val maskedEmail = maskEmail(normalizedEmail)

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
            clinicId = None,
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

        _ <- userRepo.create(user).transact(transactor)
        result <- refreshTokenRepo.create(user.id, refreshTokenPlain, refreshExpires, deviceInfo, ipAddress, None, None).transact(transactor).as(
          Right(RegistrationResult(accessToken, user, refreshTokenPlain))
        )
      yield result).flatTap {
        case Left(RegistrationError.Internal(msg)) =>
          Logger[F].error(s"Registration internal error for email=$maskedEmail: $msg")
        case Left(_) => Async[F].unit
        case Right(_) => Logger[F].info(s"Registration successful for email=$maskedEmail")
      }
