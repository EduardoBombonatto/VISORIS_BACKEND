package com.visoris.backend.iam.repository

import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import com.visoris.backend.iam.domain.User
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway

import java.time.Instant

class UserRepositorySpec extends CatsEffectSuite:

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

  test("findWorkspacesByUserId returns workspaces for user with multiple clinics") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val userId = System.currentTimeMillis
      val email = s"ws-test-${userId}@visoris.com"
      val doc = s"DOC-WS-$userId"
      val clinicId1 = userId + 1
      val clinicId2 = userId + 2
      for
        _ <- sql"INSERT INTO users (id, email, password_hash, full_name, professional_document) VALUES ($userId, $email, 'hash', 'WS Test', $doc)".update.run.transact(xa)
        _ <- sql"INSERT INTO clinics (id, name) VALUES ($clinicId1, 'Clinica Alpha')".update.run.transact(xa)
        _ <- sql"INSERT INTO clinics (id, name) VALUES ($clinicId2, 'Clinica Beta')".update.run.transact(xa)
        _ <- sql"INSERT INTO doctor_clinics (user_id, clinic_id, role) VALUES ($userId, $clinicId1, 'OWNER')".update.run.transact(xa)
        _ <- sql"INSERT INTO doctor_clinics (user_id, clinic_id, role) VALUES ($userId, $clinicId2, 'DOCTOR')".update.run.transact(xa)
        workspaces <- repo.findWorkspacesByUserId(userId).transact(xa)
      yield
        assertEquals(workspaces.length, 2)
        assertEquals(workspaces.head.name, "Clinica Alpha")
        assertEquals(workspaces.head.role, "OWNER")
        assertEquals(workspaces(1).name, "Clinica Beta")
        assertEquals(workspaces(1).role, "DOCTOR")
    }
  }

  test("findWorkspacesByUserId returns empty list for user with no clinics") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val userId = System.currentTimeMillis
      val email = s"no-ws-${userId}@visoris.com"
      val doc = s"DOC-NOWS-$userId"
      for
        _ <- sql"INSERT INTO users (id, email, password_hash, full_name, professional_document) VALUES ($userId, $email, 'hash', 'No WS', $doc)".update.run.transact(xa)
        workspaces <- repo.findWorkspacesByUserId(userId).transact(xa)
      yield
        assertEquals(workspaces, List.empty)
    }
  }

  test("findWorkspacesByUserId returns single workspace for user with one clinic") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val userId = System.currentTimeMillis
      val email = s"one-ws-${userId}@visoris.com"
      val doc = s"DOC-ONEWS-$userId"
      val clinicId = userId + 1
      for
        _ <- sql"INSERT INTO users (id, email, password_hash, full_name, professional_document) VALUES ($userId, $email, 'hash', 'One WS', $doc)".update.run.transact(xa)
        _ <- sql"INSERT INTO clinics (id, name) VALUES ($clinicId, 'Solo Clinic')".update.run.transact(xa)
        _ <- sql"INSERT INTO doctor_clinics (user_id, clinic_id, role) VALUES ($userId, $clinicId, 'DOCTOR')".update.run.transact(xa)
        workspaces <- repo.findWorkspacesByUserId(userId).transact(xa)
      yield
        assertEquals(workspaces.length, 1)
        assertEquals(workspaces.head.clinicId, clinicId)
        assertEquals(workspaces.head.name, "Solo Clinic")
        assertEquals(workspaces.head.role, "DOCTOR")
    }
  }

  test("findWorkspacesByUserId returns empty for non-existent user") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      repo.findWorkspacesByUserId(Long.MaxValue).transact(xa).map { workspaces =>
        assertEquals(workspaces, List.empty)
      }
    }
  }

  test("findMembershipByUserIdAndClinicId returns workspace when membership exists") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val userId = System.currentTimeMillis
      val email = s"memb-${userId}@visoris.com"
      val doc = s"DOC-MEMB-$userId"
      val clinicId = userId + 1
      for
        _ <- sql"INSERT INTO users (id, email, password_hash, full_name, professional_document) VALUES ($userId, $email, 'hash', 'Memb Test', $doc)".update.run.transact(xa)
        _ <- sql"INSERT INTO clinics (id, name) VALUES ($clinicId, 'Memb Clinic')".update.run.transact(xa)
        _ <- sql"INSERT INTO doctor_clinics (user_id, clinic_id, role) VALUES ($userId, $clinicId, 'OWNER')".update.run.transact(xa)
        result <- repo.findMembershipByUserIdAndClinicId(userId, clinicId).transact(xa)
      yield
        assert(result.isDefined)
        assertEquals(result.get.clinicId, clinicId)
        assertEquals(result.get.name, "Memb Clinic")
        assertEquals(result.get.role, "OWNER")
    }
  }

  test("findMembershipByUserIdAndClinicId returns None when user is not a member") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val userId = System.currentTimeMillis
      val email = s"nomemb-${userId}@visoris.com"
      val doc = s"DOC-NOMEMB-$userId"
      for
        _ <- sql"INSERT INTO users (id, email, password_hash, full_name, professional_document) VALUES ($userId, $email, 'hash', 'No Memb', $doc)".update.run.transact(xa)
        result <- repo.findMembershipByUserIdAndClinicId(userId, Long.MaxValue).transact(xa)
      yield
        assertEquals(result, None)
    }
  }

  test("findMembershipByUserIdAndClinicId returns None when clinic does not exist") {
    transactorResource.use { xa =>
      val repo = UserRepository.make[IO](xa)
      val userId = System.currentTimeMillis
      val email = s"noclinic-${userId}@visoris.com"
      val doc = s"DOC-NOCLINIC-$userId"
      for
        _ <- sql"INSERT INTO users (id, email, password_hash, full_name, professional_document) VALUES ($userId, $email, 'hash', 'No Clinic', $doc)".update.run.transact(xa)
        result <- repo.findMembershipByUserIdAndClinicId(userId, Long.MaxValue).transact(xa)
      yield
        assertEquals(result, None)
    }
  }
