package com.visoris.backend.clinics.domain

import java.time.Instant

final case class Clinic(
  id: Long,
  name: String,
  cnpj: Option[String],
  phone: Option[String],
  address: Option[String],
  createdAt: Instant,
  updatedAt: Instant
)

object Clinic:
  val MinNameLength = 2
  val MaxNameLength = 255
  val MaxAddressLength = 500

  def create(
    id: Long,
    name: String,
    cnpj: Option[String],
    phone: Option[String],
    address: Option[String],
    createdAt: Instant,
    updatedAt: Instant
  ): Either[String, Clinic] =
    val trimmed = name.trim
    if trimmed.isEmpty then Left("Nome da clínica é obrigatório.")
    else if trimmed.length < MinNameLength || trimmed.length > MaxNameLength then
      Left(s"Nome da clínica deve ter entre $MinNameLength e $MaxNameLength caracteres.")
    else if phone.exists(p => p.length < 10 || p.length > 11) then
      Left("Telefone da clínica deve ter entre 10 e 11 dígitos.")
    else if address.exists(_.length > MaxAddressLength) then
      Left(s"Endereço deve ter no máximo $MaxAddressLength caracteres.")
    else
      Right(Clinic(id, trimmed, cnpj, phone, address, createdAt, updatedAt))
