package com.visoris.backend.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import org.typelevel.ci.CIString

final case class DatabaseConfig(
  url: String,
  user: String,
  password: Secret[String]
)

final case class CorsConfig(
  allowedOrigins: Set[CIString]
)

final case class AppConfig(
  database: DatabaseConfig,
  jwtSecret: Secret[String],
  cors: CorsConfig,
  enableDocs: Boolean = true
)

object AppConfig:

  private val DevJwtFallback = "changeme-dev-secret"
  private val MinProdJwtSecretLength = 32

  private val appEnvConfig: ConfigValue[Effect, String] =
    env("APP_ENV").default("development").map(_.trim.toLowerCase)

  private def databaseConfig(isProd: Boolean): ConfigValue[Effect, DatabaseConfig] =
    val dbUrl =
      if isProd then env("DB_URL")
      else env("DB_URL").default("jdbc:postgresql://postgres:5432/visoris_db")
    val dbUser =
      if isProd then env("DB_USER")
      else env("DB_USER").default("postgres")
    val dbPass =
      if isProd then env("DB_PASSWORD").secret
      else env("DB_PASSWORD").default("postgres").secret
    (dbUrl, dbUser, dbPass).parMapN(DatabaseConfig.apply)

  private def jwtSecretConfig(isProd: Boolean): ConfigValue[Effect, Secret[String]] =
    if isProd then
      env("JWT_SECRET").flatMap { raw =>
        if raw.length < MinProdJwtSecretLength || raw == DevJwtFallback then
          ConfigValue.failed(
            ConfigError(
              s"JWT_SECRET must be at least $MinProdJwtSecretLength characters and not the development fallback in production"
            )
          )
        else ConfigValue.default(Secret(raw))
      }
    else env("JWT_SECRET").default(DevJwtFallback).secret

  private def enableDocsConfig(isProd: Boolean): ConfigValue[Effect, Boolean] =
    env("ENABLE_DOCS")
      .as[Boolean]
      .default(!isProd)

  private val corsConfig: ConfigValue[Effect, CorsConfig] =
    env("CORS_ALLOWED_ORIGINS")
      .default("http://localhost:3000")
      .map { origins =>
        val parsed = origins
          .split(',')
          .iterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(CIString(_))
          .toSet
        CorsConfig(parsed)
      }

  val config: ConfigValue[Effect, AppConfig] =
    appEnvConfig.flatMap { envName =>
      val isProd = envName == "production" || envName == "prod"
      (
        databaseConfig(isProd),
        jwtSecretConfig(isProd),
        corsConfig,
        enableDocsConfig(isProd)
      ).parMapN(AppConfig.apply)
    }

  def load[F[_] : Async]: F[AppConfig] =
    config.load[F]
