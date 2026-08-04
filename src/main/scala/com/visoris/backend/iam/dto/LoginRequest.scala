package com.visoris.backend.iam.dto

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class LoginRequest(
  email: String,
  password: String
):
  private val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r

  def validate: List[ValidationError] =
    List(
      Option.when(email.isBlank)(ValidationError("email", "E-mail é obrigatório.")),
      Option.when(!emailRegex.matches(email))(ValidationError("email", "Formato de e-mail inválido.")),
      Option.when(password.isBlank)(ValidationError("password", "Senha é obrigatória."))
    ).flatten

object LoginRequest:
  given Decoder[LoginRequest] = deriveDecoder
