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
    else if trimmed.length > 255 then Left("Nome da clínica deve ter no máximo 255 caracteres.")
    else Right(Clinic(id, trimmed, cnpj, phone, address, createdAt, updatedAt))
