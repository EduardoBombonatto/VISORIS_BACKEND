package com.visoris.backend.shared.auth

import cats.effect.IO
import munit.CatsEffectSuite

class PasswordHasherSpec extends CatsEffectSuite:

  test("hash should produce a non-plaintext output different from input") {
    for
      hashed <- PasswordHasher.hash[IO]("Senha@123")
    yield
      assert(hashed.nonEmpty)
      assert(hashed != "Senha@123")
      assert(hashed.startsWith("$2a$"))
  }

  test("verify should return true for matching password and hash") {
    for
      hashed <- PasswordHasher.hash[IO]("Senha@123")
      result <- PasswordHasher.verify[IO]("Senha@123", hashed)
    yield assert(result)
  }

  test("verify should return false for non-matching password") {
    for
      hashed <- PasswordHasher.hash[IO]("Senha@123")
      result <- PasswordHasher.verify[IO]("WrongPassword", hashed)
    yield assert(!result)
  }

  test("hash should produce different output for same input (salt)") {
    for
      h1 <- PasswordHasher.hash[IO]("Senha@123")
      h2 <- PasswordHasher.hash[IO]("Senha@123")
    yield assert(h1 != h2)
  }
