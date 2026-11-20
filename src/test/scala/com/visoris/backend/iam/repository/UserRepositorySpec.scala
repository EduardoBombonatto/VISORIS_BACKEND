package com.visoris.backend.iam.repository

import cats.effect.{IO, Resource}
import com.visoris.backend.TestSupport
import com.visoris.backend.iam.domain.User
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import munit.CatsEffectSuite

import java.time.Instant

class UserRepositorySpec extends CatsEffectSuite:

  private val transactorResource: Resource[IO, HikariTransactor[IO]] =
    TestSupport.postgresTransactor(2)

  private def assertCreate(repo: UserRepository[IO], xa: HikariTransactor[IO], user: User): IO[Unit] =
    repo.create(user).transact(xa).void

  test("create user and find by email") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val email = s"test-${System.currentTimeMillis}@visoris.com"
      val user = User(
        id = System.currentTimeMillis,
        email = email,
        passwordHash = "$2a$12$hashedpassword",
        fullName = "Test User",
        professionalDocument = Some(s"DOC-${System.currentTimeMillis}"),
        createdAt = Instant.now
      )
      for
        _     <- assertCreate(repo, xa, user)
        found <- repo.findByEmail(email).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.email, email.toLowerCase)
        assertEquals(found.get.fullName, "Test User")
    }
  }

  test("findByEmail should be case-insensitive") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val email = s"CaseTest-${System.currentTimeMillis}@Visoris.com"
      val user = User(
        id = System.currentTimeMillis,
        email = email.toLowerCase,
        passwordHash = "$2a$12$hashedpassword",
        fullName = "Case Test User",
        professionalDocument = Some(s"DOC-CASE-${System.currentTimeMillis}"),
        createdAt = Instant.now
      )
      for
        _     <- assertCreate(repo, xa, user)
        found <- repo.findByEmail(email).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.email, email.toLowerCase)
    }
  }

  test("findByProfessionalDocument should return user when doc exists") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
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
        _     <- assertCreate(repo, xa, user)
        found <- repo.findByProfessionalDocument(doc).transact(xa)
      yield
        assert(found.isDefined)
        assertEquals(found.get.professionalDocument, Some(doc))
    }
  }

  test("findByProfessionalDocument should return None for non-existent doc") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      repo.findByProfessionalDocument(s"NONEXISTENT-${System.currentTimeMillis}").transact(xa).map { found =>
        assertEquals(found, None)
      }
    }
  }
