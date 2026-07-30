package com.visoris.backend.iam.controller

import cats.effect.IO
import cats.effect.Resource
import com.visoris.backend.iam.infrastructure.{RefreshTokenRepositoryImpl, UserRepositoryImpl}
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.parser.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import com.comcast.ip4s.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class AuthControllerSpec extends CatsEffectSuite:

  private val dbUrl  = sys.env.getOrElse("DB_URL", "jdbc:postgresql://localhost:5432/visoris_db")
  private val dbUser = sys.env.getOrElse("DB_USER", "postgres")
  private val dbPass = sys.env.getOrElse("DB_PASSWORD", "Visoris@123.")
  private val jwtSecret = "test-secret-for-e2e-tests"

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val baseUri = uri"http://localhost:18080"

  private def registerRequestJson(
    fullName: String,
    email: String,
    password: String,
    professionalDocument: Option[String] = None
  ): Json =
    val fields = List(
      Some("fullName" -> Json.fromString(fullName)),
      Some("email" -> Json.fromString(email)),
      Some("password" -> Json.fromString(password)),
      professionalDocument.map(d => "professionalDocument" -> Json.fromString(d))
    ).flatten
    Json.obj(fields*)

  private def makeRequest(
    client: org.http4s.client.Client[IO],
    json: Json
  ): IO[(Status, org.http4s.Headers, String)] =
    val req = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "register")
      .withEntity(json)
    client.run(req).use { resp =>
      resp.as[String].map(body => (resp.status, resp.headers, body))
    }

  test("T041: happy path — registration returns 201 with baseToken, user data, and Set-Cookie") {
    serverResource.use { client =>
      val email = s"e2e-happy-${System.currentTimeMillis}@visoris.com"
      val doc = s"CRMV-E2E-HAPPY-${System.currentTimeMillis}"
      val body = registerRequestJson("Dr. Happy Path", email, "Senha@123", Some(doc))
      for
        response <- makeRequest(client, body)
        (status, headers, respBody) = response
      yield
        assertEquals(status, Status.Created)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 201)
        assert(json.hcursor.downField("data").downField("baseToken").as[String].getOrElse("").nonEmpty)
        assertEquals(json.hcursor.downField("data").downField("user").downField("fullName").as[String].getOrElse(""), "Dr. Happy Path")
        val workspaces = json.hcursor.downField("data").downField("workspaces").as[List[String]].getOrElse(null)
        assert(workspaces == List.empty)
        val setCookie = headers.get(org.http4s.headers.`Set-Cookie`.name)
        assert(setCookie.isDefined)
        assert(setCookie.get.head.value.contains("HttpOnly"))
        assert(setCookie.get.head.value.contains("Secure"))
        assert(setCookie.get.head.value.contains("SameSite=Strict"))
        assert(setCookie.get.head.value.contains("refreshToken="))
    }
  }

  test("T039: duplicate email returns 409 Conflict") {
    serverResource.use { client =>
      val email = s"e2e-dup-${System.currentTimeMillis}@visoris.com"
      val body = registerRequestJson("Dr. Dup", email, "Senha@123", Some(s"CRMV-DUP-${System.currentTimeMillis}"))

      for
        _ <- makeRequest(client, body)
        duplicateResp <- makeRequest(client, body)
        (status, _, respBody) = duplicateResp
      yield
        assertEquals(status, Status.Conflict)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 409)
    }
  }

  test("T042: validation error — weak password returns 400 with field-level errors") {
    serverResource.use { client =>
      val email = s"e2e-weak-${System.currentTimeMillis}@visoris.com"
      val body = registerRequestJson("Dr. Weak", email, "12345")
      for
        response <- makeRequest(client, body)
        (status, _, respBody) = response
      yield
        assertEquals(status, Status.BadRequest)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 400)
        val errors = json.hcursor.downField("data").downField("errors").as[List[Json]].getOrElse(Nil)
        assert(errors.nonEmpty, "Expected validation errors in response")
        val hasPasswordError = errors.exists { e =>
          e.hcursor.downField("field").as[String].getOrElse("") == "password"
        }
        assert(hasPasswordError, "Expected at least one password validation error")
    }
  }

  test("duplicate professional document returns 400 with specific error") {
    serverResource.use { client =>
      val doc = s"CRMV-E2E-PROFDUP-${System.currentTimeMillis}"
      val user1 = registerRequestJson("Dr. Doc Dup 1", s"e2e-pd1-${System.currentTimeMillis}@visoris.com", "Senha@123", Some(doc))
      val user2 = registerRequestJson("Dr. Doc Dup 2", s"e2e-pd2-${System.currentTimeMillis}@visoris.com", "Senha@123", Some(doc))

      for
        _ <- makeRequest(client, user1)
        response <- makeRequest(client, user2)
        (status, _, respBody) = response
      yield
        assertEquals(status, Status.BadRequest)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        val errors = json.hcursor.downField("data").downField("errors").as[List[Json]].getOrElse(Nil)
        val hasProfDocError = errors.exists { e =>
          e.hcursor.downField("field").as[String].getOrElse("") == "professionalDocument"
        }
        assert(hasProfDocError, "Expected professionalDocument validation error")
    }
  }

  test("registration with missing required fields returns 400") {
    serverResource.use { client =>
      val body = registerRequestJson("", s"e2e-empty-${System.currentTimeMillis}@visoris.com", "Senha@123")
      for
        response <- makeRequest(client, body)
        (status, _, respBody) = response
      yield
        assertEquals(status, Status.BadRequest)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        val errors = json.hcursor.downField("data").downField("errors").as[List[Json]].getOrElse(Nil)
        val hasFullNameError = errors.exists { e =>
          e.hcursor.downField("field").as[String].getOrElse("") == "fullName"
        }
        assert(hasFullNameError, "Expected fullName validation error")
    }
  }

  test("registration without professional document succeeds (optional field)") {
    serverResource.use { client =>
      val email = s"e2e-nodoc-${System.currentTimeMillis}@visoris.com"
      val body = registerRequestJson("Dr. No Doc", email, "Senha@123", None)
      for
        response <- makeRequest(client, body)
        (status, _, respBody) = response
      yield
        assertEquals(status, Status.Created)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        val profDoc = json.hcursor.downField("data").downField("user").downField("professionalDocument").as[Option[String]].getOrElse(Some("NOT-NULL"))
        assert(profDoc.isEmpty, "Expected professionalDocument to be null when omitted")
    }
  }

  private def serverResource: Resource[IO, org.http4s.client.Client[IO]] =
    import cats.effect.Sync
    import doobie.util.ExecutionContexts
    import org.flywaydb.core.Flyway
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](2)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver", dbUrl, dbUser, dbPass, ce
      )
      _ <- Resource.eval(xa.configure { dataSource =>
        Sync[IO].delay { Flyway.configure().dataSource(dataSource).load().migrate(); () }
      })
      given UserRepository = UserRepositoryImpl()
      given RefreshTokenRepository = RefreshTokenRepositoryImpl()
      authController = AuthController[IO](jwtSecret, xa)
      httpApp = authController.routes.orNotFound
      _ <- EmberServerBuilder.default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"18080")
        .withHttpApp(httpApp)
        .build
      client <- EmberClientBuilder.default[IO].build
    yield client
