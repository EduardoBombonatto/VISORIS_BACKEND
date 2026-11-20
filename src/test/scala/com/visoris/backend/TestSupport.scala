package com.visoris.backend

import cats.effect.{IO, Resource, Sync}
import com.visoris.backend.iam.domain.User
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.Flyway

object TestSupport:

  private lazy val dotEnvValues: Map[String, String] =
    val path = Paths.get(".env")
    if Files.isRegularFile(path) then
      Files.readAllLines(path).toArray.toList.collect { case line: String =>
        line.trim
      }.filter(l => l.nonEmpty && !l.startsWith("#") && l.contains("=")).map { line =>
        val idx = line.indexOf('=')
        val k = line.substring(0, idx).trim
        val v = line.substring(idx + 1).trim.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
        k -> v
      }.toMap
    else Map.empty

  private def envOrDotEnv(key: String, fallback: String): String =
    sys.env.get(key).orElse(dotEnvValues.get(key)).getOrElse(fallback)

  def dbUrl: String =
    val raw = envOrDotEnv("DB_URL", "jdbc:postgresql://localhost:5432/visoris_db")
    raw.replace("://postgres:", "://localhost:")

  def dbUser: String = envOrDotEnv("DB_USER", "postgres")
  def dbCredential: String = envOrDotEnv("DB_PASSWORD", "postgres")

  def dynamicSecret(): String = s"Test@${UUID.randomUUID().toString.take(12)}9!"
  def dynamicHash(): String = s"$$2a$$12$$${UUID.randomUUID().toString.replace("-", "")}"

  def sampleDoctor(createdAt: Instant, id: Long = 100L): User =
    User(
      id = id,
      email = "doctor@example.com",
      passwordHash = dynamicHash(),
      fullName = "Dr. Silva",
      professionalDocument = Some("CRM/SP 123456"),
      createdAt = createdAt
    )

  def postgresTransactor(poolSize: Int = 2): Resource[IO, HikariTransactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        dbUrl,
        dbUser,
        dbCredential,
        ce
      )
      _ <- Resource.eval(
        xa.configure { dataSource =>
          Sync[IO].blocking {
            Flyway.configure().dataSource(dataSource).load().migrate()
            ()
          }
        }
      )
    yield xa
