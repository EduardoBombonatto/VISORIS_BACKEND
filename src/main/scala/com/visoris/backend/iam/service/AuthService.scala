package com.visoris.backend.iam.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.{User, Workspace}
import com.visoris.backend.iam.dto.{LoginRequest, ValidationError}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.shared.auth.{CustomClaims, OpaqueTokenGenerator, PasswordHasher, TokenService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import java.time.Instant

final case class LoginResult(
  baseToken: String,
  user: User,
  workspaces: List[Workspace],
  refreshToken: String
)

final case class RefreshResult(
  accessToken: String,
  expiresIn: Int,
  newRefreshToken: String
)

final case class SessionResult(
  user: User,
  workspaces: List[Workspace],
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

trait AuthService[F[_]]:
  def login(
    request: LoginRequest,
    deviceInfo: Option[String],
    ipAddress: Option[String]
  ): F[Either[LoginError, LoginResult]]
  def refresh(cookieToken: String): F[Either[RefreshError, RefreshResult]]
  def session(accessToken: Option[String], refreshToken: Option[String]): F[Either[SessionError, SessionResult]]

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
                    workspaces <- userRepo.findWorkspacesByUserId(user.id).transact(transactor)
                    now <- Async[F].delay(Instant.now)
                    baseToken <- tokenService.createBaseToken[F](
                      CustomClaims(
                        userId = user.id.toString,
                        email = user.email,
                        roles = List("DOCTOR"),
                        clinicId = None,
                        tokenType = "BASE"
                      )
                    )
                    refreshTokenPlain <- OpaqueTokenGenerator.generate[F]
                    refreshExpires = now.plusSeconds(7 * 24 * 60 * 60)
                    _ <- refreshTokenRepo.create(user.id, refreshTokenPlain, refreshExpires, deviceInfo, ipAddress, None, None).transact(transactor)
                    _ <- Logger[F].info(s"Login successful for email=$masked")
                  yield Right(LoginResult(baseToken, user, workspaces, refreshTokenPlain))
              }
          }.flatTap {
            case Left(LoginError.Internal(msg)) =>
              Logger[F].error(s"Login internal error for email=$masked: $msg")
            case _ => Async[F].unit
          }

    def refresh(cookieToken: String): F[Either[RefreshError, RefreshResult]] =
      Logger[F].info("Refresh attempt") *>
        refreshTokenRepo.findByToken(cookieToken).transact(transactor).flatMap {
          case None =>
            Logger[F].info("Refresh rejected — token unknown") *>
              Async[F].pure(Left(RefreshError.Unknown))
          case Some(rt) if rt.isRevoked =>
            Logger[F].warn(s"Refresh rejected — token already revoked (possible theft); revoking all sessions for user=${rt.userId}") *>
              refreshTokenRepo.revokeAllByUserId(rt.userId).transact(transactor) *>
              Async[F].pure(Left(RefreshError.Revoked))
          case Some(rt) if rt.expiresAt.isBefore(Instant.now) =>
            Logger[F].info("Refresh rejected — token expired") *>
              Async[F].pure(Left(RefreshError.Expired))
          case Some(rt) =>
            for
              user <- userRepo.findById(rt.userId).transact(transactor)
              now <- Async[F].delay(Instant.now)
              accessToken <- tokenService.createAccessToken[F](
                CustomClaims(
                  userId = rt.userId.toString,
                  email = user.fold("")(_.email),
                  roles = List(rt.role.getOrElse("DOCTOR")),
                  clinicId = rt.clinicId.map(_.toString),
                  tokenType = "ACCESS"
                )
              )
              newRefreshToken <- OpaqueTokenGenerator.generate[F]
              newExpires = now.plusSeconds(7 * 24 * 60 * 60)
              _ <- (for
                _ <- refreshTokenRepo.revokeByToken(cookieToken)
                _ <- refreshTokenRepo.create(rt.userId, newRefreshToken, newExpires, rt.deviceInfo, rt.ipAddress, rt.clinicId, rt.role)
              yield ()).transact(transactor)
              _ <- Logger[F].info(s"Refresh successful for user=${rt.userId}")
            yield Right(RefreshResult(accessToken, 900, newRefreshToken))
        }.flatTap {
          case Left(RefreshError.Internal(msg)) =>
            Logger[F].error(s"Refresh internal error: $msg")
          case _ => Async[F].unit
        }

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

    private def loadSession(
      userId: Long,
      newAccessToken: Option[String],
      newRefreshToken: Option[String]
    ): F[Either[SessionError, SessionResult]] =
      userRepo.findById(userId).transact(transactor).flatMap {
        case None => Async[F].pure(Left(SessionError.Unauthenticated))
        case Some(user) =>
          userRepo.findWorkspacesByUserId(user.id).transact(transactor).map { workspaces =>
            Right(SessionResult(user, workspaces, newAccessToken, newRefreshToken))
          }
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
