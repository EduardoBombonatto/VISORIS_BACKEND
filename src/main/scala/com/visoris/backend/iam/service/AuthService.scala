package com.visoris.backend.iam.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.dto.{LoginRequest, ValidationError}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.shared.auth.{CustomClaims, OpaqueTokenGenerator, PasswordHasher, TokenService}
import com.visoris.backend.shared.utils.SecurityUtils
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
  newRefreshToken: String,
  user: User
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

    def login(
      request: LoginRequest,
      deviceInfo: Option[String],
      ipAddress: Option[String]
    ): F[Either[LoginError, LoginResult]] =
      val normalizedEmail = request.email.trim.toLowerCase
      val masked = SecurityUtils.maskEmail(normalizedEmail)
      val refreshExpiresTime = 7 * 24 * 60 * 60

      val validationErrors = request.validate
      if validationErrors.nonEmpty then
        Logger[F].info(s"Login validation failed for email=$masked — ${validationErrors.length} error(s)") *>
          Async[F].pure(Left(LoginError.Validation(validationErrors)))
      else
        Logger[F].info(s"Login attempt for email=$masked") *>
          userRepo.findByEmail(normalizedEmail).transact(transactor).flatMap {
            case None =>
              PasswordHasher.verifyDummy[F](request.password) *>
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
                        tokenType = "ACCESS"
                      )
                    )
                    refreshTokenPlain <- OpaqueTokenGenerator.generate[F]
                    refreshExpires = now.plusSeconds(refreshExpiresTime)
                    _ <- refreshTokenRepo.create(user.id, refreshTokenPlain, refreshExpires, deviceInfo, ipAddress).transact(transactor)
                    _ <- Logger[F].info(s"Login successful for email=$masked")
                  yield Right(LoginResult(accessToken, user, refreshTokenPlain))
              }
          }.flatTap {
            case Left(LoginError.Internal(msg)) =>
              Logger[F].error(s"Login internal error for email=$masked: $msg")
            case _ => Async[F].unit
          }

    def refresh(cookieToken: String): F[Either[RefreshError, RefreshResult]] =
      for
        _ <- Logger[F].info("Refresh attempt")
        now <- Async[F].delay(Instant.now)
        newRefreshToken <- OpaqueTokenGenerator.generate[F]
        newExpires = now.plusSeconds(7L * 24 * 60 * 60)
        outcome <- refreshAtomic(cookieToken, now, newRefreshToken, newExpires).transact(transactor)
        result <- outcome match
          case Left(RefreshError.Unknown) =>
            Logger[F].info("Refresh rejected — token unknown or user missing") *>
              Async[F].pure(Left(RefreshError.Unknown))
          case Left(RefreshError.Revoked) =>
            Logger[F].warn("Refresh rejected — token already revoked (possible theft); all sessions revoked") *>
              Async[F].pure(Left(RefreshError.Revoked))
          case Left(RefreshError.Expired) =>
            Logger[F].info("Refresh rejected — token expired") *>
              Async[F].pure(Left(RefreshError.Expired))
          case Left(other) =>
            Logger[F].error(s"Refresh error: $other") *>
              Async[F].pure(Left(other))
          case Right(user) =>
            for
              accessToken <- tokenService.createAccessToken[F](
                CustomClaims(
                  userId = user.id.toString,
                  email = user.email,
                  roles = List("DOCTOR"),
                  tokenType = "ACCESS"
                )
              )
              _ <- Logger[F].info(s"Refresh successful for user=${user.id}")
            yield Right(RefreshResult(accessToken, 900, newRefreshToken, user))
      yield result

    private def refreshAtomic(
      cookieToken: String,
      now: Instant,
      newRefreshToken: String,
      newExpires: Instant
    ): doobie.ConnectionIO[Either[RefreshError, User]] =
      refreshTokenRepo.findByTokenForUpdate(cookieToken).flatMap {
        case None =>
          (Left(RefreshError.Unknown): Either[RefreshError, User]).pure[doobie.ConnectionIO]
        case Some(rt) if rt.isRevoked =>
          refreshTokenRepo.revokeAllByUserId(rt.userId)
            .as(Left(RefreshError.Revoked): Either[RefreshError, User])
        case Some(rt) if rt.expiresAt.isBefore(now) =>
          (Left(RefreshError.Expired): Either[RefreshError, User]).pure[doobie.ConnectionIO]
        case Some(rt) =>
          userRepo.findById(rt.userId).flatMap {
            case None =>
              (Left(RefreshError.Unknown): Either[RefreshError, User]).pure[doobie.ConnectionIO]
            case Some(user) =>
              for
                _ <- refreshTokenRepo.revokeByToken(cookieToken, "ROTATION")
                _ <- refreshTokenRepo.create(user.id, newRefreshToken, newExpires, rt.deviceInfo, rt.ipAddress)
              yield Right(user)
          }
      }

    def session(accessToken: Option[String], refreshToken: Option[String]): F[Either[SessionError, SessionResult]] =
      accessToken match
        case Some(token) =>
          tokenService.validateToken[F](token, "ACCESS").flatMap {
            case Some(claims) =>
              claims.userId.toLongOption match
                case Some(uid) => loadSession(uid, None, None)
                case None      => refreshAndLoad(refreshToken)
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
          refresh(rt).map {
            case Left(RefreshError.Internal(msg)) => Left(SessionError.Internal(msg))
            case Left(_)                          => Left(SessionError.Unauthenticated)
            case Right(result) =>
              Right(SessionResult(result.user, Some(result.accessToken), Some(result.newRefreshToken)))
          }
