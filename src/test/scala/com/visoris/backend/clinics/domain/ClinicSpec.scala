package com.visoris.backend.clinics.domain

import munit.FunSuite
import java.time.Instant

class ClinicSpec extends FunSuite:

  private val now = Instant.now

  test("creates a clinic with a trimmed name") {
    val clinic = Clinic.create(1L, "  Clínica Vida  ", None, None, None, now, now)
    assertEquals(clinic.map(_.name), Right("Clínica Vida"))
  }

  test("rejects a blank name") {
    assert(Clinic.create(1L, "   ", None, None, None, now, now).isLeft)
    assert(Clinic.create(1L, "", None, None, None, now, now).isLeft)
  }

  test("rejects a name longer than 255 characters") {
    assert(Clinic.create(1L, "a" * 256, None, None, None, now, now).isLeft)
  }

  test("accepts a name with exactly 255 characters") {
    assertEquals(Clinic.create(1L, "a" * 255, None, None, None, now, now).map(_.name), Right("a" * 255))
  }

  test("rejects a name shorter than 2 characters") {
    assert(Clinic.create(1L, "A", None, None, None, now, now).isLeft)
  }

  test("rejects phone not 10 or 11 characters") {
    assert(Clinic.create(1L, "Clínica Vida", None, Some("123456789"), None, now, now).isLeft)
    assert(Clinic.create(1L, "Clínica Vida", None, Some("123456789012"), None, now, now).isLeft)
  }

  test("accepts phone with 10 or 11 characters") {
    assert(Clinic.create(1L, "Clínica Vida", None, Some("1234567890"), None, now, now).isRight)
    assert(Clinic.create(1L, "Clínica Vida", None, Some("12345678901"), None, now, now).isRight)
  }
