package com.visoris.backend.iam.repository

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.RefreshToken
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor
import java.time.Instant

trait RefreshTokenRepository[F[_]]:
  def create(userId: Long, token: String, expiresAt: Instant, deviceInfo: Option[String], ipAddress: Option[String]): F[Unit]
  def findByToken(token: String): F[Option[RefreshToken]]

object RefreshTokenRepository:
  def make[F[_]: Async](transactor: Transactor[F]): RefreshTokenRepository[F] = new RefreshTokenRepository[F]:
    def create(userId: Long, token: String, expiresAt: Instant, deviceInfo: Option[String], ipAddress: Option[String]): F[Unit] =
      sql"""
        INSERT INTO refresh_tokens (user_id, token, expires_at, is_revoked, device_info, ip_address)
        VALUES ($userId, $token, $expiresAt, false, $deviceInfo, $ipAddress)
      """.update.run.void.transact(transactor)

    def findByToken(token: String): F[Option[RefreshToken]] =
      sql"""
        SELECT id, user_id, token, expires_at, is_revoked, device_info, ip_address, created_at
        FROM refresh_tokens WHERE token = $token
      """.query[RefreshToken].option.transact(transactor)
