package com.visoris.backend.iam.repository

import cats.effect.{IO, Resource, Sync}
import com.visoris.backend.iam.domain.User
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway

import java.time.Instant

class RefreshTokenRepositorySpec extends CatsEffectSuite:

  private val dbUrl  = sys.env.getOrElse("DB_URL", "jdbc:postgresql://localhost:5432/visoris_db")
  private val dbUser = sys.env.getOrElse("DB_USER", "postgres")
  private val dbPass = sys.env.getOrElse("DB_PASSWORD", "Visoris@123.")

  private val transactorResource: Resource[IO, HikariTransactor[IO]] =
    import doobie.util.ExecutionContexts
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](2)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver", dbUrl, dbUser, dbPass, ce
      )
      _ <- Resource.eval(runMigrations(xa))
    yield xa

  private def runMigrations(xa: HikariTransactor[IO]): IO[Unit] =
    xa.configure { dataSource =>
      Sync[IO].delay {
        Flyway.configure().dataSource(dataSource).load().migrate()
        ()
      }
    }

  private def createTestUser(xa: HikariTransactor[IO]): IO[Long] =
    val userId = System.currentTimeMillis
    val email = s"rt-test-$userId@visoris.com"
    val user = User(
      id = userId,
      email = email,
      passwordHash = "$2a$12$testhash",
      fullName = "Refresh Token Test User",
      professionalDocument = Some(s"CRMV-RT-$userId"),
      createdAt = Instant.now
    )
    sql"""
      INSERT INTO users (id, email, password_hash, full_name, professional_document, created_at)
      VALUES ($userId, $email, ${user.passwordHash}, ${user.fullName}, ${user.professionalDocument}, ${user.createdAt})
    """.update.run.transact(xa).as(userId)

  test("create token and find by token") {
    transactorResource.use { xa =>
      val repo = RefreshTokenRepository.make[IO](xa)
      for
        userId <- createTestUser(xa)
        token = java.util.UUID.randomUUID.toString.replace("-", "")
        expiresAt = Instant.now.plusSeconds(604800)
        _     <- repo.create(userId, token, expiresAt, Some("test-agent"), Some("127.0.0.1")).transact(xa)
        found <- repo.findByToken(token).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.userId, userId)
        assertEquals(found.get.token, token)
        assert(!found.get.isRevoked)
        assertEquals(found.get.deviceInfo, Some("test-agent"))
        assertEquals(found.get.ipAddress, Some("127.0.0.1"))
    }
  }

  test("findByToken should return None for non-existent token") {
    transactorResource.use { xa =>
      val repo = RefreshTokenRepository.make[IO](xa)
      repo.findByToken("nonexistent-token-12345").transact(xa).map { found =>
        assertEquals(found, None)
      }
    }
  }

  test("create token with null device info and ip address") {
    transactorResource.use { xa =>
      val repo = RefreshTokenRepository.make[IO](xa)
      for
        userId <- createTestUser(xa)
        token = java.util.UUID.randomUUID.toString.replace("-", "")
        expiresAt = Instant.now.plusSeconds(604800)
        _     <- repo.create(userId, token, expiresAt, None, None).transact(xa)
        found <- repo.findByToken(token).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.deviceInfo, None)
        assertEquals(found.get.ipAddress, None)
    }
  }

  test("revokeByToken marks token as revoked") {
    transactorResource.use { xa =>
      val repo = RefreshTokenRepository.make[IO](xa)
      for
        userId <- createTestUser(xa)
        token = java.util.UUID.randomUUID.toString.replace("-", "")
        expiresAt = Instant.now.plusSeconds(604800)
        _ <- repo.create(userId, token, expiresAt, Some("agent"), Some("1.2.3.4")).transact(xa)
        found1 <- repo.findByToken(token).transact(xa)
        _ = assert(found1.isDefined && !found1.get.isRevoked)
        rows <- repo.revokeByToken(token).transact(xa)
        found2 <- repo.findByToken(token).transact(xa)
      yield
        assertEquals(rows, 1)
        assert(found2.isDefined)
        assert(found2.get.isRevoked)
    }
  }

  test("revokeAllByUserId revokes all active tokens for a user") {
    transactorResource.use { xa =>
      val repo = RefreshTokenRepository.make[IO](xa)
      for
        userId <- createTestUser(xa)
        tok1 = java.util.UUID.randomUUID.toString.replace("-", "")
        tok2 = java.util.UUID.randomUUID.toString.replace("-", "")
        expiresAt = Instant.now.plusSeconds(604800)
        _ <- repo.create(userId, tok1, expiresAt, None, None).transact(xa)
        _ <- repo.create(userId, tok2, expiresAt, None, None).transact(xa)
        rows <- repo.revokeAllByUserId(userId).transact(xa)
        found1 <- repo.findByToken(tok1).transact(xa)
        found2 <- repo.findByToken(tok2).transact(xa)
      yield
        assertEquals(rows, 2)
        assert(found1.get.isRevoked)
        assert(found2.get.isRevoked)
    }
  }

  test("revokeAllByUserId does not affect other users") {
    transactorResource.use { xa =>
      val repo = RefreshTokenRepository.make[IO](xa)
      for
        user1 <- createTestUser(xa)
        tok1 = java.util.UUID.randomUUID.toString.replace("-", "")
        expiresAt = Instant.now.plusSeconds(604800)
        _ <- repo.create(user1, tok1, expiresAt, None, None).transact(xa)
        rows <- repo.revokeAllByUserId(user1 + 99999).transact(xa)
        found1 <- repo.findByToken(tok1).transact(xa)
      yield
        assertEquals(rows, 0)
        assert(!found1.get.isRevoked)
    }
  }
