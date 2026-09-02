package com.visoris.backend.clinics.service

import com.visoris.backend.clinics.dto.ClinicRequest
import munit.FunSuite

class ClinicsServiceSpec extends FunSuite:

  test("validateRequest accepts a valid request and trims/normalizes fields") {
    val request = ClinicRequest("  Clínica Vida  ", Some("11.444.777/0001-61"), Some(" (11) 5555-0000 "), Some(" Av. Paulista "))
    val result = ClinicsService.validateRequest(request)
    assertEquals(result.map(_._1), Right("Clínica Vida"))
    assertEquals(result.map(_._2), Right(Some("11444777000161")))
    assertEquals(result.map(_._3), Right(Some("1155550000")))
  }

  test("validateRequest accepts a request without CNPJ") {
    val request = ClinicRequest("Clínica Sem CNPJ", None, None, None)
    val result = ClinicsService.validateRequest(request)
    assertEquals(result.map(_._2), Right(None))
  }

  test("validateRequest rejects a blank name with a field error") {
    val result = ClinicsService.validateRequest(ClinicRequest("   "))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.map(_.field), List("name"))
  }

  test("validateRequest rejects name < 2 characters") {
    val result = ClinicsService.validateRequest(ClinicRequest("A"))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.map(_.field), List("name"))
  }

  test("validateRequest rejects an invalid CNPJ with a field error") {
    val result = ClinicsService.validateRequest(ClinicRequest("Clínica", Some("12.345.678/0001-00")))
    assert(result.isLeft)
    assert(result.left.toOption.get.exists(_.field == "cnpj"))
  }

  test("validateRequest rejects an invalid phone length") {
    val result = ClinicsService.validateRequest(ClinicRequest("Clínica", None, Some("123"), None))
    assert(result.isLeft)
    assert(result.left.toOption.get.exists(_.field == "phone"))
  }

  test("validateRequest rejects an oversized name/address") {
    val result = ClinicsService.validateRequest(
      ClinicRequest("a" * 256, phone = None, address = Some("x" * 501))
    )
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.map(_.field).toSet, Set("name", "address"))
  }

