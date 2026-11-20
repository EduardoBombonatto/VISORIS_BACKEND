package com.visoris.backend.clinics.repository

import cats.effect.{IO, Resource}
import com.visoris.backend.TestSupport
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import munit.CatsEffectSuite

class DoctorClinicRepositorySpec extends CatsEffectSuite:

  private val transactorResource: Resource[IO, HikariTransactor[IO]] =
    TestSupport.postgresTransactor(4)

  private def insertUser(xa: HikariTransactor[IO]): IO[Long] =
    val email = s"clinic-link-${System.currentTimeMillis}@visoris.com"
    val doc = s"CRMV-CLINK-${System.currentTimeMillis}"
    sql"""INSERT INTO users (email, password_hash, full_name, professional_document)
          VALUES ($email, 'hash', 'Test Doctor', $doc)
          RETURNING id""".query[Long].unique.transact(xa)

  private def linkCount(xa: HikariTransactor[IO], userId: Long, clinicId: Long): IO[Long] =
    sql"""SELECT count(*) FROM doctor_clinics WHERE user_id = $userId AND clinic_id = $clinicId"""
      .query[Long]
      .unique
      .transact(xa)

  test("insert creates a link and findByDoctor returns the linked clinics") {
    transactorResource.use { xa =>
      val clinicRepo = ClinicRepository.make[IO](xa)
      val linkRepo = DoctorClinicRepository.make[IO](xa)
      for
        userId <- insertUser(xa)
        clinic1 <- clinicRepo.insertClinicIfAbsent("Clínica A", None, None, None).transact(xa)
        clinic2 <- clinicRepo.insertClinicIfAbsent("Clínica B", None, None, None).transact(xa)
        _ <- linkRepo.insert(userId, clinic1.get.id).transact(xa)
        _ <- linkRepo.insert(userId, clinic2.get.id).transact(xa)
        linked <- linkRepo.findByDoctor(userId).transact(xa)
      yield
        assertEquals(linked.map(_.id).toSet, Set(clinic1.get.id, clinic2.get.id))
        assertEquals(linked.map(_.name).toSet, Set("Clínica A", "Clínica B"))
    }
  }

  test("re-inserting the same link is a no-op (ON CONFLICT DO NOTHING)") {
    transactorResource.use { xa =>
      val clinicRepo = ClinicRepository.make[IO](xa)
      val linkRepo = DoctorClinicRepository.make[IO](xa)
      for
        userId <- insertUser(xa)
        clinic <- clinicRepo.insertClinicIfAbsent("Clínica Idempotente", None, None, None).transact(xa)
        _ <- linkRepo.insert(userId, clinic.get.id).transact(xa)
        _ <- linkRepo.insert(userId, clinic.get.id).transact(xa)
        count <- linkCount(xa, userId, clinic.get.id)
      yield
        assertEquals(count, 1L)
    }
  }

  test("findByDoctor returns only the doctor's own linked clinics") {
    transactorResource.use { xa =>
      val clinicRepo = ClinicRepository.make[IO](xa)
      val linkRepo = DoctorClinicRepository.make[IO](xa)
      for
        doctorA <- insertUser(xa)
        doctorB <- insertUser(xa)
        clinicA <- clinicRepo.insertClinicIfAbsent("Clínica de A", None, None, None).transact(xa)
        clinicB <- clinicRepo.insertClinicIfAbsent("Clínica de B", None, None, None).transact(xa)
        _ <- linkRepo.insert(doctorA, clinicA.get.id).transact(xa)
        _ <- linkRepo.insert(doctorB, clinicB.get.id).transact(xa)
        linkedA <- linkRepo.findByDoctor(doctorA).transact(xa)
        linkedB <- linkRepo.findByDoctor(doctorB).transact(xa)
      yield
        assertEquals(linkedA.map(_.id), List(clinicA.get.id))
        assertEquals(linkedB.map(_.id), List(clinicB.get.id))
        assert(!linkedA.exists(_.id == clinicB.get.id))
        assert(!linkedB.exists(_.id == clinicA.get.id))
    }
  }
