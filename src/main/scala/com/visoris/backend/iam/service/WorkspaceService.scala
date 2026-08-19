package com.visoris.backend.iam.service

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.{RefreshToken, Workspace}
import com.visoris.backend.iam.dto.{ActiveWorkspace, ValidationError}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.shared.auth.{CustomClaims, OpaqueTokenGenerator, TokenBlacklist, TokenService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import java.time.Instant

final case class WorkspaceResult(
  accessToken: String,
  expiresIn: Int,
  activeWorkspace: ActiveWorkspace,
  newRefreshToken: String
)

sealed trait WorkspaceError
object WorkspaceError:
  case class Validation(errors: List[ValidationError]) extends WorkspaceError
  case object InvalidBaseToken extends WorkspaceError
  case object MissingRefreshToken extends WorkspaceError
  case object UnauthorizedClinic extends WorkspaceError
  case object RevokedRefreshToken extends WorkspaceError
  case object ExpiredRefreshToken extends WorkspaceError
  case object BaseTokenReused extends WorkspaceError
  case class Internal(message: String) extends WorkspaceError

trait WorkspaceService[F[_]]:
  def selectWorkspace(
    baseToken: String,
    clinicId: Long,
    refreshTokenCookie: Option[String]
  ): F[Either[WorkspaceError, WorkspaceResult]]

object WorkspaceService:
  def make[F[_]: Async: Logger](
    tokenService: TokenService,
    tokenBlacklist: TokenBlacklist[F],
    transactor: Transactor[F],
    userRepo: UserRepository[F],
    refreshTokenRepo: RefreshTokenRepository[F]
  ): WorkspaceService[F] = new WorkspaceService[F]:

    def selectWorkspace(
      baseToken: String,
      clinicId: Long,
      refreshTokenCookie: Option[String]
    ): F[Either[WorkspaceError, WorkspaceResult]] =
      tokenService.validateToken[F](baseToken, "BASE").flatMap {
        case None =>
          Logger[F].info("Workspace selection rejected — invalid or expired base token") *>
            Async[F].pure(Left(WorkspaceError.InvalidBaseToken))

        case Some(claims) =>
          tokenBlacklist.isBlacklisted(baseToken).flatMap {
            case true =>
              Logger[F].warn(s"Workspace selection rejected — base token reused (blacklisted) for user=${claims.userId}") *>
                Async[F].pure(Left(WorkspaceError.BaseTokenReused))

            case false =>
              refreshTokenCookie match
                case None =>
                  Logger[F].info("Workspace selection rejected — missing refresh token cookie") *>
                    Async[F].pure(Left(WorkspaceError.MissingRefreshToken))

                case Some(cookieToken) =>
                  refreshTokenRepo.findByToken(cookieToken).transact(transactor).flatMap {
                    case None =>
                      Logger[F].info("Workspace selection rejected — refresh token unknown") *>
                        Async[F].pure(Left(WorkspaceError.MissingRefreshToken))

                    case Some(rt) if rt.userId.toString != claims.userId =>
                      Logger[F].warn(s"Workspace selection rejected — userId mismatch: base=${claims.userId} refresh=${rt.userId}") *>
                        Async[F].pure(Left(WorkspaceError.InvalidBaseToken))

                    case Some(rt) if rt.isRevoked && !reuseWithinGrace(rt) =>
                      Logger[F].warn(s"Workspace selection rejected — refresh token revoked (possible theft) for user=${rt.userId}") *>
                        refreshTokenRepo.revokeAllByUserId(rt.userId).transact(transactor) *>
                        Async[F].pure(Left(WorkspaceError.RevokedRefreshToken))

                    case Some(rt) if rt.expiresAt.isBefore(Instant.now) =>
                      Logger[F].info("Workspace selection rejected — refresh token expired") *>
                        Async[F].pure(Left(WorkspaceError.ExpiredRefreshToken))

                    case Some(rt) =>
                      if rt.isRevoked then
                        Logger[F].info(s"Workspace selection — refresh token reused within grace window (concurrent rotation) for user=${rt.userId}") *>
                          selectForWorkspace(baseToken, clinicId, claims, rt, cookieToken)
                      else
                        selectForWorkspace(baseToken, clinicId, claims, rt, cookieToken)
                  }
          }
      }.flatTap {
        case Left(WorkspaceError.Internal(msg)) =>
          Logger[F].error(s"Workspace selection internal error: $msg")
        case _ => Async[F].unit
      }

    private val ReuseGraceSeconds = 60

    private def reuseWithinGrace(rt: RefreshToken): Boolean =
      rt.revokedReason.contains("ROTATION") &&
        rt.revokedAt.exists(_.isAfter(Instant.now.minusSeconds(ReuseGraceSeconds)))

    private def selectForWorkspace(
      baseToken: String,
      clinicId: Long,
      claims: CustomClaims,
      rt: RefreshToken,
      cookieToken: String
    ): F[Either[WorkspaceError, WorkspaceResult]] =
      val userId = rt.userId
      userRepo.findMembershipByUserIdAndClinicId(userId, clinicId).transact(transactor).flatMap {
        case None =>
          Logger[F].info(s"Workspace selection rejected — user=$userId not member of clinic=$clinicId") *>
            Async[F].pure(Left(WorkspaceError.UnauthorizedClinic))

        case Some(workspace) =>
          for
            now <- Async[F].delay(Instant.now)
            refreshExpires = now.plusSeconds(7 * 24 * 60 * 60)
            newRefreshTokenPlain <- OpaqueTokenGenerator.generate[F]
            dbResult <- (for
              _ <- refreshTokenRepo.revokeByToken(cookieToken, "ROTATION")
              _ <- refreshTokenRepo.create(
                userId, newRefreshTokenPlain, refreshExpires,
                rt.deviceInfo, rt.ipAddress,
                Some(clinicId), Some(workspace.role)
              )
            yield ()).transact(transactor).attempt
            result <- dbResult match
              case Left(err) =>
                Logger[F].error(err)(s"Workspace selection DB transaction failed for user=$userId") *>
                  Async[F].pure(Left(WorkspaceError.Internal("Erro interno ao processar a seleção de workspace.")): Either[WorkspaceError, WorkspaceResult])
              case Right(()) =>
                Logger[F].info(s"Workspace selection DB transaction committed for user=$userId") *>
                  tokenService.createAccessToken[F](
                    CustomClaims(
                      userId = userId.toString,
                      email = claims.email,
                      roles = List(workspace.role),
                      clinicId = Some(clinicId.toString),
                      tokenType = "ACCESS"
                    )
                  ).attempt.flatMap {
                    case Left(err) =>
                      Logger[F].error(err)(s"Access token generation failed after committed transaction for user=$userId") *>
                        Async[F].pure(Left(WorkspaceError.Internal("Erro interno do servidor. Tente novamente.")): Either[WorkspaceError, WorkspaceResult])
                    case Right(accessToken) =>
                      tokenBlacklist.blacklist(baseToken) *>
                        Logger[F].info(s"Workspace selection successful for user=$userId, clinic=$clinicId, role=${workspace.role}") *>
                        Async[F].pure(Right(WorkspaceResult(
                          accessToken = accessToken,
                          expiresIn = 900,
                          activeWorkspace = ActiveWorkspace(
                            clinicId = clinicId.toString,
                            name = workspace.name,
                            role = workspace.role
                          ),
                          newRefreshToken = newRefreshTokenPlain
                        )))
                  }
          yield result
      }
