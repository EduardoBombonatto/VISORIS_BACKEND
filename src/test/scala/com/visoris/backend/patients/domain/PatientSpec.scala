package com.visoris.backend.patients.domain

import io.circe.Json
import java.time.{Instant, LocalDate}
import munit.FunSuite

class PatientSpec extends FunSuite:

  private val now = Instant.now()
  private val validDate = LocalDate.of(2022, 5, 10)
  private val validJson = Json.obj("species" -> Json.fromString("Canino"))

  test("Patient.create accepts valid parameters") {
    val result = Patient.create(
      id = 100L,
      clientId = Some(200L),
      name = "Rex",
      patientType = PatientType.PET,
      birthDate = Some(validDate),
      biologicalDetails = validJson,
      createdAt = now
    )
    assert(result.isRight)
    val patient = result.toOption.get
    assertEquals(patient.id, 100L)
    assertEquals(patient.clientId, Some(200L))
    assertEquals(patient.name, "Rex")
    assertEquals(patient.patientType, PatientType.PET)
  }

  test("Patient.create rejects blank name or names exceeding 255 chars") {
    assert(Patient.create(1L, Some(2L), "   ", PatientType.PET, None, validJson, now).isLeft)
    assert(Patient.create(1L, Some(2L), "a" * 256, PatientType.HUMAN, None, validJson, now).isLeft)
  }

  test("Patient.create rejects future birth dates") {
    val futureDate = LocalDate.now().plusDays(5)
    val result = Patient.create(1L, Some(2L), "Rex", PatientType.PET, Some(futureDate), validJson, now)
    assert(result.isLeft)
  }
