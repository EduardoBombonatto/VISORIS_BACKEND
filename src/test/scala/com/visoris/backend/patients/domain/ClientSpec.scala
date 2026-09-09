package com.visoris.backend.patients.domain

import java.time.Instant
import munit.FunSuite

class ClientSpec extends FunSuite:

  private val now = Instant.now()

  test("Client.create accepts valid parameters") {
    val result = Client.create(
      id = 100L,
      clinicId = 200L,
      fullName = "Carlos Silva",
      documentCpf = Some("12345678909"),
      email = Some("carlos@example.com"),
      phone = Some("11987654321"),
      createdAt = now,
      updatedAt = now
    )
    assert(result.isRight)
    val client = result.toOption.get
    assertEquals(client.id, 100L)
    assertEquals(client.clinicId, 200L)
    assertEquals(client.fullName, "Carlos Silva")
  }

  test("Client.create trims full name and rejects blank or short names") {
    assert(Client.create(1L, 2L, "  ", None, None, None, now, now).isLeft)
    assert(Client.create(1L, 2L, "A", None, None, None, now, now).isLeft)
    assert(Client.create(1L, 2L, "a" * 256, None, None, None, now, now).isLeft)
  }

  test("Client.create rejects invalid phone") {
    val result = Client.create(1L, 2L, "Carlos Silva", None, None, Some("123"), now, now)
    assert(result.isLeft)
  }

  test("Client.create rejects invalid email") {
    val result = Client.create(1L, 2L, "Carlos Silva", None, Some("invalid-email"), None, now, now)
    assert(result.isLeft)
  }
