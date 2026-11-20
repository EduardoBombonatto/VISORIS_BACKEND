package com.visoris.backend.iam.repository

import cats.syntax.all.*
import com.visoris.backend.iam.domain.RefreshToken
import com.visoris.backend.shared.utils.SecurityUtils
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor
import java.time.Instant

trait RefreshTokenRepository[F[_]]:
  def create(userId: Long, token: String, expiresAt: Instant, deviceInfo: Option[String], ipAddress: Option[String]): ConnectionIO[Unit]
  def findByToken(token: String): ConnectionIO[Option[RefreshToken]]
  def findByTokenForUpdate(token: String): ConnectionIO[Option[RefreshToken]]
  def revokeByToken(token: String, reason: String): ConnectionIO[Int]
  def revokeAllByUserId(userId: Long): ConnectionIO[Int]
  def deleteExpired(): ConnectionIO[Int]

object RefreshTokenRepository:
  def make[F[_]]: RefreshTokenRepository[F] = new RefreshTokenRepository[F]:
    def create(userId: Long, token: String, expiresAt: Instant, deviceInfo: Option[String], ipAddress: Option[String]): ConnectionIO[Unit] =
      val hashed = SecurityUtils.sha256Hex(token)
      sql"""
        INSERT INTO refresh_tokens (user_id, token, expires_at, is_revoked, device_info, ip_address)
        VALUES ($userId, $hashed, $expiresAt, false, $deviceInfo, $ipAddress)
      """.update.run.void

    def findByToken(token: String): ConnectionIO[Option[RefreshToken]] =
      val hashed = SecurityUtils.sha256Hex(token)
      sql"""
        SELECT id, user_id, token, expires_at, is_revoked, revoked_at, revoked_reason, device_info, ip_address, created_at
        FROM refresh_tokens WHERE token = $hashed
      """.query[RefreshToken].option

    def findByTokenForUpdate(token: String): ConnectionIO[Option[RefreshToken]] =
      val hashed = SecurityUtils.sha256Hex(token)
      sql"""
        SELECT id, user_id, token, expires_at, is_revoked, revoked_at, revoked_reason, device_info, ip_address, created_at
        FROM refresh_tokens WHERE token = $hashed FOR UPDATE
      """.query[RefreshToken].option

    def revokeByToken(token: String, reason: String): ConnectionIO[Int] =
      val hashed = SecurityUtils.sha256Hex(token)
      sql"""
        UPDATE refresh_tokens SET is_revoked = true, revoked_at = now(), revoked_reason = $reason WHERE token = $hashed
      """.update.run

    def revokeAllByUserId(userId: Long): ConnectionIO[Int] =
      sql"""
        UPDATE refresh_tokens SET is_revoked = true, revoked_at = now(), revoked_reason = 'THEFT'
        WHERE user_id = $userId AND is_revoked = false
      """.update.run

    def deleteExpired(): ConnectionIO[Int] =
      sql"""
        DELETE FROM refresh_tokens WHERE expires_at < now()
      """.update.run

  def make[F[_]](@scala.annotation.unused transactor: Transactor[F]): RefreshTokenRepository[F] = make[F]
