package com.visoris.backend.patients.domain

import java.time.Instant

final case class Client(
  id: Long,
  userId: Long,
  fullName: String,
  documentCpf: Option[String],
  email: Option[String],
  phone: Option[String],
  createdAt: Instant,
  updatedAt: Instant
)

object Client:
  val MinNameLength = 2
  val MaxNameLength = 255

  def create(
    id: Long,
    userId: Long,
    fullName: String,
    documentCpf: Option[String],
    email: Option[String],
    phone: Option[String],
    createdAt: Instant,
    updatedAt: Instant
  ): Either[String, Client] =
    val trimmedName = fullName.trim
    val sanitizedPhone = phone.map(_.filter(_.isDigit)).filter(_.nonEmpty)
    val sanitizedCpf = documentCpf.map(_.filter(_.isDigit)).filter(_.nonEmpty)
    val trimmedEmail = email.map(_.trim).filter(_.nonEmpty)

    if trimmedName.isEmpty then Left("Nome do cliente é obrigatório.")
    else if trimmedName.length < MinNameLength || trimmedName.length > MaxNameLength then
      Left(s"Nome do cliente deve ter entre $MinNameLength e $MaxNameLength caracteres.")
    else if sanitizedPhone.exists(p => p.length < 10 || p.length > 11) then
      Left("Telefone do cliente deve ter entre 10 e 11 dígitos.")
    else if trimmedEmail.exists(e => !e.contains("@") || !e.contains(".")) then
      Left("Formato de e-mail inválido.")
    else if sanitizedCpf.exists(_.length != 11) then
      Left("CPF deve conter 11 dígitos.")
    else
      Right(Client(id, userId, trimmedName, sanitizedCpf, trimmedEmail, sanitizedPhone, createdAt, updatedAt))
