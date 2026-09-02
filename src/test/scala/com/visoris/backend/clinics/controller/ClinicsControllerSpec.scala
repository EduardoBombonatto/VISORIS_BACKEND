package com.visoris.backend.clinics.controller

import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.visoris.backend.clinics.repository.{ClinicRepository, DoctorClinicRepository}
import com.visoris.backend.clinics.service.ClinicsService
import com.visoris.backend.iam.controller.AuthController
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.iam.service.{AuthService, RegistrationService}
import com.visoris.backend.shared.auth.{AuthMiddleware, TokenService}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import io.circe.Json
import io.circe.parser.*
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway
import org.http4s.*
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ClinicsControllerSpec extends CatsEffectSuite:

  private val dbUrl  = sys.env.getOrElse("DB_URL", "jdbc:postgresql://localhost:5432/visoris_db")
  private val dbUser = sys.env.getOrElse("DB_USER", "postgres")
  private val dbPass = sys.env.getOrElse("DB_PASSWORD", "Visoris@123.")
  private val jwtSecret = "test-secret-for-clinics-e2e"

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val baseUri = uri"http://localhost:18081"

  private def validCnpj: String =
    LazyList.continually(generateCnpj).find(_.distinct.length > 1).get

  private def generateCnpj: String =
    import scala.util.Random
    val base = Array.fill(12)(Random.nextInt(10))
    val w1 = Array(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    val w2 = Array(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    def cd(weights: Array[Int], count: Int): Int =
      val sum = (0 until count).map(i => base(i) * weights(i)).sum
      val rest = sum % 11
      if rest < 2 then 0 else 11 - rest
    val d12 = cd(w1, 12)
    val full = base :+ d12
    val sum13 = (0 until 13).map(i => full(i) * w2(i)).sum
    val rest13 = sum13 % 11
    val d13 = if rest13 < 2 then 0 else 11 - rest13
    (full :+ d13).mkString

  private def serverResource: Resource[IO, org.http4s.client.Client[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](4)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver", dbUrl, dbUser, dbPass, ce
      )
      _ <- Resource.eval(xa.configure { dataSource =>
        Sync[IO].delay { Flyway.configure().dataSource(dataSource).load().migrate(); () }
      })
      tokenService = TokenService.make(jwtSecret)
      userRepo = UserRepository.make[IO](xa)
      refreshTokenRepo = RefreshTokenRepository.make[IO](xa)
      registrationService = RegistrationService.make[IO](tokenService, xa, userRepo, refreshTokenRepo)
      authService = AuthService.make[IO](tokenService, xa, userRepo, refreshTokenRepo)
      authMiddleware = AuthMiddleware.make[IO](tokenService, userRepo, xa)
      clinicRepo = ClinicRepository.make[IO](xa)
      doctorClinicRepo = DoctorClinicRepository.make[IO](xa)
      clinicsService = ClinicsService.make[IO](clinicRepo, doctorClinicRepo, xa)
      httpApp =
        (AuthController.routes[IO](registrationService, authService) <+>
          authMiddleware(ClinicsController.routes[IO](clinicsService))).orNotFound
      _ <- EmberServerBuilder.default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"18081")
        .withHttpApp(httpApp)
        .build
      client <- EmberClientBuilder.default[IO].build
    yield client

  private def registerRequestJson(
    fullName: String,
    email: String,
    password: String,
    professionalDocument: String
  ): Json =
    Json.obj(
      "fullName" -> Json.fromString(fullName),
      "email" -> Json.fromString(email),
      "password" -> Json.fromString(password),
      "professionalDocument" -> Json.fromString(professionalDocument)
    )

  private def accessTokenCookieValue(setCookies: List[String]): String =
    setCookies
      .find(_.startsWith("accessToken="))
      .map(_.split(";").head.stripPrefix("accessToken="))
      .getOrElse(fail("No accessToken cookie in response"))

  private def registerAndGetToken(client: org.http4s.client.Client[IO], suffix: String): IO[String] =
    val email = s"clinics-e2e-$suffix-${System.currentTimeMillis}@visoris.com"
    val doc = s"CRMV-CLINICS-$suffix-${System.currentTimeMillis}"
    val req = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "register")
      .withEntity(registerRequestJson(s"Dr. $suffix", email, "Senha@123", doc))
    client.run(req).use { resp =>
      val cookies = resp.headers.headers
        .filter(_.name == org.http4s.headers.`Set-Cookie`.name)
        .map(_.value)
        .toList
      IO(accessTokenCookieValue(cookies))
    }

  private def authedRequest(method: Method, path: List[String], token: String, json: Option[Json]): Request[IO] =
    val req = Request[IO](method = method, uri = path.foldLeft(baseUri)((u, p) => u / p))
      .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("accessToken", token)))
    json.fold(req)(body => req.withEntity(body))

  private def getClinics(client: org.http4s.client.Client[IO], token: String): IO[(Status, String)] =
    client.run(authedRequest(Method.GET, List("api", "v1", "clinics"), token, None)).use { resp =>
      resp.as[String].map(body => (resp.status, body))
    }

  private def postClinic(
    client: org.http4s.client.Client[IO],
    token: String,
    body: Json
  ): IO[(Status, String)] =
    client.run(authedRequest(Method.POST, List("api", "v1", "clinics"), token, Some(body))).use { resp =>
      resp.as[String].map(respBody => (resp.status, respBody))
    }

  private def clinicJson(name: String, cnpj: Option[String] = None): Json =
    val fields = List(
      Some("name" -> Json.fromString(name)),
      cnpj.map(c => "cnpj" -> Json.fromString(c))
    ).flatten
    Json.obj(fields*)

  test("US1: linked doctor sees their clinics; empty list when no links") {
    serverResource.use { client =>
      val cnpj = validCnpj
      for
        token <- registerAndGetToken(client, "list")
        emptyResp <- getClinics(client, token)
        (emptyStatus, emptyBody) = emptyResp
        createResp <- postClinic(client, token, clinicJson("Clínica Lista", Some(cnpj)))
        (createStatus, _) = createResp
        listResp <- getClinics(client, token)
        (listStatus, listBody) = listResp
      yield
        assertEquals(emptyStatus, Status.Ok)
        val emptyJson = parse(emptyBody).getOrElse(fail("Invalid JSON"))
        assertEquals(emptyJson.hcursor.downField("data").downField("clinics").as[List[Json]].getOrElse(Nil), Nil)
        assertEquals(createStatus, Status.Created)
        assertEquals(listStatus, Status.Ok)
        val listJson = parse(listBody).getOrElse(fail("Invalid JSON"))
        val names = listJson.hcursor.downField("data").downField("clinics").as[List[Json]].getOrElse(Nil)
          .flatMap(_.hcursor.downField("name").as[String].toOption)
        assert(names.contains("Clínica Lista"), s"Expected created clinic in list, got $names")
    }
  }

  test("US1: each doctor sees only their own clinics — never another doctor's (no 403)") {
    serverResource.use { client =>
      val cnpj = validCnpj
      for
        tokenA <- registerAndGetToken(client, "isoA")
        tokenB <- registerAndGetToken(client, "isoB")
        createResp <- postClinic(client, tokenA, clinicJson("Clínica do A", Some(cnpj)))
        (createStatus, _) = createResp
        listRespB <- getClinics(client, tokenB)
        (statusB, bodyB) = listRespB
      yield
        assertEquals(createStatus, Status.Created)
        assertEquals(statusB, Status.Ok)
        assert(statusB != Status.Forbidden, "Cross-user access must never return 403")
        val jsonB = parse(bodyB).getOrElse(fail("Invalid JSON"))
        val namesB = jsonB.hcursor.downField("data").downField("clinics").as[List[Json]].getOrElse(Nil)
          .flatMap(_.hcursor.downField("name").as[String].toOption)
        assert(!namesB.contains("Clínica do A"), s"Doctor B must not see Doctor A's clinic, got $namesB")
    }
  }

  test("US1: unauthenticated request returns 401 with no clinic data") {
    serverResource.use { client =>
      val req = Request[IO](method = Method.GET, uri = baseUri / "api" / "v1" / "clinics")
      client.run(req).use { resp =>
        resp.as[String].map(body => (resp.status, body))
      }.flatMap { respPair =>
        val (status, body) = respPair
        IO {
          assertEquals(status, Status.Unauthorized)
          val json = parse(body).getOrElse(fail("Invalid JSON"))
          assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
        }
      }
    }
  }

  test("US2: new clinic (valid CNPJ) is created with 201 and appears in the list") {
    serverResource.use { client =>
      val cnpj = validCnpj
      for
        token <- registerAndGetToken(client, "create")
        createResp <- postClinic(client, token, clinicJson("Clínica Nova", Some(cnpj)))
        (status, body) = createResp
        listResp <- getClinics(client, token)
        (_, listBody) = listResp
      yield
        assertEquals(status, Status.Created)
        val json = parse(body).getOrElse(fail("Invalid JSON"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 201)
        assertEquals(json.hcursor.downField("data").downField("clinic").downField("name").as[String].getOrElse(""), "Clínica Nova")
        assertEquals(json.hcursor.downField("data").downField("clinic").downField("cnpj").as[String].getOrElse(""), cnpj)
        val listJson = parse(listBody).getOrElse(fail("Invalid JSON"))
        val names = listJson.hcursor.downField("data").downField("clinics").as[List[Json]].getOrElse(Nil)
          .flatMap(_.hcursor.downField("name").as[String].toOption)
        assert(names.contains("Clínica Nova"), s"New clinic must appear in the list, got $names")
    }
  }

  test("US2: clinic without CNPJ is created with 201") {
    serverResource.use { client =>
      for
        token <- registerAndGetToken(client, "nocnpj")
        createResp <- postClinic(client, token, clinicJson("Clínica Sem CNPJ"))
        (status, body) = createResp
      yield
        assertEquals(status, Status.Created)
        val json = parse(body).getOrElse(fail("Invalid JSON"))
        assertEquals(json.hcursor.downField("data").downField("clinic").downField("cnpj").focus, Some(Json.Null))
    }
  }

  test("US3: second doctor reuses the existing clinic with 200 and original data") {
    serverResource.use { client =>
      val cnpj = validCnpj
      for
        tokenA <- registerAndGetToken(client, "reuseA")
        tokenB <- registerAndGetToken(client, "reuseB")
        createResp <- postClinic(client, tokenA, clinicJson("Clínica Original", Some(cnpj)))
        (createStatus, _) = createResp
        reuseResp <- postClinic(client, tokenB, clinicJson("Nome Diferente", Some(cnpj)))
        (reuseStatus, reuseBody) = reuseResp
      yield
        assertEquals(createStatus, Status.Created)
        assertEquals(reuseStatus, Status.Ok)
        val json = parse(reuseBody).getOrElse(fail("Invalid JSON"))
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 200)
        assertEquals(
          json.hcursor.downField("data").downField("clinic").downField("name").as[String].getOrElse(""),
          "Clínica Original",
          "Reuse must return the existing clinic's original name, ignoring submitted fields"
        )
    }
  }

  test("US3: same doctor re-submitting the same CNPJ is idempotent (200, no error)") {
    serverResource.use { client =>
      val cnpj = validCnpj
      for
        token <- registerAndGetToken(client, "idem")
        firstResp <- postClinic(client, token, clinicJson("Clínica Idempotente", Some(cnpj)))
        (firstStatus, _) = firstResp
        secondResp <- postClinic(client, token, clinicJson("Clínica Idempotente", Some(cnpj)))
        (secondStatus, secondBody) = secondResp
      yield
        assertEquals(firstStatus, Status.Created)
        assertEquals(secondStatus, Status.Ok)
        val json = parse(secondBody).getOrElse(fail("Invalid JSON"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
    }
  }

  test("US4: missing name returns 400 with field error and nothing is created") {
    serverResource.use { client =>
      for
        token <- registerAndGetToken(client, "noname")
        createResp <- postClinic(client, token, Json.obj())
        (status, body) = createResp
        listResp <- getClinics(client, token)
        (listStatus, listBody) = listResp
      yield
        assertEquals(status, Status.BadRequest)
        val json = parse(body).getOrElse(fail("Invalid JSON"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
        val errors = json.hcursor.downField("data").downField("errors").as[List[Json]].getOrElse(Nil)
        assert(errors.exists(_.hcursor.downField("field").as[String].contains("name")), "Expected name field error")
        val listJson = parse(listBody).getOrElse(fail("Invalid JSON"))
        assertEquals(listJson.hcursor.downField("data").downField("clinics").as[List[Json]].getOrElse(Nil), Nil)
        assertEquals(listStatus, Status.Ok)
    }
  }

  test("US4: CNPJ with invalid check digits returns 400 with field error and nothing is created") {
    serverResource.use { client =>
      for
        token <- registerAndGetToken(client, "badcnpj")
        createResp <- postClinic(client, token, clinicJson("Clínica Inválida", Some("12.345.678/0001-00")))
        (status, body) = createResp
        listResp <- getClinics(client, token)
        (listStatus, listBody) = listResp
      yield
        assertEquals(status, Status.BadRequest)
        val json = parse(body).getOrElse(fail("Invalid JSON"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
        val errors = json.hcursor.downField("data").downField("errors").as[List[Json]].getOrElse(Nil)
        assert(errors.exists(_.hcursor.downField("field").as[String].contains("cnpj")), "Expected cnpj field error")
        val listJson = parse(listBody).getOrElse(fail("Invalid JSON"))
        assertEquals(listJson.hcursor.downField("data").downField("clinics").as[List[Json]].getOrElse(Nil), Nil)
        assertEquals(listStatus, Status.Ok)
    }
  }

  test("404-not-403 rule: the clinics module never returns 403 for cross-user access") {
    serverResource.use { client =>
      val cnpj = validCnpj
      for
        tokenA <- registerAndGetToken(client, "ruleA")
        tokenB <- registerAndGetToken(client, "ruleB")
        _ <- postClinic(client, tokenA, clinicJson("Clínica da Regra", Some(cnpj)))
        listB <- getClinics(client, tokenB)
        (statusB, _) = listB
        listA <- getClinics(client, tokenA)
        (statusA, _) = listA
        reuseResp <- postClinic(client, tokenB, clinicJson("Qualquer", Some(cnpj)))
        (reuseStatus, _) = reuseResp
      yield
        assert(statusB != Status.Forbidden, "List for another doctor's data must not be 403")
        assert(statusA != Status.Forbidden, "Own list must not be 403")
        assert(reuseStatus != Status.Forbidden, "Reuse must not be 403")
        assertEquals(statusB, Status.Ok)
        assertEquals(statusA, Status.Ok)
        assertEquals(reuseStatus, Status.Ok)
    }
  }

