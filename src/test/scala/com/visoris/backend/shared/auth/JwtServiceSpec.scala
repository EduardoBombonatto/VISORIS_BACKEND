package com.visoris.backend.shared.auth

import cats.effect.IO
import munit.CatsEffectSuite

class JwtServiceSpec extends CatsEffectSuite:

  private val secret = "test-secret-key-for-unit-tests"
  private val tokenService = TokenService.make(secret)

  private val baseClaims = CustomClaims(
    userId = "123",
    email = "dr.eduardo@visoris.com",
    roles = List("DOCTOR"),
    clinicId = None,
    tokenType = "BASE"
  )
  private val accessClaims = CustomClaims(
    userId = "456",
    email = "dr.ana@visoris.com",
    roles = List("DOCTOR", "ADMIN"),
    clinicId = Some("99"),
    tokenType = "ACCESS"
  )

  test("createBaseToken should produce a valid JWT string") {
    for token <- tokenService.createBaseToken[IO](baseClaims)
    yield
      assert(token.nonEmpty)
      assert(token.count(_ == '.') == 2)
  }

  test("createAccessToken should produce a valid JWT string") {
    for token <- tokenService.createAccessToken[IO](accessClaims)
    yield
      assert(token.nonEmpty)
      assert(token.count(_ == '.') == 2)
  }

  test("validateToken should return claims for a base token validated as BASE") {
    for
      token <- tokenService.createBaseToken[IO](baseClaims)
      result <- tokenService.validateToken[IO](token, "BASE")
    yield result match
      case Some(claims) =>
        assertEquals(claims.userId, "123")
        assertEquals(claims.email, "dr.eduardo@visoris.com")
        assertEquals(claims.roles, List("DOCTOR"))
        assertEquals(claims.clinicId, None)
        assertEquals(claims.tokenType, "BASE")
      case None =>
        fail("Expected valid base token to validate")
  }

  test("validateToken should reject a base token validated as ACCESS") {
    for
      token <- tokenService.createBaseToken[IO](baseClaims)
      result <- tokenService.validateToken[IO](token, "ACCESS")
    yield assert(result.isEmpty)
  }

  test("validateToken should return claims for an access token validated as ACCESS") {
    for
      token <- tokenService.createAccessToken[IO](accessClaims)
      result <- tokenService.validateToken[IO](token, "ACCESS")
    yield result match
      case Some(claims) =>
        assertEquals(claims.userId, "456")
        assertEquals(claims.email, "dr.ana@visoris.com")
        assertEquals(claims.roles, List("DOCTOR", "ADMIN"))
        assertEquals(claims.clinicId, Some("99"))
        assertEquals(claims.tokenType, "ACCESS")
      case None =>
        fail("Expected valid access token to validate")
  }

  test("validateToken should reject an access token validated as BASE") {
    for
      token <- tokenService.createAccessToken[IO](accessClaims)
      result <- tokenService.validateToken[IO](token, "BASE")
    yield assert(result.isEmpty)
  }

  test("createBaseToken should always force clinicId to None") {
    for
      token <- tokenService.createBaseToken[IO](accessClaims)
      result <- tokenService.validateToken[IO](token, "BASE")
    yield result match
      case Some(claims) =>
        assertEquals(claims.clinicId, None)
        assertEquals(claims.tokenType, "BASE")
      case None =>
        fail("Expected forced base token to validate")
  }

  test("validateToken should return None for a malformed token") {
    for result <- tokenService.validateToken[IO]("invalid.token.here", "ACCESS")
    yield assert(result.isEmpty)
  }

  test("validateToken should return None for a token signed with the wrong secret") {
    for
      token <- tokenService.createBaseToken[IO](baseClaims)
      otherService = TokenService.make("wrong-secret")
      result <- otherService.validateToken[IO](token, "BASE")
    yield assert(result.isEmpty)
  }

  test("generated tokens should carry issuer and subject") {
    for token <- tokenService.createBaseToken[IO](baseClaims)
    yield
      val claim = pdi.jwt.JwtCirce.decode(token, secret, Seq(pdi.jwt.JwtAlgorithm.HS256)).toOption.get
      assertEquals(claim.issuer, Some("api"))
      assertEquals(claim.subject, Some("123"))
  }

class OpaqueTokenGeneratorSpec extends CatsEffectSuite:

  test("generate should return a 64-character lowercase hex string") {
    for token <- OpaqueTokenGenerator.generate[IO]
    yield
      assertEquals(token.length, 64)
      assert(token.forall(ch => ch.isDigit || (ch >= 'a' && ch <= 'f')))
  }

  test("generate should produce unique tokens across calls") {
    for
      first <- OpaqueTokenGenerator.generate[IO]
      second <- OpaqueTokenGenerator.generate[IO]
    yield assertNotEquals(first, second)
  }
