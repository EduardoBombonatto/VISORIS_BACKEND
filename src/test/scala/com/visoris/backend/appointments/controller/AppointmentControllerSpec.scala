package com.visoris.backend.appointments.controller

import cats.effect.IO
import com.visoris.backend.appointments.domain.{ExamStatus, PaymentStatus, ReportStatus}
import com.visoris.backend.appointments.dto.{AppointmentResponse, CreateAppointmentRequest, ValidationError}
import com.visoris.backend.appointments.service.{AppointmentService, AppointmentsError}
import com.visoris.backend.iam.domain.User
import io.circe.Json
import java.time.Instant
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

class AppointmentControllerSpec extends CatsEffectSuite:

  private val now = Instant.parse("2026-10-15T14:30:00Z")
  private val testUser = User(
    id = 100L,
    email = "doctor@example.com",
    passwordHash = "hash",
    fullName = "Dr. Silva",
    professionalDocument = Some("CRM/SP 123456"),
    createdAt = now
  )

  private val sampleResponse = AppointmentResponse(
    id = "500",
    userId = "100",
    clinicId = "10",
    patientId = "20",
    scheduledAt = now,
    procedureName = "Endoscopia Digestiva",
    examStatus = "SCHEDULED",
    reportStatus = "PENDING",
    paymentStatus = "UNPAID",
    price = Some(BigDecimal("450.00")),
    createdAt = now,
    updatedAt = now,
    patientName = "Rex",
    patientType = "PET",
    clientName = "Carlos Silva",
    clinicName = "Clínica Central"
  )

  private def mockService(
    createResult: Either[AppointmentsError, AppointmentResponse] = Right(sampleResponse),
    listResult: Either[AppointmentsError, List[AppointmentResponse]] = Right(List(sampleResponse)),
    updateResult: Either[AppointmentsError, AppointmentResponse] = Right(sampleResponse)
  ): AppointmentService[IO] = new AppointmentService[IO]:
    def create(request: CreateAppointmentRequest, userId: Long): IO[Either[AppointmentsError, AppointmentResponse]] =
      IO.pure(createResult)
    def listFiltered(userId: Long, startDate: Option[Instant], endDate: Option[Instant], clinicId: Option[Long]): IO[Either[AppointmentsError, List[AppointmentResponse]]] =
      IO.pure(listResult)
    def updateStatus(id: Long, userId: Long, examStatus: Option[ExamStatus], reportStatus: Option[ReportStatus], paymentStatus: Option[PaymentStatus]): IO[Either[AppointmentsError, AppointmentResponse]] =
      IO.pure(updateResult)

  test("POST /api/v1/appointments returns 201 Created on valid input") {
    val service = mockService()
    val routes = AppointmentController.routes[IO](service)

    val body = Json.obj(
      "clinic_id" -> Json.fromLong(10L),
      "patient_id" -> Json.fromLong(20L),
      "scheduled_at" -> Json.fromString("2026-10-15T14:30:00Z"),
      "procedure_name" -> Json.fromString("Endoscopia Digestiva"),
      "price" -> Json.fromBigDecimal(BigDecimal("450.00"))
    )

    val req = Request[IO](Method.POST, uri"/api/v1/appointments").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Created)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("httpcode").as[Int], Right(201))
          assertEquals(json.hcursor.downField("data").downField("appointment").downField("id").as[String], Right("500"))
        }
      case None => IO(fail("Route not matched"))
    }
  }

  test("POST /api/v1/appointments returns 404 Not Found when clinic or patient not found / not owned (IDOR)") {
    val service = mockService(createResult = Left(AppointmentsError.NotFound))
    val routes = AppointmentController.routes[IO](service)

    val body = Json.obj(
      "clinic_id" -> Json.fromLong(999L),
      "patient_id" -> Json.fromLong(20L),
      "scheduled_at" -> Json.fromString("2026-10-15T14:30:00Z"),
      "procedure_name" -> Json.fromString("Endoscopia Digestiva")
    )

    val req = Request[IO](Method.POST, uri"/api/v1/appointments").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.NotFound)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(true))
          assertEquals(json.hcursor.downField("httpcode").as[Int], Right(404))
        }
      case None => IO(fail("Route not matched"))
    }
  }

  test("POST /api/v1/appointments returns 400 Bad Request on validation error") {
    val service = mockService(createResult = Left(AppointmentsError.Validation(List(ValidationError("price", "O preço não pode ser negativo.")))))
    val routes = AppointmentController.routes[IO](service)

    val body = Json.obj(
      "clinic_id" -> Json.fromLong(10L),
      "patient_id" -> Json.fromLong(20L),
      "scheduled_at" -> Json.fromString("2026-10-15T14:30:00Z"),
      "procedure_name" -> Json.fromString("Endoscopia"),
      "price" -> Json.fromBigDecimal(BigDecimal("-10.00"))
    )

    val req = Request[IO](Method.POST, uri"/api/v1/appointments").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.BadRequest)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(true))
          assertEquals(json.hcursor.downField("httpcode").as[Int], Right(400))
        }
      case None => IO(fail("Route not matched"))
    }
  }

  test("GET /api/v1/appointments returns 200 OK with list of appointments") {
    val service = mockService()
    val routes = AppointmentController.routes[IO](service)

    val req = Request[IO](Method.GET, uri"/api/v1/appointments?startDate=2026-10-01T00:00:00Z&endDate=2026-10-31T23:59:59Z")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("appointments").as[List[Json]].map(_.size), Right(1))
        }
      case None => IO(fail("Route not matched"))
    }
  }

  test("GET /api/v1/appointments returns 400 Bad Request on invalid date format") {
    val service = mockService()
    val routes = AppointmentController.routes[IO](service)

    val req = Request[IO](Method.GET, uri"/api/v1/appointments?startDate=not-a-date")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        IO(assertEquals(resp.status, Status.BadRequest))
      case None => IO(fail("Route not matched"))
    }
  }

  test("PATCH /api/v1/appointments/{id}/status returns 200 OK on valid status update") {
    val service = mockService()
    val routes = AppointmentController.routes[IO](service)

    val body = Json.obj(
      "exam_status" -> Json.fromString("IN_PROGRESS")
    )
    val req = Request[IO](Method.PATCH, uri"/api/v1/appointments/500/status").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("id").as[String], Right("500"))
        }
      case None => IO(fail("Route not matched"))
    }
  }

  test("PATCH /api/v1/appointments/{id}/status returns 404 Not Found when appointment does not exist") {
    val service = mockService(updateResult = Left(AppointmentsError.NotFound))
    val routes = AppointmentController.routes[IO](service)

    val body = Json.obj(
      "exam_status" -> Json.fromString("IN_PROGRESS")
    )
    val req = Request[IO](Method.PATCH, uri"/api/v1/appointments/999/status").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        IO(assertEquals(resp.status, Status.NotFound))
      case None => IO(fail("Route not matched"))
    }
  }

  test("PATCH /api/v1/appointments/{id}/status returns 400 Bad Request when no status is provided") {
    val service = mockService(updateResult = Left(AppointmentsError.Validation(List(ValidationError("status", "Pelo menos um status deve ser informado.")))))
    val routes = AppointmentController.routes[IO](service)

    val body = Json.obj()
    val req = Request[IO](Method.PATCH, uri"/api/v1/appointments/500/status").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        IO(assertEquals(resp.status, Status.BadRequest))
      case None => IO(fail("Route not matched"))
    }
  }
