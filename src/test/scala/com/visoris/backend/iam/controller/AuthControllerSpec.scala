package com.visoris.backend.iam.controller

import cats.effect.IO
import cats.effect.Resource
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.iam.service.{AuthService, RegistrationService}
import com.visoris.backend.shared.auth.TokenService
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

  private def loginRequestJson(email: String, password: String): Json =
    Json.obj(
      "email" -> Json.fromString(email),
      "password" -> Json.fromString(password)
    )

  test("T041: happy path — registration returns 201 with user data and Set-Cookie (accessToken + refreshToken)") {
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
        assertEquals(json.hcursor.downField("data").downField("user").downField("fullName").as[String].getOrElse(""), "Dr. Happy Path")
        assert(json.hcursor.downField("data").downField("workspaces").failed, "workspaces must not be present in response")
        val setCookies = headers.headers
          .filter(_.name == org.http4s.headers.`Set-Cookie`.name)
          .map(_.value)
        assert(setCookies.exists(_.contains("refreshToken=")), "Expected refreshToken cookie")
        assert(setCookies.exists(_.contains("accessToken=")), "Expected accessToken cookie")
        assert(setCookies.exists(_.contains("HttpOnly")), "Expected HttpOnly flag")
        assert(setCookies.exists(_.contains("SameSite=Strict")), "Expected SameSite=Strict")
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

  test("registration without professional document fails (professional_document is NOT NULL)") {
    serverResource.use { client =>
      val email = s"e2e-nodoc-${System.currentTimeMillis}@visoris.com"
      val body = registerRequestJson("Dr. No Doc", email, "Senha@123", None)
      for
        response <- makeRequest(client, body)
        (status, _, respBody) = response
      yield
        assert(status != Status.Created, s"Expected registration without professional document to fail, got $status")
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
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
      userRepo = UserRepository.make[IO](xa)
      refreshTokenRepo = RefreshTokenRepository.make[IO](xa)
      registrationService = RegistrationService.make[IO](TokenService.make(jwtSecret), xa, userRepo, refreshTokenRepo)
      authService = AuthService.make[IO](TokenService.make(jwtSecret), xa, userRepo, refreshTokenRepo)
      authRoutes = AuthController.routes[IO](registrationService, authService)
      httpApp = authRoutes.orNotFound
      _ <- EmberServerBuilder.default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"18080")
        .withHttpApp(httpApp)
        .build
      client <- EmberClientBuilder.default[IO].build
    yield client

  test("login: happy path returns 200 with user data and Set-Cookie (accessToken + refreshToken)") {
    serverResource.use { client =>
      val email = s"e2e-login-${System.currentTimeMillis}@visoris.com"
      val password = "Senha@123"
      val doc = s"CRMV-LOGIN-${System.currentTimeMillis}"
      val registerBody = registerRequestJson("Dr. Login", email, password, Some(doc))
      for
        _ <- makeRequest(client, registerBody)
        loginJson = loginRequestJson(email, password)
        loginReq = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "login")
          .withEntity(loginJson)
        loginResp <- client.run(loginReq).use { resp =>
          resp.as[String].map(body => (resp.status, resp.headers, body))
        }
      yield
        val (status, headers, respBody) = loginResp
        assertEquals(status, Status.Ok)
        val json = parse(respBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 200)
        assertEquals(json.hcursor.downField("data").downField("user").downField("fullName").as[String].getOrElse(""), "Dr. Login")
        assert(json.hcursor.downField("data").downField("workspaces").failed, "workspaces must not be present in response")
        val setCookies = headers.headers
          .filter(_.name == org.http4s.headers.`Set-Cookie`.name)
          .map(_.value)
        assert(setCookies.exists(_.contains("refreshToken=")), "Expected refreshToken cookie")
        assert(setCookies.exists(_.contains("accessToken=")), "Expected accessToken cookie")
        assert(setCookies.exists(_.contains("HttpOnly")), "Expected HttpOnly flag")
        assert(setCookies.exists(_.contains("SameSite=Strict")), "Expected SameSite=Strict")
    }
  }

  test("refresh: returns expiresIn and new Set-Cookie (accessToken + refreshToken); old token reuse within grace window rotates again") {
    serverResource.use { client =>
      val email = s"e2e-refresh-${System.currentTimeMillis}@visoris.com"
      val password = "Senha@123"
      val doc = s"CRMV-REFRESH-${System.currentTimeMillis}"
      val registerBody = registerRequestJson("Dr. Refresh", email, password, Some(doc))
      for
        _ <- makeRequest(client, registerBody)
        loginJson = loginRequestJson(email, password)
        loginReq = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "login")
          .withEntity(loginJson)
        loginResp <- client.run(loginReq).use { resp =>
          resp.as[String].map(body => (resp.headers, body))
        }
        (loginHeaders, _) = loginResp
        oldCookie = loginHeaders.headers
          .find(h => h.name == org.http4s.headers.`Set-Cookie`.name && h.value.contains("refreshToken="))
          .getOrElse(fail("No refreshToken cookie in login response"))
        oldCookieValue = oldCookie.value.split(";").headOption.filter(_.startsWith("refreshToken=")).map(_.stripPrefix("refreshToken=")).getOrElse("")

        refreshReq: Request[IO] = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "refresh")
          .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("refreshToken", oldCookieValue)))
        refreshResp <- client.run(refreshReq).use { resp =>
          resp.as[String].map(body => (resp.status, resp.headers, body))
        }
        (refreshStatus, refreshHeaders, refreshBody) = refreshResp

        _ <- IO(assertEquals(refreshStatus, Status.Ok))
        refreshJson = parse(refreshBody).getOrElse(fail("Invalid JSON response"))
        _ = assertEquals(refreshJson.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        _ = assertEquals(refreshJson.hcursor.downField("data").downField("expiresIn").as[Int].getOrElse(0), 900)

        setCookies = refreshHeaders.headers
          .filter(_.name == org.http4s.headers.`Set-Cookie`.name)
          .map(_.value)
        _ = assert(setCookies.exists(_.contains("accessToken=")), "Expected accessToken cookie in response")
        _ = assert(setCookies.exists(_.contains("refreshToken=")), "Expected refreshToken cookie in response")

        newCookie = refreshHeaders.headers
          .find(h => h.name == org.http4s.headers.`Set-Cookie`.name && h.value.contains("refreshToken="))
          .getOrElse(fail("No refreshToken cookie found"))
        newCookieValue = newCookie.value.split(";").headOption.filter(_.startsWith("refreshToken=")).getOrElse("")
        _ = assert(newCookieValue != oldCookieValue, "New refresh token cookie should differ from old one")

        replayReq: Request[IO] = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "refresh")
          .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("refreshToken", oldCookieValue)))
        replayResp <- client.run(replayReq).use { resp =>
          resp.as[String].map(body => (resp.status, resp.headers, body))
        }
        (replayStatus, replayHeaders, replayBody) = replayResp
      yield
        assertEquals(replayStatus, Status.Ok)
        val replayJson = parse(replayBody).getOrElse(fail("Invalid JSON response"))
        assertEquals(replayJson.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assertEquals(replayJson.hcursor.downField("data").downField("expiresIn").as[Int].getOrElse(0), 900)
        val replayCookies = replayHeaders.headers
          .filter(_.name == org.http4s.headers.`Set-Cookie`.name)
          .map(_.value)
        assert(replayCookies.exists(_.contains("accessToken=")), "Expected accessToken cookie on grace-window replay")
        assert(replayCookies.exists(_.contains("refreshToken=")), "Expected refreshToken cookie on grace-window replay")
        val replayCookieValue = replayHeaders.headers
          .find(h => h.name == org.http4s.headers.`Set-Cookie`.name && h.value.contains("refreshToken="))
          .getOrElse(fail("No refreshToken cookie on replay"))
          .value.split(";").headOption.filter(_.startsWith("refreshToken=")).map(_.stripPrefix("refreshToken=")).getOrElse("")
        assert(replayCookieValue != newCookieValue, "Grace-window replay should rotate yet another refresh token")
    }
  }

  test("refresh: no cookie returns 401") {
    serverResource.use { client =>
      val req = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "refresh")
      client.run(req).use { resp =>
        IO(assertEquals(resp.status, Status.Unauthorized))
      }
    }
  }

  test("login: wrong password returns 401 with generic message") {
    serverResource.use { client =>
      val email = s"e2e-badpw-${System.currentTimeMillis}@visoris.com"
      val doc = s"CRMV-BADPW-${System.currentTimeMillis}"
      val registerBody = registerRequestJson("Dr. BadPass", email, "Correct@1", Some(doc))
      for
        _ <- makeRequest(client, registerBody)
        loginJson = loginRequestJson(email, "WrongPassword")
        loginReq = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "login")
          .withEntity(loginJson)
        resp <- client.run(loginReq).use { r => r.as[String].map(body => (r.status, r.headers, body)) }
      yield
        val (status, headers, body) = resp
        assertEquals(status, Status.Unauthorized)
        val json = parse(body).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(false), true)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 401)
        assert(headers.get(org.http4s.headers.`Set-Cookie`.name).isEmpty)
    }
  }

  test("login: unknown email returns 401 with same generic message") {
    serverResource.use { client =>
      val loginJson = loginRequestJson(s"nonexistent-${System.currentTimeMillis}@visoris.com", "AnyPass@1")
      val loginReq = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "login")
        .withEntity(loginJson)
      client.run(loginReq).use { r => r.as[String].map(body => (r.status, body)) }.map { (status, body) =>
        assertEquals(status, Status.Unauthorized)
        val json = parse(body).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("message").as[String].getOrElse(""), "Credenciais inválidas.")
      }
    }
  }

  private val logoutUri = baseUri / "api" / "v1" / "auth" / "logout"

  private def setCookieValues(headers: org.http4s.Headers): List[String] =
    headers.headers.filter(_.name == org.http4s.headers.`Set-Cookie`.name).map(_.value)

  private def hasClearCookie(cookies: List[String], name: String, path: String): Boolean =
    cookies.exists(c => c.contains(s"$name=") && c.contains(s"Path=$path") && c.contains("Max-Age=0"))

  private def refreshTokenCookieFrom(headers: org.http4s.Headers): String =
    headers.headers
      .find(h => h.name == org.http4s.headers.`Set-Cookie`.name && h.value.contains("refreshToken="))
      .map(_.value.split(";").headOption.filter(_.startsWith("refreshToken=")).map(_.stripPrefix("refreshToken=")).getOrElse(""))
      .getOrElse(fail("No refreshToken cookie in response"))

  private def registerAndLogin(client: org.http4s.client.Client[IO]): IO[String] =
    val email = s"e2e-logout-${System.currentTimeMillis}@visoris.com"
    val password = "Senha@123"
    val doc = s"CRMV-LOGOUT-${System.currentTimeMillis}"
    val registerBody = registerRequestJson("Dr. Logout", email, password, Some(doc))
    for
      _ <- makeRequest(client, registerBody)
      loginJson = loginRequestJson(email, password)
      loginReq = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "login")
        .withEntity(loginJson)
      loginResp <- client.run(loginReq).use { resp => resp.as[String].map(body => (resp.headers, body)) }
      (loginHeaders, _) = loginResp
    yield refreshTokenCookieFrom(loginHeaders)

  test("logout: active session returns 200 with envelope and clears both session cookies") {
    serverResource.use { client =>
      for
        refreshCookieVal <- registerAndLogin(client)
        logoutReq = Request[IO](method = Method.POST, uri = logoutUri)
          .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("refreshToken", refreshCookieVal)))
        logoutResp <- client.run(logoutReq).use { resp => resp.as[String].map(body => (resp.status, resp.headers, body)) }
        (status, headers, body) = logoutResp
      yield
        assertEquals(status, Status.Ok)
        val json = parse(body).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assertEquals(json.hcursor.downField("httpcode").as[Int].getOrElse(0), 200)
        assertEquals(json.hcursor.downField("data").focus, Some(Json.Null))
        val sc = setCookieValues(headers)
        assert(hasClearCookie(sc, "accessToken", "/api/v1"), s"accessToken clearing cookie missing in $sc")
        assert(hasClearCookie(sc, "refreshToken", "/"), s"refreshToken clearing cookie missing in $sc")
    }
  }

  test("logout: presented refresh token is revoked and rejected at refresh") {
    serverResource.use { client =>
      for
        refreshCookieVal <- registerAndLogin(client)
        logoutReq = Request[IO](method = Method.POST, uri = logoutUri)
          .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("refreshToken", refreshCookieVal)))
        logoutResp <- client.run(logoutReq).use { resp => resp.as[String].map(body => (resp.status, body)) }
        (logoutStatus, _) = logoutResp
        _ <- IO(assertEquals(logoutStatus, Status.Ok))
        refreshReq = Request[IO](method = Method.POST, uri = baseUri / "api" / "v1" / "auth" / "refresh")
          .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("refreshToken", refreshCookieVal)))
        refreshResp <- client.run(refreshReq).use { resp => resp.as[String].map(body => (resp.status, body)) }
        (refreshStatus, _) = refreshResp
      yield
        assertEquals(refreshStatus, Status.Unauthorized)
    }
  }

  test("logout: no cookies still returns 200 with clearing headers") {
    serverResource.use { client =>
      val req = Request[IO](method = Method.POST, uri = logoutUri)
      client.run(req).use { resp => resp.as[String].map(body => (resp.status, resp.headers, body)) }.map { (status, headers, body) =>
        assertEquals(status, Status.Ok)
        val json = parse(body).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        val sc = setCookieValues(headers)
        assert(hasClearCookie(sc, "accessToken", "/api/v1"), s"accessToken clearing cookie missing in $sc")
        assert(hasClearCookie(sc, "refreshToken", "/"), s"refreshToken clearing cookie missing in $sc")
      }
    }
  }

  test("logout: unknown or revoked refresh token still returns 200 (idempotent)") {
    serverResource.use { client =>
      val logoutReq = Request[IO](method = Method.POST, uri = logoutUri)
        .putHeaders(org.http4s.headers.Cookie(org.http4s.RequestCookie("refreshToken", "unknown-token-value")))
      for
        first <- client.run(logoutReq).use { resp => resp.as[String].map(body => (resp.status, resp.headers, body)) }
        (firstStatus, firstHeaders, _) = first
        second <- client.run(logoutReq).use { resp => resp.as[String].map(body => (resp.status, resp.headers, body)) }
        (secondStatus, secondHeaders, _) = second
      yield
        assertEquals(firstStatus, Status.Ok)
        assertEquals(secondStatus, Status.Ok)
        val sc1 = setCookieValues(firstHeaders)
        val sc2 = setCookieValues(secondHeaders)
        assert(hasClearCookie(sc1, "refreshToken", "/"), s"refreshToken clearing cookie missing in $sc1")
        assert(hasClearCookie(sc2, "refreshToken", "/"), s"refreshToken clearing cookie missing in $sc2")
    }
  }

  test("logout: unexpected JSON body is ignored and returns 200") {
    serverResource.use { client =>
      val req = Request[IO](method = Method.POST, uri = logoutUri)
        .withEntity(Json.obj("foo" -> Json.fromString("bar")))
      client.run(req).use { resp => resp.as[String].map(body => (resp.status, resp.headers, body)) }.map { (status, headers, body) =>
        assertEquals(status, Status.Ok)
        val json = parse(body).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assert(hasClearCookie(setCookieValues(headers), "refreshToken", "/"), "refreshToken clearing cookie missing")
      }
    }
  }

  test("logout: malformed content is ignored and returns 200") {
    serverResource.use { client =>
      val req = Request[IO](method = Method.POST, uri = logoutUri)
        .withEntity("{\"malformed\":")
      client.run(req).use { resp => resp.as[String].map(body => (resp.status, resp.headers, body)) }.map { (status, headers, body) =>
        assertEquals(status, Status.Ok)
        val json = parse(body).getOrElse(fail("Invalid JSON response"))
        assertEquals(json.hcursor.downField("erro").as[Boolean].getOrElse(true), false)
        assert(hasClearCookie(setCookieValues(headers), "refreshToken", "/"), "refreshToken clearing cookie missing")
      }
    }
  }
