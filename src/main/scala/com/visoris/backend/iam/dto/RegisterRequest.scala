package com.visoris.backend.iam.dto

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class RegisterRequest(
  fullName: String,
  email: String,
  password: String,
  professionalDocument: Option[String]
):
  private val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r
  private val asciiPattern = """^[\x20-\x7E]+$""".r

  private val disposableDomains = Set(
    "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com",
    "throwaway.email", "yopmail.com", "sharklasers.com", "trashmail.com",
    "temp-mail.org", "fakeinbox.com", "guerrillamail.info", "guerrillamail.biz",
    "maildrop.cc", "getairmail.com", "mailnesia.com", "spamgourmet.com",
    "spambox.us", "dispostable.com", "mailcatch.com", "tempinbox.com"
  )

  def validate: List[ValidationError] =
    validateRequiredFields ++
      validateEmailFormat.toList ++
      validatePassword ++
      validateFieldLengths ++
      validateDisposableEmail.toList

  private def validateRequiredFields: List[ValidationError] =
    List(
      Option.when(fullName.isBlank)(ValidationError("fullName", "Nome completo é obrigatório.")),
      Option.when(email.isBlank)(ValidationError("email", "E-mail é obrigatório.")),
      Option.when(password.isBlank)(ValidationError("password", "Senha é obrigatória."))
    ).flatten

  private def validateEmailFormat: Option[ValidationError] =
    if emailRegex.matches(email) then None
    else Some(ValidationError("email", "Formato de e-mail inválido."))

  private def validatePassword: List[ValidationError] =
    List(
      Option.when(password.trim.isEmpty)(ValidationError("password", "Senha é obrigatória.")),
      Option.when(password.length < 8)(ValidationError("password", "A senha deve conter pelo menos 8 caracteres.")),
      Option.when(!password.exists(_.isUpper))(ValidationError("password", "A senha deve conter pelo menos uma letra maiúscula.")),
      Option.when(!password.exists(_.isLower))(ValidationError("password", "A senha deve conter pelo menos uma letra minúscula.")),
      Option.when(!password.exists(_.isDigit))(ValidationError("password", "A senha deve conter pelo menos um número.")),
      Option.when(password.forall(ch => ch.isLetterOrDigit))(ValidationError("password", "A senha deve conter pelo menos um caractere especial.")),
      Option.when(!asciiPattern.matches(password))(ValidationError("password", "A senha contém caracteres não permitidos. Use apenas letras, números e símbolos padrão."))
    ).flatten

  private def validateFieldLengths: List[ValidationError] =
    List(
      Option.when(fullName.length > 255)(ValidationError("fullName", "Nome completo excede o limite de 255 caracteres.")),
      Option.when(email.length > 255)(ValidationError("email", "E-mail excede o limite de 255 caracteres.")),
      Option.when(professionalDocument.exists(_.length > 50))(ValidationError("professionalDocument", "Documento profissional excede o limite de 50 caracteres."))
    ).flatten

  private def validateDisposableEmail: Option[ValidationError] =
    val domain = email.split("@").lastOption.getOrElse("").toLowerCase
    if disposableDomains.contains(domain) then
      Some(ValidationError("email", "Por favor, use um e-mail profissional ou pessoal válido."))
    else None

object RegisterRequest:
  given Decoder[RegisterRequest] = deriveDecoder
