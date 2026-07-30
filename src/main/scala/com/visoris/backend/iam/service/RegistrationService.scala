package com.visoris.backend.iam.service

import cats.effect.Sync
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.dto.{RegisterRequest, ValidationError}
import com.visoris.backend.iam.repository.{UserRepository, RefreshTokenRepository}
import com.visoris.backend.shared.auth.{JwtService, JwtUserClaims, PasswordHasher}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.postgres.sqlstate
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import java.security.SecureRandom
import java.time.Instant

final case class RegistrationResult(
  baseToken: String,
  user: User,
  refreshToken: String
)

sealed trait RegistrationError
object RegistrationError:
  final case class Validation(errors: List[ValidationError]) extends RegistrationError
  final case class DuplicateField(field: String, message: String) extends RegistrationError
  final case class Internal(message: String) extends RegistrationError

object RegistrationService:

  private val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r
  private val asciiPattern = """^[\x20-\x7E]+$""".r

  private val disposableDomains = Set(
    "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com",
    "throwaway.email", "yopmail.com", "sharklasers.com", "trashmail.com",
    "temp-mail.org", "fakeinbox.com", "guerrillamail.info", "guerrillamail.biz",
    "maildrop.cc", "getairmail.com", "mailnesia.com", "spamgourmet.com",
    "spambox.us", "dispostable.com", "mailcatch.com", "tempinbox.com"
  )

  private def validateRequiredFields(request: RegisterRequest): List[ValidationError] =
    List(
      Option.when(request.fullName.isBlank)(ValidationError("fullName", "Nome completo é obrigatório.")),
      Option.when(request.email.isBlank)(ValidationError("email", "E-mail é obrigatório.")),
      Option.when(request.password.isBlank)(ValidationError("password", "Senha é obrigatória."))
    ).flatten

  private def validateEmailFormat(email: String): Option[ValidationError] =
    if emailRegex.matches(email) then None
    else Some(ValidationError("email", "Formato de e-mail inválido."))

  private def validatePassword(password: String): List[ValidationError] =
    List(
      Option.when(password.trim.isEmpty)(ValidationError("password", "Senha é obrigatória.")),
      Option.when(password.length < 8)(ValidationError("password", "A senha deve conter pelo menos 8 caracteres.")),
      Option.when(!password.exists(_.isUpper))(ValidationError("password", "A senha deve conter pelo menos uma letra maiúscula.")),
      Option.when(!password.exists(_.isLower))(ValidationError("password", "A senha deve conter pelo menos uma letra minúscula.")),
      Option.when(!password.exists(_.isDigit))(ValidationError("password", "A senha deve conter pelo menos um número.")),
      Option.when(password.forall(ch => ch.isLetterOrDigit))(ValidationError("password", "A senha deve conter pelo menos um caractere especial.")),
      Option.when(!asciiPattern.matches(password))(ValidationError("password", "A senha contém caracteres não permitidos. Use apenas letras, números e símbolos padrão."))
    ).flatten

  private def validateFieldLengths(request: RegisterRequest): List[ValidationError] =
    List(
      Option.when(request.fullName.length > 255)(ValidationError("fullName", "Nome completo excede o limite de 255 caracteres.")),
      Option.when(request.email.length > 255)(ValidationError("email", "E-mail excede o limite de 255 caracteres.")),
      Option.when(request.professionalDocument.exists(_.length > 50))(ValidationError("professionalDocument", "Documento profissional excede o limite de 50 caracteres."))
    ).flatten

  private def validateDisposableEmail(email: String): Option[ValidationError] =
    val domain = email.split("@").lastOption.getOrElse("").toLowerCase
    if disposableDomains.contains(domain) then
      Some(ValidationError("email", "Por favor, use um e-mail profissional ou pessoal válido."))
    else None

  private def validate(request: RegisterRequest): List[ValidationError] =
    validateRequiredFields(request) ++
      validateEmailFormat(request.email).toList ++
      validatePassword(request.password) ++
      validateFieldLengths(request) ++
      validateDisposableEmail(request.email).toList

  private def maskEmail(email: String): String =
    val parts = email.split("@")
    if parts.length == 2 then
      val local = parts(0)
      val domain = parts(1)
      if local.length <= 2 then s"$local**@$domain"
      else s"${local.take(2)}**@$domain"
    else email

  private def generateRefreshToken(): String =
    val random = new SecureRandom()
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString

  private def checkEmailExists(userRepo: UserRepository, email: String): ConnectionIO[Unit] =
    userRepo.findByEmail(email).flatMap {
      case Some(_) =>
        val msg = "Este e-mail já está cadastrado."
        doobie.free.connection.raiseError(
          new java.sql.SQLException(msg, sqlstate.class23.UNIQUE_VIOLATION.value)
        )
      case None => ().pure[ConnectionIO]
    }

  private def checkProfessionalDocumentExists(userRepo: UserRepository, doc: Option[String]): ConnectionIO[Unit] =
    doc.traverse(d => userRepo.findByProfessionalDocument(d)).flatMap {
      case Some(Some(_)) =>
        doobie.free.connection.raiseError(
          new java.sql.SQLException("Documento profissional já cadastrado.", sqlstate.class23.UNIQUE_VIOLATION.value)
        )
      case _ => ().pure[ConnectionIO]
    }

  def register[F[_]: Sync: Logger](
    request: RegisterRequest,
    jwtSecret: String,
    transactor: Transactor[F],
    deviceInfo: Option[String],
    ipAddress: Option[String]
  )(implicit
    userRepo: UserRepository,
    refreshTokenRepo: RefreshTokenRepository
  ): F[Either[RegistrationError, RegistrationResult]] =
    val normalizedEmail = request.email.toLowerCase
    val maskedEmail = maskEmail(normalizedEmail)
    val validationErrors = validate(request)
    if validationErrors.nonEmpty then
      Logger[F].info(s"Registration validation failed for email=$maskedEmail — ${validationErrors.length} error(s)") *>
        Sync[F].pure(Left(RegistrationError.Validation(validationErrors)))
    else
      Logger[F].info(s"Registration attempt for email=$maskedEmail") *>
      (for
        now <- Sync[F].delay(Instant.now)
        passwordHash <- PasswordHasher.hash[F](request.password)
        userId <- transactor.trans.apply(sql"SELECT next_id()".query[Long].unique)
        baseToken <- JwtService.createAccessToken[F](
          jwtSecret,
          JwtUserClaims(userId = userId, email = normalizedEmail, clinicId = None, roles = List("DOCTOR"))
        )
        refreshTokenPlain = generateRefreshToken()
        refreshExpires = now.plusSeconds(7 * 24 * 60 * 60)

        user = User(
          id = userId,
          email = normalizedEmail,
          passwordHash = passwordHash,
          fullName = request.fullName.trim,
          professionalDocument = request.professionalDocument.map(_.trim).filter(_.nonEmpty),
          createdAt = now
        )

        dbProgram: ConnectionIO[Unit] = for
          _ <- checkEmailExists(userRepo, request.email)
          _ <- checkProfessionalDocumentExists(userRepo, request.professionalDocument.map(_.trim).filter(_.nonEmpty))
          _ <- userRepo.create(user)
          _ <- refreshTokenRepo.create(user.id, refreshTokenPlain, refreshExpires, deviceInfo, ipAddress)
        yield ()

        result <- transactor.trans.apply(dbProgram).attempt.map {
          case Right(()) =>
            Right(RegistrationResult(baseToken, user, refreshTokenPlain))
          case Left(e) =>
            val sqle = e match
              case s: java.sql.SQLException => Some(s)
              case _ => e.getCause match
                case s: java.sql.SQLException => Some(s)
                case _ => None
            sqle match
              case Some(s) if s.getSQLState == sqlstate.class23.UNIQUE_VIOLATION.value =>
                val msg = s.getMessage
                if msg != null && (msg.contains("Documento profissional") || msg.contains("professional_document")) then
                  Left(RegistrationError.DuplicateField("professionalDocument", "Documento profissional já cadastrado."))
                else
                  Left(RegistrationError.DuplicateField("email", "Este e-mail já está cadastrado."))
              case _ =>
                Left(RegistrationError.Internal("Erro interno do servidor. Tente novamente."))
        }

        _ <- result match
          case Right(_) => Logger[F].info(s"Registration successful for email=$maskedEmail")
          case Left(RegistrationError.DuplicateField("email", _)) =>
            Logger[F].info(s"Registration rejected — duplicate email: $maskedEmail")
          case Left(RegistrationError.Internal(msg)) =>
            Logger[F].error(s"Registration internal error for email=$maskedEmail: $msg")
          case _ => Sync[F].unit
      yield result)
