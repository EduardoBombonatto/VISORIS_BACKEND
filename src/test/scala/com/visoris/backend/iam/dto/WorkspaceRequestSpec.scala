package com.visoris.backend.iam.dto

import munit.FunSuite

class WorkspaceRequestSpec extends FunSuite:
  test("valid clinicId returns no errors") {
    val req = WorkspaceRequest("829384756102938")
    assertEquals(req.validate, Nil)
  }

  test("blank clinicId returns validation error") {
    val req = WorkspaceRequest("")
    val errors = req.validate
    assert(errors.exists(e => e.field == "clinicId" && e.message.contains("obrigatório")))
  }

  test("non-numeric clinicId returns validation error") {
    val req = WorkspaceRequest("abc")
    val errors = req.validate
    assert(errors.exists(e => e.field == "clinicId" && e.message.contains("positivo")))
  }

  test("zero clinicId returns validation error") {
    val req = WorkspaceRequest("0")
    val errors = req.validate
    assert(errors.exists(e => e.field == "clinicId" && e.message.contains("positivo")))
  }

  test("negative clinicId returns validation error") {
    val req = WorkspaceRequest("-5")
    val errors = req.validate
    assert(errors.exists(e => e.field == "clinicId" && e.message.contains("positivo")))
  }
