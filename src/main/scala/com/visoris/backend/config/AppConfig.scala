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
  cors: CorsConfig
)

object AppConfig:

  private val databaseConfig: ConfigValue[Effect, DatabaseConfig] =
    (
      env("DB_URL").default("jdbc:postgresql://postgres:5432/visoris_db"),
      env("DB_USER").default("postgres"),
      env("DB_PASSWORD").default("postgres").secret
    ).parMapN(DatabaseConfig.apply)

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
    (
      databaseConfig,
      env("JWT_SECRET").default("changeme-dev-secret").secret,
      corsConfig
    ).parMapN(AppConfig.apply)

  def load[F[_] : Async]: F[AppConfig] =
    config.load[F]
