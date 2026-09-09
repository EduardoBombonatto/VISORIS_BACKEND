package com.visoris.backend.patients.controller

import cats.effect.IO
import com.visoris.backend.iam.domain.User
import com.visoris.backend.patients.domain.Client
import com.visoris.backend.patients.dto.{ClientRequest, ValidationError}
import com.visoris.backend.patients.service.{ClientService, ClientsError}
import io.circe.Json
import java.time.Instant
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

class ClientControllerSpec extends CatsEffectSuite:

  private val now = Instant.now()
  private val testUser = User(
    id = 100L,
    email = "doctor@example.com",
    passwordHash = "hash",
    fullName = "Dr. Silva",
    professionalDocument = Some("CRM/SP 123456"),
    createdAt = now
  )

  private val sampleClient = Client(
    id = 500L,
    userId = 100L,
    fullName = "Maria Souza",
    documentCpf = Some("12345678909"),
    email = Some("maria@example.com"),
    phone = Some("11987654321"),
    createdAt = now,
    updatedAt = now
  )

  private def mockService(
    createResult: Either[ClientsError, Client] = Right(sampleClient),
    listResult: Either[ClientsError, List[Client]] = Right(List(sampleClient))
  ): ClientService[IO] = new ClientService[IO]:
    def create(request: ClientRequest, userId: Long): IO[Either[ClientsError, Client]] = IO.pure(createResult)
    def listByUser(userId: Long, limit: Long, offset: Long): IO[Either[ClientsError, List[Client]]] = IO.pure(listResult)

  test("POST /api/v1/clients returns 201 Created on valid input") {
    val service = mockService()
    val routes = ClientController.routes[IO](service)

    val body = Json.obj(
      "full_name" -> Json.fromString("Maria Souza"),
      "document_cpf" -> Json.fromString("12345678909"),
      "email" -> Json.fromString("maria@example.com"),
      "phone" -> Json.fromString("11987654321")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/clients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Created)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("id").as[String], Right("500"))
        }
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/clients returns 404 when user not found") {
    val service = mockService(createResult = Left(ClientsError.NotFound))
    val routes = ClientController.routes[IO](service)

    val body = Json.obj(
      "full_name" -> Json.fromString("Maria Souza"),
      "document_cpf" -> Json.fromString("12345678909"),
      "email" -> Json.fromString("maria@example.com"),
      "phone" -> Json.fromString("11987654321")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/clients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/clients returns 409 when CPF already exists for user") {
    val service = mockService(createResult = Left(ClientsError.ConflictCpf))
    val routes = ClientController.routes[IO](service)

    val body = Json.obj(
      "full_name" -> Json.fromString("Maria Souza"),
      "document_cpf" -> Json.fromString("12345678909"),
      "email" -> Json.fromString("maria@example.com"),
      "phone" -> Json.fromString("11987654321")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/clients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.Conflict)
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/clients returns 400 on validation failure") {
    val service = mockService(createResult = Left(ClientsError.Validation(List(ValidationError("full_name", "Obrigatório")))))
    val routes = ClientController.routes[IO](service)

    val body = Json.obj(
      "full_name" -> Json.fromString(""),
      "document_cpf" -> Json.fromString("12345678909"),
      "email" -> Json.fromString("maria@example.com"),
      "phone" -> Json.fromString("11987654321")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/clients").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.BadRequest)
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/clients returns 200 and list of clients") {
    val service = mockService()
    val routes = ClientController.routes[IO](service)

    val req = Request[IO](Method.GET, uri"/api/v1/clients?limit=10&offset=0")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          val clients = json.hcursor.downField("data").downField("clients").as[List[Json]].toOption.get
          assertEquals(clients.length, 1)
        }
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/clients returns 404 when user not found") {
    val service = mockService(listResult = Left(ClientsError.NotFound))
    val routes = ClientController.routes[IO](service)

    val req = Request[IO](Method.GET, uri"/api/v1/clients")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }
