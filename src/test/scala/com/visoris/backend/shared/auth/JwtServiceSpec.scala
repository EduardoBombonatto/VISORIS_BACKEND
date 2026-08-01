package com.visoris.backend.shared.auth

import cats.effect.IO
import munit.CatsEffectSuite

class JwtServiceSpec extends CatsEffectSuite:

  private val secret = "test-secret-key-for-unit-tests"
  private val tokenService = TokenService.make(secret)

  private val accessClaims = CustomClaims(userId = "123", role = "DOCTOR", tokenType = "access")
  private val refreshClaims = CustomClaims(userId = "456", role = "DOCTOR", tokenType = "refresh")

  test("createAccessToken should produce a valid JWT string") {
    for token <- tokenService.createAccessToken[IO](accessClaims)
    yield
      assert(token.nonEmpty)
      assert(token.count(_ == '.') == 2)
  }

  test("validateToken should return claims for an access token validated as access") {
    for
      token <- tokenService.createAccessToken[IO](accessClaims)
      result <- tokenService.validateToken[IO](token, "access")
    yield result match
      case Some(claims) =>
        assertEquals(claims.userId, "123")
        assertEquals(claims.role, "DOCTOR")
        assertEquals(claims.tokenType, "access")
      case None =>
        fail("Expected valid access token to validate")
  }

  test("validateToken should reject an access token validated as refresh") {
    for
      token <- tokenService.createAccessToken[IO](accessClaims)
      result <- tokenService.validateToken[IO](token, "refresh")
    yield assert(result.isEmpty)
  }

  test("validateToken should return claims for a refresh token validated as refresh") {
    for
      token <- tokenService.createRefreshToken[IO](refreshClaims)
      result <- tokenService.validateToken[IO](token, "refresh")
    yield result match
      case Some(claims) =>
        assertEquals(claims.userId, "456")
        assertEquals(claims.tokenType, "refresh")
      case None =>
        fail("Expected valid refresh token to validate")
  }

  test("validateToken should return None for a malformed token") {
    for result <- tokenService.validateToken[IO]("invalid.token.here", "access")
    yield assert(result.isEmpty)
  }

  test("validateToken should return None for a token signed with the wrong secret") {
    for
      token <- tokenService.createAccessToken[IO](accessClaims)
      otherService = TokenService.make("wrong-secret")
      result <- otherService.validateToken[IO](token, "access")
    yield assert(result.isEmpty)
  }

  test("generated tokens should carry issuer and subject") {
    for token <- tokenService.createAccessToken[IO](accessClaims)
    yield
      val claim = pdi.jwt.JwtCirce.decode(token, secret, Seq(pdi.jwt.JwtAlgorithm.HS256)).toOption.get
      assertEquals(claim.issuer, Some("api"))
      assertEquals(claim.subject, Some("123"))
  }
