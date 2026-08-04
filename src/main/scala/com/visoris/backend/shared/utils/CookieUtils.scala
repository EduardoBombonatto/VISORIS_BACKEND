package com.visoris.backend.shared.utils

import org.http4s.{ResponseCookie, SameSite}

object CookieUtils:
  def createRefreshCookie(refreshToken: String, isSecure: Boolean = false): ResponseCookie =
    ResponseCookie(
      name = "refreshToken",
      content = refreshToken,
      httpOnly = true,
      secure = isSecure,
      sameSite = Some(SameSite.Strict),
      path = Some("/api/v1/auth"),
      maxAge = Some(604800L)
    )
