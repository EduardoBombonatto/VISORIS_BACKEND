package com.visoris.backend.patients.service

import cats.effect.IO
import cats.syntax.all.*
import com.visoris.backend.clinics.domain.Clinic
import com.visoris.backend.clinics.repository.DoctorClinicRepository
import com.visoris.backend.patients.domain.{Client, Patient, PatientType}
import com.visoris.backend.patients.dto.PatientRequest
import com.visoris.backend.patients.repository.{ClientRepository, PatientRepository}
import doobie.*
import doobie.implicits.*
import io.circe.Json
import java.time.{Instant, LocalDate}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class PatientServiceSpec extends CatsEffectSuite:

  private given Logger[IO] = NoOpLogger[IO]
  private val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:test_patients_svc;DB_CLOSE_DELAY=-1",
    "sa",
    "",
    None
  )

  private val now = Instant.now()
  private val sampleClient = Client(
    id = 20L,
    clinicId = 10L,
    fullName = "Maria Souza",
    documentCpf = Some("12345678909"),
    email = Some("maria@example.com"),
    phone = Some("11987654321"),
    createdAt = now,
    updatedAt = now
  )

  private val samplePatient = Patient(
    id = 100L,
    clientId = Some(20L),
    name = "Rex",
    patientType = PatientType.PET,
    birthDate = Some(LocalDate.of(2022, 5, 10)),
    biologicalDetails = Json.obj("breed" -> Json.fromString("Golden")),
    createdAt = now
  )

  private def mockDoctorClinicRepo(isLinkedResult: Boolean): DoctorClinicRepository[IO] =
    new DoctorClinicRepository[IO]:
      def insert(userId: Long, clinicId: Long): ConnectionIO[Unit] = ().pure[ConnectionIO]
      def findByDoctor(userId: Long): ConnectionIO[List[Clinic]] = List.empty.pure[ConnectionIO]
      def isLinked(userId: Long, clinicId: Long): ConnectionIO[Boolean] = isLinkedResult.pure[ConnectionIO]

  private def mockClientRepo(clientOpt: Option[Client] = Some(sampleClient)): ClientRepository[IO] =
    new ClientRepository[IO]:
      def insert(clinicId: Long, fullName: String, documentCpf: Option[String], email: Option[String], phone: Option[String]): ConnectionIO[Client] =
        sampleClient.pure[ConnectionIO]
      def findById(id: Long): ConnectionIO[Option[Client]] = clientOpt.pure[ConnectionIO]
      def findByClinicId(clinicId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]] = List(sampleClient).pure[ConnectionIO]
      def findByClinicIdAndCpf(clinicId: Long, documentCpf: String): ConnectionIO[Option[Client]] = None.pure[ConnectionIO]

  private def mockPatientRepo(
    patient: Patient = samplePatient,
    list: List[Patient] = List(samplePatient)
  ): PatientRepository[IO] =
    new PatientRepository[IO]:
      def insert(clientId: Long, name: String, patientType: PatientType, birthDate: Option[LocalDate], biologicalDetails: Json): ConnectionIO[Patient] =
        patient.pure[ConnectionIO]
      def findById(id: Long): ConnectionIO[Option[Patient]] = Some(patient).pure[ConnectionIO]
      def findByClientId(clientId: Long, limit: Long, offset: Long): ConnectionIO[List[Patient]] = list.pure[ConnectionIO]

  test("create returns Validation error when name is empty or birthDate is future") {
    val service = PatientService.make[IO](mockPatientRepo(), mockClientRepo(), mockDoctorClinicRepo(true), xa)
    val req = PatientRequest(
      clientId = 20L,
      name = "",
      patientType = Right(PatientType.PET),
      birthDate = Some(LocalDate.now().plusDays(10)),
      biologicalDetails = Json.obj()
    )

    service.create(req, userId = 1L).map {
      case Left(PatientsError.Validation(errors)) =>
        assert(errors.exists(_.field == "name"))
        assert(errors.exists(_.field == "birth_date"))
      case other => fail(s"Expected validation errors, got $other")
    }
  }

  test("create returns NotFound (404) when client does not exist") {
    val service = PatientService.make[IO](mockPatientRepo(), mockClientRepo(clientOpt = None), mockDoctorClinicRepo(true), xa)
    val req = PatientRequest(20L, "Rex", Right(PatientType.PET), Some(LocalDate.of(2022, 1, 1)), Json.obj())

    service.create(req, userId = 1L).map { res =>
      assertEquals(res, Left(PatientsError.NotFound))
    }
  }

  test("create returns NotFound (404) when client's clinic is not linked to doctor") {
    val service = PatientService.make[IO](mockPatientRepo(), mockClientRepo(), mockDoctorClinicRepo(false), xa)
    val req = PatientRequest(20L, "Rex", Right(PatientType.PET), Some(LocalDate.of(2022, 1, 1)), Json.obj())

    service.create(req, userId = 1L).map { res =>
      assertEquals(res, Left(PatientsError.NotFound))
    }
  }

  test("create returns Patient on success") {
    val service = PatientService.make[IO](mockPatientRepo(), mockClientRepo(), mockDoctorClinicRepo(true), xa)
    val req = PatientRequest(20L, "Rex", Right(PatientType.PET), Some(LocalDate.of(2022, 5, 10)), Json.obj())

    service.create(req, userId = 1L).map { res =>
      assertEquals(res, Right(samplePatient))
    }
  }

  test("listByClient returns NotFound (404) when client does not exist or clinic unlinked") {
    val service = PatientService.make[IO](mockPatientRepo(), mockClientRepo(clientOpt = None), mockDoctorClinicRepo(true), xa)
    service.listByClient(20L, userId = 1L, limit = 50L, offset = 0L).map { res =>
      assertEquals(res, Left(PatientsError.NotFound))
    }
  }

  test("listByClient returns patients list on success") {
    val service = PatientService.make[IO](mockPatientRepo(), mockClientRepo(), mockDoctorClinicRepo(true), xa)
    service.listByClient(20L, userId = 1L, limit = 50L, offset = 0L).map { res =>
      assertEquals(res, Right(List(samplePatient)))
    }
  }
