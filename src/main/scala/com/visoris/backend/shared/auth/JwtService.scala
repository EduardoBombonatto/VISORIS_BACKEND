package com.visoris.backend.shared.auth

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import java.time.Instant

final case class CustomClaims(
  userId: String,
  role: String,
  tokenType: String
)

trait TokenService:
  def createAccessToken[F[_]: Sync](claims: CustomClaims): F[String]
  def createRefreshToken[F[_]: Sync](claims: CustomClaims): F[String]
  def validateToken[F[_]: Sync](token: String, expectedType: String): F[Option[CustomClaims]]

object TokenService:
  private val algorithm = JwtAlgorithm.HS256
  private val issuer = "api"
  private val accessTokenTtlSeconds = 900L
  private val refreshTokenTtlSeconds = 604800L

  def make(secretKey: String): TokenService = new TokenService:
    private def encode(claims: CustomClaims, ttlSeconds: Long): String =
      val now = Instant.now.getEpochSecond
      JwtCirce.encode(
        JwtClaim(
          subject = Some(claims.userId),
          issuer = Some(issuer),
          issuedAt = Some(now),
          expiration = Some(now + ttlSeconds)
        ).withContent(claims.asJson.noSpaces),
        secretKey,
        algorithm
      )

    def createAccessToken[F[_]: Sync](claims: CustomClaims): F[String] =
      Sync[F].delay(encode(claims.copy(tokenType = "access"), accessTokenTtlSeconds))

    def createRefreshToken[F[_]: Sync](claims: CustomClaims): F[String] =
      Sync[F].delay(encode(claims.copy(tokenType = "refresh"), refreshTokenTtlSeconds))

    def validateToken[F[_]: Sync](token: String, expectedType: String): F[Option[CustomClaims]] =
      Sync[F].delay {
        JwtCirce.decode(token, secretKey, Seq(algorithm)).toOption.flatMap { claim =>
          decode[CustomClaims](claim.content).toOption.filter(_.tokenType == expectedType)
        }
      }
