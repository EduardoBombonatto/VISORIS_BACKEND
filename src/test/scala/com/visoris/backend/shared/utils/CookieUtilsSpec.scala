package com.visoris.backend.shared.utils

import munit.FunSuite
import org.http4s.SameSite

class CookieUtilsSpec extends FunSuite:

  test("clearAccessTokenCookie clears accessToken at Path=/api/v1 with Max-Age=0") {
    val c = CookieUtils.clearAccessTokenCookie
    assertEquals(c.name, "accessToken")
    assertEquals(c.content, "")
    assertEquals(c.maxAge, Some(0L))
    assertEquals(c.path, Some("/api/v1"))
    assert(c.httpOnly, "accessToken clearing cookie must be HttpOnly")
    assert(c.secure, "accessToken clearing cookie must be Secure")
    assertEquals(c.sameSite, Some(SameSite.Strict))
  }

  test("clearRefreshTokenCookie clears refreshToken at Path=/api/v1/auth with Max-Age=0") {
    val c = CookieUtils.clearRefreshTokenCookie
    assertEquals(c.name, "refreshToken")
    assertEquals(c.content, "")
    assertEquals(c.maxAge, Some(0L))
    assertEquals(c.path, Some("/api/v1/auth"))
    assert(c.httpOnly, "refreshToken clearing cookie must be HttpOnly")
    assert(c.secure, "refreshToken clearing cookie must be Secure")
    assertEquals(c.sameSite, Some(SameSite.Strict))
  }

  test("clearBaseTokenCookie clears baseToken at Path=/api/v1/auth/workspace with Max-Age=0") {
    val c = CookieUtils.clearBaseTokenCookie
    assertEquals(c.name, "baseToken")
    assertEquals(c.content, "")
    assertEquals(c.maxAge, Some(0L))
    assertEquals(c.path, Some("/api/v1/auth/workspace"))
    assert(c.httpOnly, "baseToken clearing cookie must be HttpOnly")
    assert(c.secure, "baseToken clearing cookie must be Secure")
    assertEquals(c.sameSite, Some(SameSite.Strict))
  }
