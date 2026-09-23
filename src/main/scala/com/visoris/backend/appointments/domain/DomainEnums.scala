package com.visoris.backend.appointments.domain

import io.circe.{Decoder, Encoder}

enum ExamStatus:
  case SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED

object ExamStatus:
  def fromString(raw: String): Either[String, ExamStatus] =
    raw.trim.toUpperCase match
      case "SCHEDULED"   => Right(SCHEDULED)
      case "IN_PROGRESS" => Right(IN_PROGRESS)
      case "COMPLETED"   => Right(COMPLETED)
      case "CANCELLED"   => Right(CANCELLED)
      case other =>
        Left(s"Status de exame inválido: '$other'. Valores permitidos: SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED.")

  given Encoder[ExamStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[ExamStatus] = Decoder.decodeString.emap(fromString)

enum ReportStatus:
  case PENDING, DRAFT, COMPLETED

object ReportStatus:
  def fromString(raw: String): Either[String, ReportStatus] =
    raw.trim.toUpperCase match
      case "PENDING"   => Right(PENDING)
      case "DRAFT"     => Right(DRAFT)
      case "COMPLETED" => Right(COMPLETED)
      case other =>
        Left(s"Status de laudo inválido: '$other'. Valores permitidos: PENDING, DRAFT, COMPLETED.")

  given Encoder[ReportStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[ReportStatus] = Decoder.decodeString.emap(fromString)

enum PaymentStatus:
  case UNPAID, PAID, INSURANCE

object PaymentStatus:
  def fromString(raw: String): Either[String, PaymentStatus] =
    raw.trim.toUpperCase match
      case "UNPAID"    => Right(UNPAID)
      case "PAID"      => Right(PAID)
      case "INSURANCE" => Right(INSURANCE)
      case other =>
        Left(s"Status de pagamento inválido: '$other'. Valores permitidos: UNPAID, PAID, INSURANCE.")

  given Encoder[PaymentStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PaymentStatus] = Decoder.decodeString.emap(fromString)
