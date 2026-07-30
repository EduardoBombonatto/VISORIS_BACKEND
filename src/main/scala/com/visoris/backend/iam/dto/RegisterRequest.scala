package com.visoris.backend.iam.dto

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class RegisterRequest(
  fullName: String,
  email: String,
  password: String,
  professionalDocument: Option[String]
)

object RegisterRequest:
  given Decoder[RegisterRequest] = deriveDecoder
