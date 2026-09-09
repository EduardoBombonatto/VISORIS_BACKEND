package com.visoris.backend.patients.domain

import io.circe.Json
import java.time.{Instant, LocalDate}

final case class Patient(
  id: Long,
  clientId: Option[Long],
  name: String,
  patientType: PatientType,
  birthDate: Option[LocalDate],
  biologicalDetails: Json,
  createdAt: Instant
)

object Patient:
  val MinNameLength = 1
  val MaxNameLength = 255

  def create(
    id: Long,
    clientId: Option[Long],
    name: String,
    patientType: PatientType,
    birthDate: Option[LocalDate],
    biologicalDetails: Json,
    createdAt: Instant
  ): Either[String, Patient] =
    val trimmedName = name.trim
    val safeJson = if biologicalDetails.isObject then biologicalDetails else Json.obj()

    if trimmedName.isEmpty then Left("Nome do paciente é obrigatório.")
    else if trimmedName.length < MinNameLength || trimmedName.length > MaxNameLength then
      Left(s"Nome do paciente deve ter entre $MinNameLength e $MaxNameLength caracteres.")
    else if birthDate.exists(_.isAfter(LocalDate.now())) then
      Left("Data de nascimento não pode estar no futuro.")
    else
      Right(Patient(id, clientId, trimmedName, patientType, birthDate, safeJson, createdAt))
