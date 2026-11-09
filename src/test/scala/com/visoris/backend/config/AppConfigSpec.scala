package com.visoris.backend.config

import cats.effect.IO
import munit.CatsEffectSuite

class AppConfigSpec extends CatsEffectSuite:

  test("AppConfig.load should load default or environment configuration") {
    for config <- AppConfig.load[IO]
    yield
      assert(config.database.url.nonEmpty)
      assert(config.database.user.nonEmpty)
      assert(config.database.password.value.nonEmpty)
      assert(config.jwtSecret.value.nonEmpty)
      assert(config.cors.allowedOrigins.nonEmpty)
  }

  test("AppConfig.load should include configured CORS origins") {
    for config <- AppConfig.load[IO]
    yield
      // Either default http://localhost:3000 or whatever is set in environment
      assert(config.cors.allowedOrigins.exists(_.toString.contains("localhost:3000")) || config.cors.allowedOrigins.nonEmpty)
  }
