package com.visoris.backend.iam.infrastructure

import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import com.visoris.backend.iam.domain.User
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway

import java.time.Instant

class UserRepositoryImplSpec extends CatsEffectSuite:

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

  private val repo = UserRepositoryImpl()

  test("create user and find by email") {
    transactorResource.use { xa =>
      val email = s"test-${System.currentTimeMillis()}@visoris.com"
      val user = User(
        id = System.currentTimeMillis,
        email = email,
        passwordHash = "$2a$12$hashedpassword",
        fullName = "Test User",
        professionalDocument = Some(s"DOC-${System.currentTimeMillis}"),
        createdAt = Instant.now
      )
      for
        _     <- repo.create(user).transact(xa)
        found <- repo.findByEmail(email).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.email, email.toLowerCase)
        assertEquals(found.get.fullName, "Test User")
    }
  }

  test("findByEmail should be case-insensitive") {
    transactorResource.use { xa =>
      val email = s"CaseTest-${System.currentTimeMillis()}@Visoris.com"
      val user = User(
        id = System.currentTimeMillis,
        email = email.toLowerCase,
        passwordHash = "$2a$12$hashedpassword",
        fullName = "Case Test User",
        professionalDocument = Some(s"DOC-CASE-${System.currentTimeMillis}"),
        createdAt = Instant.now
      )
      for
        _     <- repo.create(user).transact(xa)
        found <- repo.findByEmail(email).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.email, email.toLowerCase)
    }
  }

  test("findByProfessionalDocument should return user when doc exists") {
    transactorResource.use { xa =>
      val doc = s"CRMV-TEST-${System.currentTimeMillis}"
      val user = User(
        id = System.currentTimeMillis,
        email = s"doc-test-${System.currentTimeMillis}@visoris.com",
        passwordHash = "$2a$12$hashedpassword",
        fullName = "Doc Test User",
        professionalDocument = Some(doc),
        createdAt = Instant.now
      )
      for
        _     <- repo.create(user).transact(xa)
        found <- repo.findByProfessionalDocument(doc).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.professionalDocument, Some(doc))
    }
  }

  test("findByProfessionalDocument should return None for non-existent doc") {
    transactorResource.use { xa =>
      repo.findByProfessionalDocument(s"NONEXISTENT-${System.currentTimeMillis}").transact(xa).map { found =>
        assertEquals(found, None)
      }
    }
  }

  test("duplicate email insert should raise unique violation") {
    transactorResource.use { xa =>
      val email = s"dup-${System.currentTimeMillis}@visoris.com"
      val docBase = s"DOC-DUP-${System.currentTimeMillis}"
      val user1 = User(
        id = System.currentTimeMillis,
        email = email,
        passwordHash = "$2a$12$hashedpassword",
        fullName = "Dup User 1",
        professionalDocument = Some(s"$docBase-1"),
        createdAt = Instant.now
      )
      val user2 = user1.copy(
        id = System.currentTimeMillis + 1,
        professionalDocument = Some(s"$docBase-2")
      )
      for
        _       <- repo.create(user1).transact(xa)
        attempt <- repo.create(user2).transact(xa).attempt
      yield attempt match
        case Left(_)  => assert(true)
        case Right(_) => fail("Expected unique violation on duplicate email")
    }
  }

  test("create user without professional document") {
    transactorResource.use { xa =>
      val email = s"nodoc-${System.currentTimeMillis}@visoris.com"
      val user = User(
        id = System.currentTimeMillis,
        email = email,
        passwordHash = "$2a$12$hashedpassword",
        fullName = "No Doc User",
        professionalDocument = None,
        createdAt = Instant.now
      )
      for
        _     <- repo.create(user).transact(xa)
        found <- repo.findByEmail(email).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.professionalDocument, None)
    }
  }
