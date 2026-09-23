package com.visoris.backend.templates.service

import com.visoris.backend.templates.dto.ValidationError

sealed trait TemplatesError
object TemplatesError:
  final case class Validation(errors: List[ValidationError]) extends TemplatesError
  case object NotFound extends TemplatesError
  final case class Internal(message: String) extends TemplatesError
