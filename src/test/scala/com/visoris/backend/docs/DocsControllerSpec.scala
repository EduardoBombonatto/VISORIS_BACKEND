package com.visoris.backend.docs

import cats.effect.IO
import io.circe.Json
import io.circe.parser.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*

class DocsControllerSpec extends CatsEffectSuite:

  private val routes = DocsController.routes[IO].orNotFound

  private def responseCodesFor(json: Json, path: String): Set[String] =
    json.hcursor
      .downField("paths").downField(path).downField("post").downField("responses")
      .keys
      .fold(Set.empty[String])(_.toSet)

  private def keysAt(json: Json, segments: String*): Set[String] =
    val cursor = segments.foldLeft[io.circe.ACursor](json.hcursor)((c, seg) => c.downField(seg))
    cursor.keys.fold(Set.empty[String])(_.toSet)

  test("openapi.json returns a valid OpenAPI 3.0.1 document") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(resp.contentType.map(_.mediaType), Some(MediaType.application.json))
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      assertEquals(json.hcursor.downField("openapi").as[String].getOrElse(""), "3.0.1")
      assertEquals(json.hcursor.downField("info").downField("title").as[String].getOrElse(""), "Visoris API")
      val paths = json.hcursor.downField("paths").keys.fold(Set.empty[String])(_.toSet)
      assertEquals(
        paths,
        Set(
          "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/register", "/api/v1/auth/me",
          "/api/v1/auth/logout", "/api/v1/clinics", "/api/v1/clients", "/api/v1/clients/{id}",
          "/api/v1/patients", "/api/v1/patients/{id}",
          "/api/v1/appointments", "/api/v1/appointments/{id}/status",
          "/api/v1/templates", "/api/v1/templates/{id}"
        )
      )
  }

  test("login documents happy path (200) and all edge cases (400, 401, 500)") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      val codes = responseCodesFor(json, "/api/v1/auth/login")
      assertEquals(codes, Set("200", "400", "401", "500"))
  }

  test("refresh documents happy path (200) and edge cases (401, 500) with cookie security") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      assertEquals(responseCodesFor(json, "/api/v1/auth/refresh"), Set("200", "401", "500"))
      val security = json.hcursor
        .downField("paths").downField("/api/v1/auth/refresh").downField("post").downField("security")
        .as[Json].toOption.flatMap(_.asArray)
        .getOrElse(Nil)
      assert(security.nonEmpty, "refresh must declare cookie security")
  }

  test("register documents happy path (201) and edge cases (400, 409, 500)") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      assertEquals(responseCodesFor(json, "/api/v1/auth/register"), Set("201", "400", "409", "500"))
  }

  test("clinics documents list (200, 401, 500) and create (201, 200, 400, 401, 500) with cookie security") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      val getCodes = json.hcursor
        .downField("paths").downField("/api/v1/clinics").downField("get").downField("responses")
        .keys.fold(Set.empty[String])(_.toSet)
      assertEquals(getCodes, Set("200", "401", "500"))
      val postCodes = json.hcursor
        .downField("paths").downField("/api/v1/clinics").downField("post").downField("responses")
        .keys.fold(Set.empty[String])(_.toSet)
      assertEquals(postCodes, Set("201", "200", "400", "401", "500"))
      val tags = json.hcursor.downField("tags").as[List[Json]].getOrElse(Nil)
      assert(tags.exists(_.hcursor.downField("name").as[String].contains("Clinics")), "Clinics tag missing")
  }

  test("defines the two cookie security schemes and all schemas") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      assertEquals(
        keysAt(json, "components", "securitySchemes"),
        Set("refreshTokenCookie", "accessTokenCookie")
      )
      val schemas = keysAt(json, "components", "schemas")
      assertEquals(
        schemas,
        Set(
          "LoginRequest", "RegisterRequest",
          "UserData", "LoginResponse", "RegisterResponse",
          "RefreshResponse", "ValidationError",
          "ClinicData", "CreateClinicRequest", "ClinicListResponse", "CreateClinicResponse",
          "ClientRequest", "ClientResponse", "CreateClientResponse", "ClientListResponse",
          "PatientRequest", "PatientResponse", "CreatePatientResponse", "PatientListResponse",
          "UpdatePatientRequest",
          "ExamStatus", "ReportStatus", "PaymentStatus",
          "CreateAppointmentRequest", "UpdateAppointmentStatusRequest",
          "AppointmentResponse", "CreateAppointmentResponse", "AppointmentListResponse",
          "CreateTemplateRequest", "CreateTemplateResponse", "UpdateTemplateRequest",
          "TemplateSummaryResponse", "TemplateListResponse", "TemplateDetailResponse"
        )
      )
  }

  test("appointments documents create (201), list (200), and status update (200) with cookie security") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      val postCodes = json.hcursor
        .downField("paths").downField("/api/v1/appointments").downField("post").downField("responses")
        .keys.fold(Set.empty[String])(_.toSet)
      assertEquals(postCodes, Set("201", "400", "401", "404", "500"))

      val getCodes = json.hcursor
        .downField("paths").downField("/api/v1/appointments").downField("get").downField("responses")
        .keys.fold(Set.empty[String])(_.toSet)
      assertEquals(getCodes, Set("200", "400", "401", "404", "500"))

      val patchCodes = json.hcursor
        .downField("paths").downField("/api/v1/appointments/{id}/status").downField("patch").downField("responses")
        .keys.fold(Set.empty[String])(_.toSet)
      assertEquals(patchCodes, Set("200", "400", "401", "404", "500"))

      val tags = json.hcursor.downField("tags").as[List[Json]].getOrElse(Nil)
      assert(tags.exists(_.hcursor.downField("name").as[String].contains("Appointments")), "Appointments tag missing")
  }

  test("docs index serves the Swagger UI HTML shell") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(resp.contentType.map(_.mediaType), Some(MediaType.text.html))
      assert(!body.startsWith("\""), "HTML must not be JSON-encoded")
      assert(body.contains("swagger-ui"))
      assert(body.contains("/api/v1/docs/openapi.json"))
  }

  test("docs root without trailing slash serves the HTML shell too") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(resp.contentType.map(_.mediaType), Some(MediaType.text.html))
      assert(!body.startsWith("\""), "HTML must not be JSON-encoded")
      assert(body.contains("swagger-ui"))
  }

  test("swagger-ui static assets are served from the webjar") {
    for
      bundleResp <- routes.run(Request[IO](Method.GET, uri"/api/v1/docs/swagger-ui/swagger-ui-bundle.js"))
      cssResp <- routes.run(Request[IO](Method.GET, uri"/api/v1/docs/swagger-ui/swagger-ui.css"))
    yield
      assertEquals(bundleResp.status, Status.Ok)
      assertEquals(cssResp.status, Status.Ok)
  }

  test("unknown swagger-ui asset returns 404") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/swagger-ui/does-not-exist.js")
    routes.run(req).map(resp => assertEquals(resp.status, Status.NotFound))
  }

  test("openapi.json documents Templates tag, paths, and schemas") {
    val req = Request[IO](Method.GET, uri"/api/v1/docs/openapi.json")
    for
      resp <- routes.run(req)
      body <- resp.as[String]
    yield
      val json = parse(body).getOrElse(fail("OpenAPI spec is not valid JSON"))
      val tags = json.hcursor.downField("tags").as[List[Json]].getOrElse(Nil)
        .flatMap(_.hcursor.downField("name").as[String].toOption)
      assert(tags.contains("Templates"))

      val schemas = keysAt(json, "components", "schemas")
      assert(schemas.contains("CreateTemplateRequest"))
      assert(schemas.contains("CreateTemplateResponse"))
      assert(schemas.contains("UpdateTemplateRequest"))
      assert(schemas.contains("TemplateSummaryResponse"))
      assert(schemas.contains("TemplateListResponse"))
      assert(schemas.contains("TemplateDetailResponse"))

      val templatePaths = json.hcursor.downField("paths").keys.fold(Set.empty[String])(_.toSet)
      assert(templatePaths.contains("/api/v1/templates"))
      assert(templatePaths.contains("/api/v1/templates/{id}"))
  }

