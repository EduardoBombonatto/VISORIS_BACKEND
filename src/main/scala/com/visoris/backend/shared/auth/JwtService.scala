package com.visoris.backend.shared.auth

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import java.security.SecureRandom
import java.time.Instant

final case class JwtUserClaims(
  userId: Long,
  email: String,
  clinicId: Option[Long],
  roles: List[String]
)

object JwtService:
  private val algorithm = JwtAlgorithm.HS256
  private val accessTokenTtlSeconds = 15 * 60L

  def createAccessToken[F[_]: Sync](
    secret: String,
    claims: JwtUserClaims
  ): F[String] =
    Sync[F].delay {
      val now = Instant.now.getEpochSecond
      val jsonClaims = claims.asJson.noSpaces
      JwtCirce.encode(
        JwtClaim(
          content = jsonClaims,
          expiration = Some(now + accessTokenTtlSeconds),
          issuedAt = Some(now)
        ),
        secret,
        algorithm
      )
    }

  def createRefreshToken[F[_]: Sync](): F[String] =
    Sync[F].delay {
      val random = new SecureRandom()
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      bytes.map("%02x".format(_)).mkString
    }

  def decodeAndValidate[F[_]: Sync](
    secret: String,
    token: String
  ): F[Either[String, JwtUserClaims]] =
    Sync[F].delay {
      JwtCirce.decode(token, secret, Seq(algorithm)).toEither match
        case Right(claim) =>
          decode[JwtUserClaims](claim.content).left.map(_.getMessage)
        case Left(err) =>
          Left(s"Invalid token: ${err.getMessage}")
    }
