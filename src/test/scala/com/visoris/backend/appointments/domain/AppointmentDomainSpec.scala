package com.visoris.backend.appointments.domain

import munit.FunSuite

class AppointmentDomainSpec extends FunSuite:

  test("ExamStatus.fromString parses valid statuses case-insensitively") {
    assertEquals(ExamStatus.fromString("SCHEDULED"), Right(ExamStatus.SCHEDULED))
    assertEquals(ExamStatus.fromString("scheduled"), Right(ExamStatus.SCHEDULED))
    assertEquals(ExamStatus.fromString("IN_PROGRESS"), Right(ExamStatus.IN_PROGRESS))
    assertEquals(ExamStatus.fromString("in_progress"), Right(ExamStatus.IN_PROGRESS))
    assertEquals(ExamStatus.fromString("COMPLETED"), Right(ExamStatus.COMPLETED))
    assertEquals(ExamStatus.fromString("completed"), Right(ExamStatus.COMPLETED))
    assertEquals(ExamStatus.fromString("CANCELLED"), Right(ExamStatus.CANCELLED))
    assertEquals(ExamStatus.fromString("cancelled"), Right(ExamStatus.CANCELLED))
  }

  test("ExamStatus.fromString rejects invalid status") {
    val result = ExamStatus.fromString("UNKNOWN")
    assert(result.isLeft)
  }

  test("ReportStatus.fromString parses valid statuses case-insensitively") {
    assertEquals(ReportStatus.fromString("PENDING"), Right(ReportStatus.PENDING))
    assertEquals(ReportStatus.fromString("pending"), Right(ReportStatus.PENDING))
    assertEquals(ReportStatus.fromString("DRAFT"), Right(ReportStatus.DRAFT))
    assertEquals(ReportStatus.fromString("draft"), Right(ReportStatus.DRAFT))
    assertEquals(ReportStatus.fromString("COMPLETED"), Right(ReportStatus.COMPLETED))
    assertEquals(ReportStatus.fromString("completed"), Right(ReportStatus.COMPLETED))
  }

  test("ReportStatus.fromString rejects invalid status") {
    val result = ReportStatus.fromString("UNKNOWN")
    assert(result.isLeft)
  }

  test("PaymentStatus.fromString parses valid statuses case-insensitively") {
    assertEquals(PaymentStatus.fromString("UNPAID"), Right(PaymentStatus.UNPAID))
    assertEquals(PaymentStatus.fromString("unpaid"), Right(PaymentStatus.UNPAID))
    assertEquals(PaymentStatus.fromString("PAID"), Right(PaymentStatus.PAID))
    assertEquals(PaymentStatus.fromString("paid"), Right(PaymentStatus.PAID))
    assertEquals(PaymentStatus.fromString("INSURANCE"), Right(PaymentStatus.INSURANCE))
    assertEquals(PaymentStatus.fromString("insurance"), Right(PaymentStatus.INSURANCE))
  }

  test("PaymentStatus.fromString rejects invalid status") {
    val result = PaymentStatus.fromString("UNKNOWN")
    assert(result.isLeft)
  }
