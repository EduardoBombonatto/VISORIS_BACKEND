package com.visoris.backend.config

import cats.effect.{Async, Resource, Sync}
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway

object Database:
  private val PoolSize = 16

  def makeTransactor[F[_] : Async](
    url: String,
    user: String,
    pass: String
  ): Resource[F, HikariTransactor[F]] =
    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName("org.postgresql.Driver")
    hikariConfig.setJdbcUrl(url)
    hikariConfig.setUsername(user)
    hikariConfig.setPassword(pass)
    hikariConfig.setMaximumPoolSize(PoolSize)
    hikariConfig.setMinimumIdle(4)
    hikariConfig.setConnectionTimeout(10000L)
    hikariConfig.setIdleTimeout(300000L)
    hikariConfig.setMaxLifetime(1200000L)
    hikariConfig.setLeakDetectionThreshold(30000L)
    HikariTransactor.fromHikariConfig[F](hikariConfig)

  def makeTransactor[F[_] : Async](config: DatabaseConfig): Resource[F, HikariTransactor[F]] =
    makeTransactor(config.url, config.user, config.password.value)

  def runMigrations[F[_] : Sync](transactor: HikariTransactor[F]): F[Unit] =
    transactor.configure { dataSource =>
      Sync[F].blocking {
        val flyway = Flyway.configure().dataSource(dataSource).load()
        flyway.migrate()
        ()
      }
    }
