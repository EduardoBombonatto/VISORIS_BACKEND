package com.visoris.backend.templates.service

import cats.effect.IO
import com.visoris.backend.templates.domain.{Template, TemplateSummary}
import com.visoris.backend.templates.dto.{CreateTemplateRequest, UpdateTemplateRequest}
import com.visoris.backend.templates.repository.TemplateRepository
import doobie.ConnectionIO
import doobie.free.connection.pure
import doobie.util.transactor.Transactor
import java.time.Instant
import munit.CatsEffectSuite

class TemplateServiceSpec extends CatsEffectSuite:

  private val now = Instant.now()
  private val testUserId = 123L
  private val otherUserId = 999L

  private val sampleTemplate = Template(
    id = 1L,
    userId = testUserId,
    title = "Ecocardiograma Padrão",
    content = "<p>Conteúdo HTML</p>",
    createdAt = now,
    updatedAt = now
  )

  private val sampleSummary = TemplateSummary(
    id = 1L,
    userId = testUserId,
    title = "Ecocardiograma Padrão",
    createdAt = now
  )

  private def mockRepo(
    insertResult: Template = sampleTemplate,
    listResult: List[TemplateSummary] = List(sampleSummary),
    findByIdResult: Option[Template] = Some(sampleTemplate),
    updateResult: Option[Template] = Some(sampleTemplate),
    deleteCount: Int = 1
  ): TemplateRepository[IO] = new TemplateRepository[IO]:
    def insert(userId: Long, title: String, content: String): ConnectionIO[Template] = pure(insertResult)
    def findAllByUserId(userId: Long): ConnectionIO[List[TemplateSummary]] = pure(listResult)
    def findByIdAndUserId(id: Long, userId: Long): ConnectionIO[Option[Template]] =
      if userId == testUserId && id == sampleTemplate.id then pure(findByIdResult)
      else pure(None)
    def update(id: Long, userId: Long, title: String, content: String): ConnectionIO[Option[Template]] =
      if userId == testUserId && id == sampleTemplate.id then pure(updateResult)
      else pure(None)
    def delete(id: Long, userId: Long): ConnectionIO[Int] =
      if userId == testUserId && id == sampleTemplate.id then pure(deleteCount)
      else pure(0)

  private val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:test_templates_svc;DB_CLOSE_DELAY=-1",
    "sa",
    "",
    None
  )

  test("validateCreateRequest accepts valid title and content") {
    val req = CreateTemplateRequest("Ultrassom Abdominal", "<p>Sem alterações</p>")
    val result = TemplateService.validateCreateRequest(req)
    assert(result.isRight)
  }

  test("validateCreateRequest rejects empty title") {
    val req = CreateTemplateRequest("   ", "<p>Sem alterações</p>")
    val result = TemplateService.validateCreateRequest(req)
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.head.field, "title")
  }

  test("validateCreateRequest rejects title longer than 255 chars") {
    val longTitle = "A" * 256
    val req = CreateTemplateRequest(longTitle, "<p>Conteúdo</p>")
    val result = TemplateService.validateCreateRequest(req)
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.head.field, "title")
  }

  test("validateUpdateRequest rejects empty payload") {
    val req = UpdateTemplateRequest(None, None)
    val result = TemplateService.validateUpdateRequest(req)
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.head.field, "body")
  }

  test("validateUpdateRequest accepts title only") {
    val req = UpdateTemplateRequest(Some("Novo Título"), None)
    val result = TemplateService.validateUpdateRequest(req)
    assert(result.isRight)
  }

  test("validateUpdateRequest accepts content only") {
    val req = UpdateTemplateRequest(None, Some("<p>Novo Conteúdo</p>"))
    val result = TemplateService.validateUpdateRequest(req)
    assert(result.isRight)
  }

  test("validateUpdateRequest rejects empty title when provided") {
    val req = UpdateTemplateRequest(Some("   "), None)
    val result = TemplateService.validateUpdateRequest(req)
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.head.field, "title")
  }

  test("create returns Validation error when title is empty") {
    val repo = mockRepo()
    val service = TemplateService.make[IO](repo, xa)
    val req = CreateTemplateRequest("", "<p>conteudo</p>")

    service.create(req, testUserId).map {
      case Left(TemplatesError.Validation(errors)) =>
        assertEquals(errors.head.field, "title")
      case other => fail(s"Expected validation error, got $other")
    }
  }

  test("findById returns NotFound for unowned template") {
    val repo = mockRepo()
    val service = TemplateService.make[IO](repo, xa)

    service.findById(sampleTemplate.id, otherUserId).map {
      case Left(TemplatesError.NotFound) => ()
      case other => fail(s"Expected NotFound, got $other")
    }
  }

  test("update returns NotFound for unowned template") {
    val repo = mockRepo()
    val service = TemplateService.make[IO](repo, xa)
    val req = UpdateTemplateRequest(Some("Novo Título"), None)

    service.update(sampleTemplate.id, req, otherUserId).map {
      case Left(TemplatesError.NotFound) => ()
      case other => fail(s"Expected NotFound, got $other")
    }
  }

  test("delete returns NotFound for unowned template") {
    val repo = mockRepo()
    val service = TemplateService.make[IO](repo, xa)

    service.delete(sampleTemplate.id, otherUserId).map {
      case Left(TemplatesError.NotFound) => ()
      case other => fail(s"Expected NotFound, got $other")
    }
  }
