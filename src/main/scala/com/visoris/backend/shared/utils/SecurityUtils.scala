package com.visoris.backend.shared.utils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SecurityUtils:
  def containsNullByte(value: String): Boolean =
    value.indexOf('\u0000') >= 0

  def asciiDigits(value: String): String =
    value.filter(ch => ch >= '0' && ch <= '9')

  def sha256Hex(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8))
    bytes.map(b => f"$b%02x").mkString

  def maskEmail(email: String): String =
    val parts = email.split("@", -1)
    if parts.length == 2 && parts(0).nonEmpty && parts(1).nonEmpty then
      val local = parts(0)
      val domain = parts(1)
      if local.length <= 2 then s"${local.take(1)}**@$domain"
      else s"${local.take(2)}**@$domain"
    else "<invalid-email>"
