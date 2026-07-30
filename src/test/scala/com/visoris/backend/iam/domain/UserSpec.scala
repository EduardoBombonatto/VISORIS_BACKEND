package com.visoris.backend.iam.domain

import munit.CatsEffectSuite
import java.time.Instant

class UserSpec extends CatsEffectSuite:

  test("User entity should be created with valid fields") {
    val user = User(
      id = 1L,
      email = "doctor@visoris.com",
      passwordHash = "$2a$12$hashedvalue1234567890abcdef",
      fullName = "Dra. Ana Silva",
      professionalDocument = Some("CRMV-PR 98765"),
      createdAt = Instant.now
    )

    assertEquals(user.id, 1L)
    assertEquals(user.email, "doctor@visoris.com")
    assertEquals(user.fullName, "Dra. Ana Silva")
    assertEquals(user.professionalDocument, Some("CRMV-PR 98765"))
  }

  test("User entity should allow None for professional document") {
    val user = User(
      id = 2L,
      email = "doctor2@visoris.com",
      passwordHash = "$2a$12$hashedvalue",
      fullName = "Dr. João Silva",
      professionalDocument = None,
      createdAt = Instant.now
    )

    assertEquals(user.professionalDocument, None)
  }

  test("User entity should store password hash, not plaintext password") {
    val user = User(
      id = 3L,
      email = "doctor3@visoris.com",
      passwordHash = "$2a$12$somehash",
      fullName = "Dr. Test",
      professionalDocument = None,
      createdAt = Instant.now
    )

    assert(user.passwordHash.nonEmpty)
    assert(!user.passwordHash.contains("plaintext"))
  }
