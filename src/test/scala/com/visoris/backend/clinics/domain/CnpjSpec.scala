package com.visoris.backend.clinics.domain

import munit.FunSuite

class CnpjSpec extends FunSuite:

  test("accepts a valid CNPJ with formatting and normalizes to digits") {
    assertEquals(Cnpj.validate("11.444.777/0001-61"), Right("11444777000161"))
  }

  test("accepts a valid CNPJ provided as digits only") {
    assertEquals(Cnpj.validate("11444777000161"), Right("11444777000161"))
  }

  test("normalize strips formatting") {
    assertEquals(Cnpj.normalize("11.444.777/0001-61"), "11444777000161")
    assertEquals(Cnpj.normalize(" 11.444.777/0001-61 "), "11444777000161")
  }

  test("rejects a CNPJ with invalid check digits") {
    assert(Cnpj.validate("11.444.777/0001-95").isLeft)
    assert(Cnpj.validate("11444777000195").isLeft)
  }

  test("rejects a CNPJ shorter than 14 digits") {
    assert(Cnpj.validate("1234567890123").isLeft)
  }

  test("rejects a CNPJ longer than 14 digits") {
    assert(Cnpj.validate("123456789012345").isLeft)
  }

  test("rejects repeated-digit CNPJs") {
    assert(Cnpj.validate("00000000000000").isLeft)
    assert(Cnpj.validate("11111111111111").isLeft)
  }

  test("rejects blank input") {
    assert(Cnpj.validate("").isLeft)
    assert(Cnpj.validate("   ").isLeft)
  }
