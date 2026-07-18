package com.visoris.backend

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  val run = BackendServer.run[IO]
