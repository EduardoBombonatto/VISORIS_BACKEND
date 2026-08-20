package com.visoris.backend.iam.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.{RefreshToken, User}
import com.visoris.backend.iam.dto.{LoginRequest, ValidationError}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.shared.auth.{CustomClaims, OpaqueTokenGenerator, PasswordHasher, TokenService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import java.time.Instant

final case class LoginResult(
  accessToken: String,
  user: User,
  refreshToken: String
)

final case class RefreshResult(
  accessToken: String,
  expiresIn: Int,
  newRefreshToken: String
)

final case class SessionResult(
  user: User,
  newAccessToken: Option[String],
  newRefreshToken: Option[String]
)

sealed trait LoginError
object LoginError:
  case class Validation(errors: List[ValidationError]) extends LoginError
  case object InvalidCredentials extends LoginError
  case class Internal(message: String) extends LoginError

sealed trait RefreshError
object RefreshError:
  case object Missing extends RefreshError
  case object Unknown extends RefreshError
  case object Expired extends RefreshError
  case object Revoked extends RefreshError
  case class Internal(message: String) extends RefreshError

sealed trait SessionError
object SessionError:
  case object Unauthenticated extends SessionError
  case class Internal(message: String) extends SessionError

sealed trait LogoutError
object LogoutError:
  case class Internal(message: String) extends LogoutError

trait AuthService[F[_]]:
  def login(
    request: LoginRequest,
    deviceInfo: Option[String],
    ipAddress: Option[String]
  ): F[Either[LoginError, LoginResult]]
  def refresh(cookieToken: String): F[Either[RefreshError, RefreshResult]]
  def session(accessToken: Option[String], refreshToken: Option[String]): F[Either[SessionError, SessionResult]]
  def logout(refreshToken: Option[String]): F[Either[LogoutError, Unit]]

object AuthService:
  def make[F[_]: Async: Logger](
    tokenService: TokenService,
    transactor: Transactor[F],
    userRepo: UserRepository[F],
    refreshTokenRepo: RefreshTokenRepository[F]
  ): AuthService[F] = new AuthService[F]:

    private def maskEmail(email: String): String =
      val parts = email.split("@")
      if parts.length == 2 then
        val local = parts(0)
        val domain = parts(1)
        if local.length <= 2 then s"$local**@$domain"
        else s"${local.take(2)}**@$domain"
      else email

    def login(
      request: LoginRequest,
      deviceInfo: Option[String],
      ipAddress: Option[String]
    ): F[Either[LoginError, LoginResult]] =
      val normalizedEmail = request.email.toLowerCase
      val masked = maskEmail(normalizedEmail)
      val refreshExpiresTime = 7 * 24 * 60 * 60

      val validationErrors = request.validate
      if validationErrors.nonEmpty then
        Logger[F].info(s"Login validation failed for email=$masked — ${validationErrors.length} error(s)") *>
          Async[F].pure(Left(LoginError.Validation(validationErrors)))
      else
        Logger[F].info(s"Login attempt for email=$masked") *>
          userRepo.findByEmail(normalizedEmail).transact(transactor).flatMap {
            case None =>
              Logger[F].info(s"Login rejected — email not found: $masked") *>
                Async[F].pure(Left(LoginError.InvalidCredentials))
            case Some(user) =>
              PasswordHasher.verify[F](request.password, user.passwordHash).flatMap {
                case false =>
                  Logger[F].info(s"Login rejected — wrong password for email=$masked") *>
                    Async[F].pure(Left(LoginError.InvalidCredentials))
                case true =>
                  for
                    now <- Async[F].delay(Instant.now)
                    accessToken <- tokenService.createAccessToken[F](
                      CustomClaims(
                        userId = user.id.toString,
                        email = user.email,
                        roles = List("DOCTOR"),
                        clinicId = None,
                        tokenType = "ACCESS"
                      )
                    )
                    refreshTokenPlain <- OpaqueTokenGenerator.generate[F]
                    refreshExpires = now.plusSeconds(refreshExpiresTime)
                    _ <- refreshTokenRepo.create(user.id, refreshTokenPlain, refreshExpires, deviceInfo, ipAddress, None, None).transact(transactor)
                    _ <- Logger[F].info(s"Login successful for email=$masked")
                  yield Right(LoginResult(accessToken, user, refreshTokenPlain))
              }
          }.flatTap {
            case Left(LoginError.Internal(msg)) =>
              Logger[F].error(s"Login internal error for email=$masked: $msg")
            case _ => Async[F].unit
          }

    private val ReuseGraceSeconds = 60

    private def reuseWithinGrace(rt: RefreshToken): Boolean =
      rt.revokedReason.contains("ROTATION") &&
        rt.revokedAt.exists(_.isAfter(Instant.now.minusSeconds(ReuseGraceSeconds)))

    def refresh(cookieToken: String): F[Either[RefreshError, RefreshResult]] =
      Logger[F].info("Refresh attempt") *>
        refreshTokenRepo.findByToken(cookieToken).transact(transactor).flatMap {
          case None =>
            Logger[F].info("Refresh rejected — token unknown") *>
              Async[F].pure(Left(RefreshError.Unknown))
          case Some(rt) if rt.isRevoked && !reuseWithinGrace(rt) =>
            Logger[F].warn(s"Refresh rejected — token already revoked (possible theft); revoking all sessions for user=${rt.userId}") *>
              refreshTokenRepo.revokeAllByUserId(rt.userId).transact(transactor) *>
              Async[F].pure(Left(RefreshError.Revoked))
          case Some(rt) if rt.isRevoked =>
            Logger[F].warn(s"Refresh — revoked token reused within grace window (possible concurrent rotation); rotating again for user=${rt.userId}") *>
              rotate(rt)
          case Some(rt) if rt.expiresAt.isBefore(Instant.now) =>
            Logger[F].info("Refresh rejected — token expired") *>
              Async[F].pure(Left(RefreshError.Expired))
          case Some(rt) =>
            rotate(rt)
        }.flatTap {
          case Left(RefreshError.Internal(msg)) =>
            Logger[F].error(s"Refresh internal error: $msg")
          case _ => Async[F].unit
        }

    private def rotate(rt: RefreshToken): F[Either[RefreshError, RefreshResult]] =
      for
        user <- userRepo.findById(rt.userId).transact(transactor)
        now <- Async[F].delay(Instant.now)
        accessToken <- tokenService.createAccessToken[F](
          CustomClaims(
            userId = rt.userId.toString,
            email = user.fold("")(_.email),
            roles = List("DOCTOR"),
            clinicId = None,
            tokenType = "ACCESS"
          )
        )
        newRefreshToken <- OpaqueTokenGenerator.generate[F]
        newExpires = now.plusSeconds(7 * 24 * 60 * 60)
        _ <- (for
          _ <- refreshTokenRepo.revokeByToken(rt.token, "ROTATION")
          _ <- refreshTokenRepo.create(rt.userId, newRefreshToken, newExpires, rt.deviceInfo, rt.ipAddress, rt.clinicId, rt.role)
        yield ()).transact(transactor)
        _ <- Logger[F].info(s"Refresh successful for user=${rt.userId}")
      yield Right(RefreshResult(accessToken, 900, newRefreshToken))

    def session(accessToken: Option[String], refreshToken: Option[String]): F[Either[SessionError, SessionResult]] =
      accessToken match
        case Some(token) =>
          tokenService.validateToken[F](token, "ACCESS").flatMap {
            case Some(claims) =>
              loadSession(claims.userId.toLong, None, None)
            case None =>
              refreshAndLoad(refreshToken)
          }
        case None =>
          refreshAndLoad(refreshToken)

    def logout(refreshToken: Option[String]): F[Either[LogoutError, Unit]] =
      refreshToken match
        case None =>
          Logger[F].info("Logout sem refresh token — nada a revogar") *>
            Async[F].pure(Right(()))
        case Some(token) =>
          Logger[F].info("Logout attempt") *>
            refreshTokenRepo.revokeByToken(token, "LOGOUT").transact(transactor).attempt.flatMap {
              case Right(_) =>
                Logger[F].info("Logout successful — refresh token revoked") *>
                  Async[F].pure(Right(()))
              case Left(err) =>
                Logger[F].error(s"Logout internal error: ${err.getMessage}") *>
                  Async[F].pure(Left(LogoutError.Internal("Erro interno ao processar o logout.")))
            }

    private def loadSession(
      userId: Long,
      newAccessToken: Option[String],
      newRefreshToken: Option[String]
    ): F[Either[SessionError, SessionResult]] =
      userRepo.findById(userId).transact(transactor).map {
        case None => Left(SessionError.Unauthenticated)
        case Some(user) =>
          Right(SessionResult(user, newAccessToken, newRefreshToken))
      }

    private def refreshAndLoad(refreshToken: Option[String]): F[Either[SessionError, SessionResult]] =
      refreshToken match
        case None => Async[F].pure(Left(SessionError.Unauthenticated))
        case Some(rt) =>
          refresh(rt).flatMap {
            case Left(RefreshError.Internal(msg)) => Async[F].pure(Left(SessionError.Internal(msg)))
            case Left(_)                          => Async[F].pure(Left(SessionError.Unauthenticated))
            case Right(result) =>
              refreshTokenRepo.findByToken(result.newRefreshToken).transact(transactor).flatMap {
                case None => Async[F].pure(Left(SessionError.Unauthenticated))
                case Some(row) =>
                  loadSession(row.userId, Some(result.accessToken), Some(result.newRefreshToken))
              }
          }
