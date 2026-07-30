package com.visoris.backend.config

import cats.effect.Async
import cats.effect.Resource
import cats.effect.Sync
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database:
  def makeTransactor[F[_] : Async](
                                    url: String,
                                    user: String,
                                    pass: String
                                  ): Resource[F, HikariTransactor[F]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[F](32)
      transactor <- HikariTransactor.newHikariTransactor[F](
        driverClassName = "org.postgresql.Driver",
        url = url,
        user = user,
        pass = pass,
        connectEC = ce
      )
    } yield transactor

  def runMigrations[F[_] : Sync](transactor: HikariTransactor[F]): F[Unit] =
    transactor.configure { dataSource =>
      Sync[F].delay {
        val flyway = Flyway.configure().dataSource(dataSource).load()
        flyway.migrate()
        ()
      }
    }
