package com.visoris.backend.shared.dto

import io.circe.{Encoder, Decoder}
import io.circe.generic.semiauto.{deriveEncoder, deriveDecoder}
import java.time.Instant

final case class ApiResponse[T](
  erro: Boolean,
  message: String,
  data: Option[T],
  httpcode: Int,
  timestamp: Instant
)

object ApiResponse:
  given [T: Encoder]: Encoder[ApiResponse[T]] = deriveEncoder
  given [T: Decoder]: Decoder[ApiResponse[T]] = deriveDecoder

  def success[T](message: String, data: T, httpcode: Int): ApiResponse[T] =
    ApiResponse(erro = false, message = message, data = Some(data), httpcode = httpcode, timestamp = Instant.now)

  def error(message: String, httpcode: Int): ApiResponse[Unit] =
    ApiResponse(erro = true, message = message, data = None, httpcode = httpcode, timestamp = Instant.now)
