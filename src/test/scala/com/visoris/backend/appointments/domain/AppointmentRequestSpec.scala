package com.visoris.backend.appointments.domain

import com.visoris.backend.appointments.dto.CreateAppointmentRequest
import io.circe.parser.decode
import java.time.Instant
import munit.FunSuite

class AppointmentRequestSpec extends FunSuite:

  test("CreateAppointmentRequest decodes valid snake_case JSON with price") {
    val json =
      """{
        |  "clinic_id": 100,
        |  "patient_id": 200,
        |  "scheduled_at": "2026-10-15T14:30:00Z",
        |  "procedure_name": "Endoscopia Digestiva",
        |  "price": 350.50
        |}""".stripMargin

    val decoded = decode[CreateAppointmentRequest](json)
    assert(decoded.isRight)
    val req = decoded.toOption.get
    assertEquals(req.clinicId, 100L)
    assertEquals(req.patientId, 200L)
    assertEquals(req.scheduledAt, Right(Instant.parse("2026-10-15T14:30:00Z")))
    assertEquals(req.procedureName, "Endoscopia Digestiva")
    assertEquals(req.price, Some(BigDecimal("350.50")))
  }

  test("CreateAppointmentRequest decodes valid camelCase JSON without price") {
    val json =
      """{
        |  "clinicId": 100,
        |  "patientId": 200,
        |  "scheduledAt": "2026-10-15T14:30:00Z",
        |  "procedureName": "Colonoscopia"
        |}""".stripMargin

    val decoded = decode[CreateAppointmentRequest](json)
    assert(decoded.isRight)
    val req = decoded.toOption.get
    assertEquals(req.clinicId, 100L)
    assertEquals(req.patientId, 200L)
    assertEquals(req.scheduledAt, Right(Instant.parse("2026-10-15T14:30:00Z")))
    assertEquals(req.procedureName, "Colonoscopia")
    assertEquals(req.price, None)
  }

  test("CreateAppointmentRequest returns error for invalid scheduled_at format") {
    val json =
      """{
        |  "clinic_id": 100,
        |  "patient_id": 200,
        |  "scheduled_at": "invalid-date",
        |  "procedure_name": "Endoscopia"
        |}""".stripMargin

    val decoded = decode[CreateAppointmentRequest](json)
    assert(decoded.isRight)
    val req = decoded.toOption.get
    assert(req.scheduledAt.isLeft)
  }
