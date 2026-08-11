package com.visoris.backend.shared.utils

import org.http4s.{ResponseCookie, SameSite}

object CookieUtils:
  private val secure = true

  def createRefreshCookie(refreshToken: String): ResponseCookie =
    ResponseCookie(
      name = "refreshToken",
      content = refreshToken,
      httpOnly = true,
      secure = secure,
      sameSite = Some(SameSite.Strict),
      path = Some("/api/v1/auth"),
      maxAge = Some(604800L)
    )

  def createBaseTokenCookie(baseToken: String): ResponseCookie =
    ResponseCookie(
      name = "baseToken",
      content = baseToken,
      httpOnly = true,
      secure = secure,
      sameSite = Some(SameSite.Strict),
      path = Some("/api/v1/auth/workspace"),
      maxAge = Some(300L)
    )

  def createAccessTokenCookie(accessToken: String): ResponseCookie =
    ResponseCookie(
      name = "accessToken",
      content = accessToken,
      httpOnly = true,
      secure = secure,
      sameSite = Some(SameSite.Strict),
      path = Some("/api/v1"),
      maxAge = Some(900L)
    )

  def clearBaseTokenCookie: ResponseCookie =
    ResponseCookie(
      name = "baseToken",
      content = "",
      httpOnly = true,
      secure = secure,
      sameSite = Some(SameSite.Strict),
      path = Some("/api/v1/auth/workspace"),
      maxAge = Some(0L)
    )
