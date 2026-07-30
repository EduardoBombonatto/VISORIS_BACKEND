package com.visoris.backend.shared.auth

import cats.effect.IO
import munit.CatsEffectSuite

class JwtServiceSpec extends CatsEffectSuite:

  private val secret = "test-secret-key-for-unit-tests"

  test("createAccessToken should produce a valid JWT string") {
    for
      token <- JwtService.createAccessToken[IO](
        secret,
        JwtUserClaims(userId = 123L, email = "test@visoris.com", clinicId = None, roles = List("DOCTOR"))
      )
    yield
      assert(token.nonEmpty)
      assert(token.count(_ == '.') == 2)
  }

  test("decodeAndValidate should return parsed claims for valid token") {
    for
      token <- JwtService.createAccessToken[IO](
        secret,
        JwtUserClaims(userId = 456L, email = "dr@visoris.com", clinicId = Some(789L), roles = List("DOCTOR"))
      )
      result <- JwtService.decodeAndValidate[IO](secret, token)
    yield result match
      case Right(claims) =>
        assertEquals(claims.userId, 456L)
        assertEquals(claims.email, "dr@visoris.com")
        assertEquals(claims.clinicId, Some(789L))
        assertEquals(claims.roles, List("DOCTOR"))
      case Left(err) =>
        fail(s"Expected valid token but got error: $err")
  }

  test("decodeAndValidate should return Left for invalid token") {
    for
      result <- JwtService.decodeAndValidate[IO](secret, "invalid.token.here")
    yield result match
      case Right(_) => fail("Expected error for invalid token")
      case Left(err) => assert(err.contains("Invalid token"))
  }

  test("decodeAndValidate should return Left for token signed with wrong secret") {
    for
      token <- JwtService.createAccessToken[IO](
        secret,
        JwtUserClaims(userId = 1L, email = "a@b.com", clinicId = None, roles = List("DOCTOR"))
      )
      result <- JwtService.decodeAndValidate[IO]("wrong-secret", token)
    yield result match
      case Right(_) => fail("Expected error for wrong secret")
      case Left(err) => assert(err.contains("Invalid token"))
  }
