package com.visoris.backend.reports.service

import com.visoris.backend.reports.dto.ValidationError

sealed trait ReportsError

object ReportsError:
  final case class Validation(errors: List[ValidationError]) extends ReportsError
  case object NotFound extends ReportsError
  final case class Conflict(message: String) extends ReportsError
  final case class Internal(message: String) extends ReportsError
