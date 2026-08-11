package com.visoris.backend.iam.dto

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class WorkspaceRequest(
  clinicId: String
):
  def validate: List[ValidationError] =
    if clinicId.isBlank then
      List(ValidationError("clinicId", "clinicId é obrigatório."))
    else
      val numericCheck = Option.when(!clinicId.forall(_.isDigit) || clinicId.toLongOption.isEmpty)(
        ValidationError("clinicId", "clinicId deve ser um número inteiro positivo.")
      )
      val positiveCheck = Option.when(clinicId.toLongOption.exists(_ <= 0L))(
        ValidationError("clinicId", "clinicId deve ser um número inteiro positivo.")
      )
      List(numericCheck, positiveCheck).flatten

object WorkspaceRequest:
  given Decoder[WorkspaceRequest] = deriveDecoder
