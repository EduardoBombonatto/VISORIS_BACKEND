package com.visoris.backend.clinics.service

import com.visoris.backend.clinics.dto.CreateClinicRequest
import munit.FunSuite

class ClinicsServiceSpec extends FunSuite:

  test("validateRequest accepts a valid request and trims/normalizes fields") {
    val request = CreateClinicRequest("  Clínica Vida  ", Some("11.444.777/0001-61"), Some(" (11) 5555-0000 "), Some(" Av. Paulista "))
    val result = ClinicsService.validateRequest(request)
    assertEquals(result.map(_._1), Right("Clínica Vida"))
    assertEquals(result.map(_._2), Right(Some("11444777000161")))
    assertEquals(result.map(_._3), Right(Some("(11) 5555-0000")))
  }

  test("validateRequest accepts a request without CNPJ") {
    val request = CreateClinicRequest("Clínica Sem CNPJ", None, None, None)
    val result = ClinicsService.validateRequest(request)
    assertEquals(result.map(_._2), Right(None))
  }

  test("validateRequest rejects a blank name with a field error") {
    val result = ClinicsService.validateRequest(CreateClinicRequest("   "))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.map(_.field), List("name"))
  }

  test("validateRequest rejects an invalid CNPJ with a field error") {
    val result = ClinicsService.validateRequest(CreateClinicRequest("Clínica", Some("12.345.678/0001-00")))
    assert(result.isLeft)
    assert(result.left.toOption.get.exists(_.field == "cnpj"))
  }

  test("validateRequest rejects an oversized name/phone/address") {
    val result = ClinicsService.validateRequest(
      CreateClinicRequest("a" * 256, phone = Some("p" * 21), address = Some("x" * 501))
    )
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.map(_.field).toSet, Set("name", "phone", "address"))
  }
