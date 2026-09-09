package com.visoris.backend.patients.domain

import munit.FunSuite

class PatientTypeSpec extends FunSuite:

  test("fromString parses PET case-insensitively") {
    assertEquals(PatientType.fromString("PET"), Right(PatientType.PET))
    assertEquals(PatientType.fromString("pet"), Right(PatientType.PET))
    assertEquals(PatientType.fromString(" Pet "), Right(PatientType.PET))
  }

  test("fromString parses HUMAN case-insensitively") {
    assertEquals(PatientType.fromString("HUMAN"), Right(PatientType.HUMAN))
    assertEquals(PatientType.fromString("human"), Right(PatientType.HUMAN))
    assertEquals(PatientType.fromString(" Human "), Right(PatientType.HUMAN))
  }

  test("fromString returns Left on invalid value") {
    assert(PatientType.fromString("ALIEN").isLeft)
    assert(PatientType.fromString("").isLeft)
    assert(PatientType.fromString("   ").isLeft)
  }

  test("toString produces exact uppercase database enum value") {
    assertEquals(PatientType.PET.toString, "PET")
    assertEquals(PatientType.HUMAN.toString, "HUMAN")
  }
