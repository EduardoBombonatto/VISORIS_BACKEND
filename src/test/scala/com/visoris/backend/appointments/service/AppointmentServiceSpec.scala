package com.visoris.backend.appointments.service

import cats.effect.IO
import cats.syntax.all.*
import com.visoris.backend.appointments.domain.{Appointment, AppointmentWithDetails, ExamStatus, PaymentStatus, ReportStatus}
import com.visoris.backend.appointments.dto.CreateAppointmentRequest
import com.visoris.backend.appointments.repository.AppointmentRepository
import com.visoris.backend.clinics.domain.Clinic
import com.visoris.backend.clinics.repository.DoctorClinicRepository
import com.visoris.backend.patients.domain.{Patient, PatientType}
import com.visoris.backend.patients.repository.PatientRepository
import doobie.*
import doobie.implicits.*
import io.circe.Json
import java.time.{Instant, LocalDate}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AppointmentServiceSpec extends CatsEffectSuite:

  private given Logger[IO] = NoOpLogger[IO]
  private val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:test_appts_svc;DB_CLOSE_DELAY=-1",
    "sa",
    "",
    None
  )

  private val now = Instant.parse("2026-10-15T14:30:00Z")

  private val sampleAppointment = Appointment(
    id = 500L,
    userId = 1L,
    clinicId = 10L,
    patientId = 20L,
    scheduledAt = now,
    procedureName = "Endoscopia",
    examStatus = ExamStatus.SCHEDULED,
    reportStatus = ReportStatus.PENDING,
    paymentStatus = PaymentStatus.UNPAID,
    price = Some(BigDecimal("400.00")),
    createdAt = now,
    updatedAt = now
  )

  private val sampleDetails = AppointmentWithDetails(
    id = 500L,
    userId = 1L,
    clinicId = 10L,
    patientId = 20L,
    scheduledAt = now,
    procedureName = "Endoscopia",
    examStatus = ExamStatus.SCHEDULED,
    reportStatus = ReportStatus.PENDING,
    paymentStatus = PaymentStatus.UNPAID,
    price = Some(BigDecimal("400.00")),
    createdAt = now,
    updatedAt = now,
    patientName = "Rex",
    patientType = PatientType.PET,
    clientName = "Carlos Silva",
    clinicName = "Clínica Central"
  )

  private def mockAppointmentRepo(
    appointmentOpt: Option[Appointment] = Some(sampleAppointment),
    detailsOpt: Option[AppointmentWithDetails] = Some(sampleDetails)
  ): AppointmentRepository[IO] = new AppointmentRepository[IO]:
    def insert(userId: Long, clinicId: Long, patientId: Long, scheduledAt: Instant, procedureName: String, price: Option[BigDecimal], paymentStatus: PaymentStatus): ConnectionIO[Appointment] =
      sampleAppointment.pure[ConnectionIO]
    def findById(id: Long, userId: Long): ConnectionIO[Option[Appointment]] =
      appointmentOpt.pure[ConnectionIO]
    def findDetailsById(id: Long, userId: Long): ConnectionIO[Option[AppointmentWithDetails]] =
      detailsOpt.pure[ConnectionIO]
    def findAllFiltered(userId: Long, startDate: Option[Instant], endDate: Option[Instant], clinicId: Option[Long]): ConnectionIO[List[AppointmentWithDetails]] =
      detailsOpt.toList.pure[ConnectionIO]
    def updateStatus(id: Long, userId: Long, examStatus: Option[ExamStatus], reportStatus: Option[ReportStatus], paymentStatus: Option[PaymentStatus]): ConnectionIO[Option[Appointment]] =
      appointmentOpt.pure[ConnectionIO]

  private def mockDoctorClinicRepo(isLinkedResult: Boolean = true): DoctorClinicRepository[IO] = new DoctorClinicRepository[IO]:
    def insert(userId: Long, clinicId: Long): ConnectionIO[Unit] = ().pure[ConnectionIO]
    def findByDoctor(userId: Long): ConnectionIO[List[Clinic]] = Nil.pure[ConnectionIO]
    def isLinked(userId: Long, clinicId: Long): ConnectionIO[Boolean] = isLinkedResult.pure[ConnectionIO]

  private def mockPatientRepo(ownerUserIdOpt: Option[Long] = Some(1L)): PatientRepository[IO] = new PatientRepository[IO]:
    def insert(clientId: Long, name: String, patientType: PatientType, birthDate: Option[LocalDate], biologicalDetails: Json): ConnectionIO[Patient] = ???
    def findById(id: Long): ConnectionIO[Option[Patient]] = ???
    def findByClientId(clientId: Long, limit: Long, offset: Long): ConnectionIO[List[Patient]] = ???
    def update(id: Long, name: String, patientType: PatientType, birthDate: Option[LocalDate], biologicalDetails: Json): ConnectionIO[Option[Patient]] = ???
    def delete(id: Long): ConnectionIO[Int] = ???
    def hasAppointments(patientId: Long): ConnectionIO[Boolean] = ???
    def findOwnerUserId(patientId: Long): ConnectionIO[Option[Long]] = ownerUserIdOpt.pure[ConnectionIO]

  test("create returns success when clinic is linked and patient belongs to doctor") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(ownerUserIdOpt = Some(1L)),
      xa
    )

    val request = CreateAppointmentRequest(
      clinicId = 10L,
      patientId = 20L,
      scheduledAt = Right(now),
      procedureName = "Endoscopia",
      price = Some(BigDecimal("400.00"))
    )

    service.create(request, userId = 1L).map { result =>
      assert(result.isRight)
      val resp = result.toOption.get
      assertEquals(resp.id, "500")
      assertEquals(resp.procedureName, "Endoscopia")
      assertEquals(resp.examStatus, "SCHEDULED")
      assertEquals(resp.reportStatus, "PENDING")
      assertEquals(resp.paymentStatus, "UNPAID")
      assertEquals(resp.patientName, "Rex")
      assertEquals(resp.clinicName, "Clínica Central")
    }
  }

  test("create rejects with 404 (NotFound) if clinic is not linked to doctor (IDOR protection)") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = false),
      mockPatientRepo(ownerUserIdOpt = Some(1L)),
      xa
    )

    val request = CreateAppointmentRequest(
      clinicId = 999L,
      patientId = 20L,
      scheduledAt = Right(now),
      procedureName = "Endoscopia",
      price = None
    )

    service.create(request, userId = 1L).map { result =>
      assertEquals(result, Left(AppointmentsError.NotFound))
    }
  }

  test("create rejects with 404 (NotFound) if patient does not belong to doctor (IDOR protection)") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(ownerUserIdOpt = Some(999L)), // different user
      xa
    )

    val request = CreateAppointmentRequest(
      clinicId = 10L,
      patientId = 20L,
      scheduledAt = Right(now),
      procedureName = "Endoscopia",
      price = None
    )

    service.create(request, userId = 1L).map { result =>
      assertEquals(result, Left(AppointmentsError.NotFound))
    }
  }

  test("create rejects with Validation error if price is negative") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(ownerUserIdOpt = Some(1L)),
      xa
    )

    val request = CreateAppointmentRequest(
      clinicId = 10L,
      patientId = 20L,
      scheduledAt = Right(now),
      procedureName = "Endoscopia",
      price = Some(BigDecimal("-10.00"))
    )

    service.create(request, userId = 1L).map { result =>
      assert(result.isLeft)
      result.left.foreach {
        case AppointmentsError.Validation(errs) =>
          assert(errs.exists(_.field == "price"))
        case other => fail(s"Expected validation error, got $other")
      }
    }
  }

  test("listFiltered returns enriched list for doctor") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(),
      xa
    )

    service.listFiltered(userId = 1L, startDate = None, endDate = None, clinicId = None).map { result =>
      assert(result.isRight)
      val list = result.toOption.get
      assertEquals(list.size, 1)
      assertEquals(list.head.clientName, "Carlos Silva")
    }
  }

  test("listFiltered rejects if startDate is after endDate") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(),
      xa
    )

    val start = Instant.parse("2026-10-20T00:00:00Z")
    val end = Instant.parse("2026-10-10T00:00:00Z")

    service.listFiltered(userId = 1L, startDate = Some(start), endDate = Some(end), clinicId = None).map { result =>
      assert(result.isLeft)
      result.left.foreach {
        case AppointmentsError.Validation(errs) =>
          assert(errs.exists(_.field == "startDate"))
        case other => fail(s"Expected validation error, got $other")
      }
    }
  }

  test("updateStatus updates provided statuses") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(),
      xa
    )

    service.updateStatus(id = 500L, userId = 1L, examStatus = Some(ExamStatus.COMPLETED), reportStatus = None, paymentStatus = None).map { result =>
      assert(result.isRight)
      assertEquals(result.toOption.get.id, "500")
    }
  }

  test("updateStatus rejects with 404 when appointment does not exist or belongs to another user") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(appointmentOpt = None),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(),
      xa
    )

    service.updateStatus(id = 999L, userId = 1L, examStatus = Some(ExamStatus.COMPLETED), reportStatus = None, paymentStatus = None).map { result =>
      assertEquals(result, Left(AppointmentsError.NotFound))
    }
  }

  test("updateStatus rejects with Validation when no status is provided") {
    val service = AppointmentService.make[IO](
      mockAppointmentRepo(),
      mockDoctorClinicRepo(isLinkedResult = true),
      mockPatientRepo(),
      xa
    )

    service.updateStatus(id = 500L, userId = 1L, examStatus = None, reportStatus = None, paymentStatus = None).map { result =>
      assert(result.isLeft)
    }
  }
