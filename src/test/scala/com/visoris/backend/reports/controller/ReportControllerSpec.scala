package com.visoris.backend.reports.controller

import cats.effect.IO
import com.visoris.backend.iam.domain.User
import com.visoris.backend.reports.domain.{Report, ReportImage, ReportWithImages}
import com.visoris.backend.reports.dto.*
import com.visoris.backend.reports.service.{ReportService, ReportsError}
import io.circe.Json
import java.time.Instant
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*

class ReportControllerSpec extends CatsEffectSuite:

  private val now = Instant.parse("2026-10-21T12:00:00Z")
  private val testUser = User(
    id = 100L,
    email = "doctor@example.com",
    passwordHash = "hash",
    fullName = "Dr. Silva",
    professionalDocument = Some("CRM/SP 123456"),
    createdAt = now
  )

  private val samplePatientSnap = Json.obj("id" -> Json.fromString("300"), "name" -> Json.fromString("Rex"))
  private val sampleClinicSnap  = Json.obj("id" -> Json.fromString("200"), "name" -> Json.fromString("Clínica Vida"))

  private val sampleReport = Report(
    id = 700L,
    appointmentId = 400L,
    userId = 100L,
    attendanceLocationName = Some("Clínica Vida"),
    patientSnapshot = Some(samplePatientSnap),
    clinicSnapshot = Some(sampleClinicSnap),
    content = "<h2>Ultrassonografia</h2><p>Achados normais.</p>",
    pdfUrl = None,
    signedAt = None,
    createdAt = now,
    updatedAt = now
  )

  private val sampleImage = ReportImage(
    id = 800L,
    reportId = 700L,
    imageUrl = "https://storage.visoris.com/reports/700/img1.jpg",
    caption = Some("Corte longitudinal hepático"),
    displayOrder = 1,
    createdAt = now
  )

  private val sampleReportWithImages = ReportWithImages(
    report = sampleReport,
    images = List(sampleImage)
  )

  private def mockService(
    getByAppointmentResult: Either[ReportsError, ReportWithImages] = Right(sampleReportWithImages),
    createDraftResult: Either[ReportsError, ReportWithImages] = Right(ReportWithImages(sampleReport, Nil)),
    updateContentResult: Either[ReportsError, ReportWithImages] = Right(sampleReportWithImages),
    addImageResult: Either[ReportsError, ReportImage] = Right(sampleImage),
    signResult: Either[ReportsError, ReportWithImages] = Right(
      sampleReportWithImages.copy(report = sampleReport.copy(signedAt = Some(now)))
    )
  ): ReportService[IO] = new ReportService[IO]:
    def getByAppointmentId(appointmentId: Long, userId: Long): IO[Either[ReportsError, ReportWithImages]] =
      IO.pure(getByAppointmentResult)
    def createDraft(request: CreateReportRequest, userId: Long): IO[Either[ReportsError, ReportWithImages]] =
      IO.pure(createDraftResult)
    def updateContent(id: Long, request: UpdateReportRequest, userId: Long): IO[Either[ReportsError, ReportWithImages]] =
      IO.pure(updateContentResult)
    def addImage(reportId: Long, request: ReportImageRequest, userId: Long): IO[Either[ReportsError, ReportImage]] =
      IO.pure(addImageResult)
    def signReport(id: Long, userId: Long): IO[Either[ReportsError, ReportWithImages]] =
      IO.pure(signResult)

  // --- US1: POST /api/v1/reports ---

  test("POST /api/v1/reports returns 201 Created with report and snapshots on valid input") {
    val routes = ReportController.routes[IO](mockService())
    val body = Json.obj(
      "appointment_id" -> Json.fromLong(400L),
      "content"        -> Json.fromString("<h2>Ultrassonografia</h2><p>Achados normais.</p>")
    )
    val req = Request[IO](Method.POST, uri"/api/v1/reports").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Created)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          val data = json.hcursor.downField("data")
          assertEquals(data.downField("id").as[String], Right("700"))
          assertEquals(data.downField("appointmentId").as[String], Right("400"))
          assertEquals(data.downField("attendanceLocationName").as[String], Right("Clínica Vida"))
          assertEquals(data.downField("patientSnapshot").downField("name").as[String], Right("Rex"))
          assertEquals(data.downField("clinicSnapshot").downField("name").as[String], Right("Clínica Vida"))
        }
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports returns 400 Bad Request on validation failure") {
    val service = mockService(
      createDraftResult = Left(ReportsError.Validation(List(ValidationError("appointment_id", "O campo 'appointment_id' é obrigatório."))))
    )
    val routes = ReportController.routes[IO](service)
    val req    = Request[IO](Method.POST, uri"/api/v1/reports").withEntity(Json.obj())

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.BadRequest)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(true))
        }
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports returns 404 Not Found when appointment does not exist or belongs to another user") {
    val service = mockService(createDraftResult = Left(ReportsError.NotFound))
    val routes  = ReportController.routes[IO](service)
    val body    = Json.obj("appointment_id" -> Json.fromLong(999L), "content" -> Json.fromString("<p>Teste</p>"))
    val req     = Request[IO](Method.POST, uri"/api/v1/reports").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports returns 409 Conflict when appointment already has a report") {
    val service = mockService(createDraftResult = Left(ReportsError.Conflict("Já existe um laudo associado a este agendamento.")))
    val routes  = ReportController.routes[IO](service)
    val body    = Json.obj("appointment_id" -> Json.fromLong(400L), "content" -> Json.fromString("<p>Teste</p>"))
    val req     = Request[IO](Method.POST, uri"/api/v1/reports").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.Conflict)
      case None       => fail("Route not matched")
    }
  }

  // --- US2: PUT /api/v1/reports/{id} ---

  test("PUT /api/v1/reports/{id} returns 200 OK on valid content update") {
    val updated = sampleReport.copy(content = "<p>Conteúdo atualizado</p>")
    val service = mockService(updateContentResult = Right(ReportWithImages(updated, List(sampleImage))))
    val routes  = ReportController.routes[IO](service)
    val body    = Json.obj("content" -> Json.fromString("<p>Conteúdo atualizado</p>"))
    val req     = Request[IO](Method.PUT, uri"/api/v1/reports/700").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("content").as[String], Right("<p>Conteúdo atualizado</p>"))
        }
      case None => fail("Route not matched")
    }
  }

  test("PUT /api/v1/reports/{id} returns 404 Not Found when report does not exist or belongs to another user") {
    val service = mockService(updateContentResult = Left(ReportsError.NotFound))
    val routes  = ReportController.routes[IO](service)
    val body    = Json.obj("content" -> Json.fromString("<p>Novo</p>"))
    val req     = Request[IO](Method.PUT, uri"/api/v1/reports/999").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("Route not matched")
    }
  }

  test("PUT /api/v1/reports/{id} returns 409 Conflict when report is already signed") {
    val service = mockService(updateContentResult = Left(ReportsError.Conflict("Laudo já assinado não pode ser alterado.")))
    val routes  = ReportController.routes[IO](service)
    val body    = Json.obj("content" -> Json.fromString("<p>Tentativa de edição</p>"))
    val req     = Request[IO](Method.PUT, uri"/api/v1/reports/700").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.Conflict)
      case None       => fail("Route not matched")
    }
  }

  // --- US3: POST /api/v1/reports/{id}/images ---

  test("POST /api/v1/reports/{id}/images returns 201 Created with image metadata") {
    val routes = ReportController.routes[IO](mockService())
    val body = Json.obj(
      "image_url"     -> Json.fromString("https://storage.visoris.com/reports/700/img1.jpg"),
      "caption"       -> Json.fromString("Corte longitudinal hepático"),
      "display_order" -> Json.fromInt(1)
    )
    val req = Request[IO](Method.POST, uri"/api/v1/reports/700/images").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Created)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          val data = json.hcursor.downField("data")
          assertEquals(data.downField("id").as[String], Right("800"))
          assertEquals(data.downField("reportId").as[String], Right("700"))
          assertEquals(data.downField("imageUrl").as[String], Right("https://storage.visoris.com/reports/700/img1.jpg"))
          assertEquals(data.downField("displayOrder").as[Int], Right(1))
        }
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports/{id}/images returns 400 Bad Request on invalid image payload") {
    val service = mockService(
      addImageResult = Left(ReportsError.Validation(List(ValidationError("image_url", "A URL da imagem é obrigatória."))))
    )
    val routes = ReportController.routes[IO](service)
    val req    = Request[IO](Method.POST, uri"/api/v1/reports/700/images").withEntity(Json.obj("image_url" -> Json.fromString("")))

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.BadRequest)
      case None       => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports/{id}/images returns 404 Not Found when report belongs to another user") {
    val service = mockService(addImageResult = Left(ReportsError.NotFound))
    val routes  = ReportController.routes[IO](service)
    val body    = Json.obj("image_url" -> Json.fromString("https://storage.visoris.com/img.jpg"))
    val req     = Request[IO](Method.POST, uri"/api/v1/reports/999/images").withEntity(body)

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("Route not matched")
    }
  }

  // --- US4: GET /api/v1/reports/appointment/{appointmentId} ---

  test("GET /api/v1/reports/appointment/{appointmentId} returns 200 OK with report and attached images") {
    val routes = ReportController.routes[IO](mockService())
    val req    = Request[IO](Method.GET, uri"/api/v1/reports/appointment/400")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          val data   = json.hcursor.downField("data")
          val images = data.downField("images").as[List[Json]].getOrElse(Nil)
          assertEquals(data.downField("id").as[String], Right("700"))
          assertEquals(data.downField("appointmentId").as[String], Right("400"))
          assertEquals(images.size, 1)
          assertEquals(images.head.hcursor.downField("imageUrl").as[String], Right("https://storage.visoris.com/reports/700/img1.jpg"))
        }
      case None => fail("Route not matched")
    }
  }

  test("GET /api/v1/reports/appointment/{appointmentId} returns 404 Not Found when report does not exist or unowned") {
    val service = mockService(getByAppointmentResult = Left(ReportsError.NotFound))
    val routes  = ReportController.routes[IO](service)
    val req     = Request[IO](Method.GET, uri"/api/v1/reports/appointment/999")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("Route not matched")
    }
  }

  // --- US5: POST /api/v1/reports/{id}/sign ---

  test("POST /api/v1/reports/{id}/sign returns 200 OK with signedAt timestamp populated") {
    val routes = ReportController.routes[IO](mockService())
    val req    = Request[IO](Method.POST, uri"/api/v1/reports/700/sign")

    routes.run(ContextRequest(testUser, req)).value.flatMap {
      case Some(resp) =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { json =>
          assertEquals(json.hcursor.downField("erro").as[Boolean], Right(false))
          assertEquals(json.hcursor.downField("data").downField("signedAt").as[String], Right("2026-10-21T12:00:00Z"))
        }
      case None => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports/{id}/sign returns 404 Not Found when report belongs to another user") {
    val service = mockService(signResult = Left(ReportsError.NotFound))
    val routes  = ReportController.routes[IO](service)
    val req     = Request[IO](Method.POST, uri"/api/v1/reports/999/sign")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.NotFound)
      case None       => fail("Route not matched")
    }
  }

  test("POST /api/v1/reports/{id}/sign returns 409 Conflict when report was already signed") {
    val service = mockService(signResult = Left(ReportsError.Conflict("Laudo já foi assinado anteriormente.")))
    val routes  = ReportController.routes[IO](service)
    val req     = Request[IO](Method.POST, uri"/api/v1/reports/700/sign")

    routes.run(ContextRequest(testUser, req)).value.map {
      case Some(resp) => assertEquals(resp.status, Status.Conflict)
      case None       => fail("Route not matched")
    }
  }
