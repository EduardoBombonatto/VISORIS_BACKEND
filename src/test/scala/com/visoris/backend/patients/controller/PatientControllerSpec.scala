package com.visoris.backend.patients.controller

import cats.effect.IO
import com.visoris.backend.iam.domain.User
import com.visoris.backend.patients.domain.{Patient, PatientType}
import com.visoris.backend.patients.dto.{PatientRequest, ValidationError}
import com.visoris.backend.patients.service.{PatientService, PatientsError}
import io.circe.Json
import java.time.{Instant, LocalDate}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

class PatientControllerSpec extends CatsEffectSuite:

  private val now = Instant.now()
  private val testUser = User(
    id = 100L,
    email = "doctor@example.com",
    passwordHash = "hash",
    fullName = "Dr. Silva",
    professionalDocument = Some("CRM/SP 123456"),
    createdAt = now
  )

  private val samplePatient = Patient(
    id = 800L,
    clientId = Some(20L),
    name = "Rex",
    patientType = PatientType.PET,
    birthDate = Some(LocalDate.of(2022, 5, 10)),
    biologicalDetails = Json.obj("breed" -> Json.fromString("Golden")),
    createdAt = now
  )

  private def mockService(
    createResult: Either[PatientsError, Patient] = Right(samplePatient),
    listResult: Either[PatientsError, List[Patient]] = Right(List(samplePatient))
  ): PatientService[IO] = new PatientService[IO]:
    def create(request: PatientRequest, userId: Long): IO[Either[PatientsError, Patient]] = IO.pure(createResult)
    def listByClient(clientId: Long, userId: Long, limit: Long, offset: Long): IO[Either[PatientsError, List[Patient]]] = IO.pure(listResult)

  test("POST /api/v1/patients returns 201 Created on valid input") {
    val service = mockService()
    val routes = PatientController.routes[IO](service)

    val body = Json.obj(
      "client_id" -> Json.fromLong(20L),
      "name" -> Json.fromString("Rex"),
      "patient_type" -> Json.fromString("PET"),
      "birth_date" -> Json.fromString("2022-05-10"),
      "biological_details" -> Json.obj("species" -> Json.fromString("Canino"))
    )
    val req = Request[IO](Method.POST, uri"/api/v1/patients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Created)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("patient").downField("name").as[String], Right("Rex"))
        }
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/patients returns 404 when client not found or not owned") {
    val service = mockService(createResult = Left(PatientsError.NotFound))
    val routes = PatientController.routes[IO](service)

    val body = Json.obj(
      "client_id" -> Json.fromLong(20L),
      "name" -> Json.fromString("Rex"),
      "patient_type" -> Json.fromString("PET"),
      "birth_date" -> Json.fromString("2022-05-10"),
      "biological_details" -> Json.obj()
    )
    val req = Request[IO](Method.POST, uri"/api/v1/patients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/patients returns 400 on validation failure") {
    val service = mockService(createResult = Left(PatientsError.Validation(List(ValidationError("name", "Obrigatório")))))
    val routes = PatientController.routes[IO](service)

    val body = Json.obj(
      "client_id" -> Json.fromLong(20L),
      "name" -> Json.fromString(""),
      "patient_type" -> Json.fromString("PET"),
      "birth_date" -> Json.fromString("2022-05-10"),
      "biological_details" -> Json.obj()
    )
    val req = Request[IO](Method.POST, uri"/api/v1/patients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.BadRequest)
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/patients returns 200 and list of patients") {
    val service = mockService()
    val routes = PatientController.routes[IO](service)

    val req = Request[IO](Method.GET, uri"/api/v1/patients?clientId=20&limit=10&offset=0")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          val patients = json.hcursor.downField("data").downField("patients").as[List[Json]].toOption.get
          assertEquals(patients.length, 1)
        }
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/patients returns 404 when client not found or not owned") {
    val service = mockService(listResult = Left(PatientsError.NotFound))
    val routes = PatientController.routes[IO](service)

    val req = Request[IO](Method.GET, uri"/api/v1/patients?clientId=20")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }
