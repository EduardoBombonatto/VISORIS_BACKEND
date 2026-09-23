package com.visoris.backend.appointments.repository

import com.visoris.backend.appointments.domain.{ExamStatus, PaymentStatus, ReportStatus}
import doobie.postgres.implicits.pgEnumString
import doobie.util.meta.Meta

object DoobieInstances:

  given examStatusMeta: Meta[ExamStatus] =
    pgEnumString[ExamStatus](
      "exam_status_enum",
      s => ExamStatus.fromString(s).fold(err => throw new IllegalArgumentException(err), identity),
      _.toString
    )

  given reportStatusMeta: Meta[ReportStatus] =
    pgEnumString[ReportStatus](
      "report_status_enum",
      s => ReportStatus.fromString(s).fold(err => throw new IllegalArgumentException(err), identity),
      _.toString
    )

  given paymentStatusMeta: Meta[PaymentStatus] =
    pgEnumString[PaymentStatus](
      "payment_status_enum",
      s => PaymentStatus.fromString(s).fold(err => throw new IllegalArgumentException(err), identity),
      _.toString
    )
