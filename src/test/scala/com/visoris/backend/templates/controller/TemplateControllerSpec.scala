package com.visoris.backend.templates.controller

import cats.effect.IO
import com.visoris.backend.iam.domain.User
import com.visoris.backend.templates.domain.{Template, TemplateSummary}
import com.visoris.backend.templates.dto.*
import com.visoris.backend.templates.service.{TemplateService, TemplatesError}
import io.circe.Json
import java.time.Instant
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

class TemplateControllerSpec extends CatsEffectSuite:

  private val now = Instant.now()
  private val testUser = User(
    id = 100L,
    email = "doctor@example.com",
    passwordHash = "hash",
    fullName = "Dr. Silva",
    professionalDocument = Some("CRM/SP 123456"),
    createdAt = now
  )

  private val sampleTemplate = Template(
    id = 500L,
    userId = 100L,
    title = "Ecocardiograma Normal",
    content = "<p>Estruturas normais.</p>",
    createdAt = now,
    updatedAt = now
  )

  private val sampleSummary = TemplateSummary(
    id = 500L,
    userId = 100L,
    title = "Ecocardiograma Normal",
    createdAt = now
  )

  private def mockService(
    createResult: Either[TemplatesError, Template] = Right(sampleTemplate),
    listResult: Either[TemplatesError, List[TemplateSummary]] = Right(List(sampleSummary)),
    findByIdResult: Either[TemplatesError, Template] = Right(sampleTemplate),
    updateResult: Either[TemplatesError, Template] = Right(sampleTemplate),
    deleteResult: Either[TemplatesError, Unit] = Right(())
  ): TemplateService[IO] = new TemplateService[IO]:
    def create(request: CreateTemplateRequest, userId: Long): IO[Either[TemplatesError, Template]] = IO.pure(createResult)
    def listByUser(userId: Long): IO[Either[TemplatesError, List[TemplateSummary]]] = IO.pure(listResult)
    def findById(id: Long, userId: Long): IO[Either[TemplatesError, Template]] = IO.pure(findByIdResult)
    def update(id: Long, request: UpdateTemplateRequest, userId: Long): IO[Either[TemplatesError, Template]] = IO.pure(updateResult)
    def delete(id: Long, userId: Long): IO[Either[TemplatesError, Unit]] = IO.pure(deleteResult)

  test("POST /api/v1/templates returns 201 Created on valid input") {
    val service = mockService()
    val routes = TemplateController.routes[IO](service)

    val body = Json.obj(
      "title" -> Json.fromString("Ecocardiograma Normal"),
      "content" -> Json.fromString("<p>Estruturas normais.</p>")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/templates").withEntity(body)

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

  test("POST /api/v1/templates returns 400 Bad Request on validation failure") {
    val service = mockService(createResult = Left(TemplatesError.Validation(List(ValidationError("title", "Título do template é obrigatório.")))))
    val routes = TemplateController.routes[IO](service)

    val body = Json.obj(
      "title" -> Json.fromString(""),
      "content" -> Json.fromString("<p>Estruturas normais.</p>")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/templates").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.BadRequest)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(true))
        }
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/templates returns 200 OK with list of template summaries") {
    val service = mockService()
    val routes = TemplateController.routes[IO](service)
    val req = Request[IO](Method.GET, uri"/api/v1/templates")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          val templates = json.hcursor.downField("data").downField("templates").as[List[Json]].getOrElse(Nil)
          assertEquals(templates.size, 1)
          assertEquals(templates.head.hcursor.downField("title").as[String], Right("Ecocardiograma Normal"))
          assert(templates.head.hcursor.downField("content").as[String].isLeft, "Listing must not contain content")
        }
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/templates/{id} returns 200 OK with full template detail") {
    val service = mockService()
    val routes = TemplateController.routes[IO](service)
    val req = Request[IO](Method.GET, uri"/api/v1/templates/500")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("title").as[String], Right("Ecocardiograma Normal"))
          assertEquals(json.hcursor.downField("data").downField("content").as[String], Right("<p>Estruturas normais.</p>"))
        }
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/templates/{id} returns 404 Not Found when template does not exist or unowned") {
    val service = mockService(findByIdResult = Left(TemplatesError.NotFound))
    val routes = TemplateController.routes[IO](service)
    val req = Request[IO](Method.GET, uri"/api/v1/templates/999")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }

  test("PUT /api/v1/templates/{id} returns 200 OK on valid update") {
    val updatedTemplate = sampleTemplate.copy(title = "Ecocardiograma Atualizado")
    val service = mockService(updateResult = Right(updatedTemplate))
    val routes = TemplateController.routes[IO](service)

    val body = Json.obj(
      "title" -> Json.fromString("Ecocardiograma Atualizado")
    )
    val req = Request[IO](Method.PUT, uri"/api/v1/templates/500").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("title").as[String], Right("Ecocardiograma Atualizado"))
        }
      case None => fail("Route not matched")
    }
  }

  test("PUT /api/v1/templates/{id} returns 400 Bad Request on empty payload") {
    val service = mockService(updateResult = Left(TemplatesError.Validation(List(ValidationError("body", "Pelo menos um campo ('title' ou 'content') deve ser fornecido para atualização.")))))
    val routes = TemplateController.routes[IO](service)

    val body = Json.obj()
    val req = Request[IO](Method.PUT, uri"/api/v1/templates/500").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) =>
        assertEquals(resp.status, Status.BadRequest)
      case None => fail("Route not matched")
    }
  }

  test("PUT /api/v1/templates/{id} returns 404 Not Found when unowned or not found") {
    val service = mockService(updateResult = Left(TemplatesError.NotFound))
    val routes = TemplateController.routes[IO](service)

    val body = Json.obj("title" -> Json.fromString("Novo Título"))
    val req = Request[IO](Method.PUT, uri"/api/v1/templates/999").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }

  test("DELETE /api/v1/templates/{id} returns 200 OK on successful deletion") {
    val service = mockService(deleteResult = Right(()))
    val routes = TemplateController.routes[IO](service)
    val req = Request[IO](Method.DELETE, uri"/api/v1/templates/500")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.Ok)
      case None => fail("Route not matched")
    }
  }

  test("DELETE /api/v1/templates/{id} returns 404 Not Found when unowned or not found") {
    val service = mockService(deleteResult = Left(TemplatesError.NotFound))
    val routes = TemplateController.routes[IO](service)
    val req = Request[IO](Method.DELETE, uri"/api/v1/templates/999")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None => fail("Route not matched")
    }
  }
