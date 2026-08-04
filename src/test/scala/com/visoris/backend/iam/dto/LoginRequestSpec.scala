package com.visoris.backend.iam.dto

import munit.FunSuite

class LoginRequestSpec extends FunSuite:
  test("valid login request returns no errors") {
    val req = LoginRequest("test@visoris.com", "password123")
    assertEquals(req.validate, Nil)
  }

  test("empty email returns validation error") {
    val req = LoginRequest("", "password123")
    val errors = req.validate
    assert(errors.exists(e => e.field == "email" && e.message.contains("obrigatório")))
  }

  test("invalid email format returns validation error") {
    val req = LoginRequest("not-an-email", "password123")
    val errors = req.validate
    assert(errors.exists(e => e.field == "email" && e.message.contains("inválido")))
  }

  test("empty password returns validation error") {
    val req = LoginRequest("test@visoris.com", "")
    val errors = req.validate
    assert(errors.exists(e => e.field == "password" && e.message.contains("obrigatória")))
  }
