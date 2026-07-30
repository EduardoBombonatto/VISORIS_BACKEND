package com.visoris.backend

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  val run: IO[Unit] = BackendServer.run[IO]
