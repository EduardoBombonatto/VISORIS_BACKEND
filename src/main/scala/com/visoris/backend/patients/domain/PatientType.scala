package com.visoris.backend.patients.domain

enum PatientType:
  case PET, HUMAN

object PatientType:
  def fromString(raw: String): Either[String, PatientType] =
    raw.trim.toUpperCase match
      case "PET"   => Right(PET)
      case "HUMAN" => Right(HUMAN)
      case other   => Left(s"Tipo de paciente inválido: '$other'. Valores permitidos: PET, HUMAN.")
