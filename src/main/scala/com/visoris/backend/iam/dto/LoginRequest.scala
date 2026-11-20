package com.visoris.backend.iam.dto

import com.visoris.backend.shared.utils.SecurityUtils
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class LoginRequest(
  email: String,
  password: String
):
  private val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r

  def validate: List[ValidationError] =
    val trimmedEmail = email.trim
    List(
      Option.when(trimmedEmail.isBlank)(ValidationError("email", "E-mail é obrigatório.")),
      Option.when(trimmedEmail.length > 255)(ValidationError("email", "E-mail excede o limite de 255 caracteres.")),
      Option.when(SecurityUtils.containsNullByte(email))(ValidationError("email", "E-mail contém caracteres inválidos.")),
      Option.when(!emailRegex.matches(trimmedEmail))(ValidationError("email", "Formato de e-mail inválido.")),
      Option.when(password.isBlank)(ValidationError("password", "Senha é obrigatória.")),
      Option.when(password.length > 72)(ValidationError("password", "A senha excede o limite de 72 caracteres.")),
      Option.when(SecurityUtils.containsNullByte(password))(ValidationError("password", "Senha contém caracteres inválidos."))
    ).flatten

object LoginRequest:
  given Decoder[LoginRequest] = deriveDecoder
