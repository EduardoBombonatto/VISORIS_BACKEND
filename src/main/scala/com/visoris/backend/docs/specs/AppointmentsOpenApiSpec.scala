package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object AppointmentsOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Appointments"
  val tagDescription: String = "Agendamento e controle financeiro de procedimentos."

  private val appointmentResponseExample: Json =
    obj(
      "id" -> str("8712345678901237777"),
      "userId" -> str("8712345678901234567"),
      "clinicId" -> str("8712345678901234567"),
      "patientId" -> str("8712345678901238888"),
      "scheduledAt" -> str("2026-10-15T14:30:00Z"),
      "procedureName" -> str("Endoscopia Digestiva Alta"),
      "examStatus" -> str("SCHEDULED"),
      "reportStatus" -> str("PENDING"),
      "paymentStatus" -> str("UNPAID"),
      "price" -> Json.fromBigDecimal(BigDecimal("450.00")),
      "createdAt" -> str(timestampExample),
      "updatedAt" -> str(timestampExample),
      "patientName" -> str("Rex"),
      "patientType" -> str("PET"),
      "clientName" -> str("Carlos Silva"),
      "clinicName" -> str("Clínica Vida")
    )

  private val createAppointmentSuccessExample: Json =
    successExample("Agendamento criado com sucesso.", obj("appointment" -> appointmentResponseExample), httpcode = 201)

  private val appointmentsListSuccessExample: Json =
    successExample("Agendamentos listados com sucesso.", obj("appointments" -> Json.arr(appointmentResponseExample)))

  private val updateAppointmentStatusSuccessExample: Json =
    successExample("Status atualizado com sucesso.", appointmentResponseExample)

  private val appointmentsPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Cria um novo agendamento na agenda do médico.",
      description =
        """Cria o agendamento com status iniciais SCHEDULED, PENDING e UNPAID.
          |Valida se clinic_id e patient_id pertencem ao médico autenticado, retornando 404 caso não pertençam.""".stripMargin,
      operationId = "createAppointment",
      requestBody = Some("CreateAppointmentRequest"),
      responses = List(
        "201" -> jsonResponseWithExample("Agendamento criado com sucesso.", successEnvelope("CreateAppointmentResponse"), createAppointmentSuccessExample),
        simpleValidationResponse("Requisição inválida ou dados incorretos.", "procedure_name", "Nome do procedimento é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Clínica ou paciente não encontrados."),
        internalErrorResponse
      )
    )

  private val appointmentsGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Lista agendamentos do médico autenticado com dados enriquecidos.",
      description =
        """Retorna a lista de agendamentos do médico autenticado enriquecida com dados do paciente, tutor e clínica via JOIN.
          |Filtros opcionais: startDate, endDate e clinicId.""".stripMargin,
      operationId = "listAppointments",
      parameters = List(
        queryParam("startDate", dateTimeField, description = Some("Filtro data inicial (ISO 8601).")),
        queryParam("endDate", dateTimeField, description = Some("Filtro data final (ISO 8601).")),
        queryParam("clinicId", obj("type" -> str("integer")), description = Some("Filtro por ID da clínica."))
      ),
      responses = List(
        "200" -> jsonResponseWithExample("Agendamentos listados com sucesso.", successEnvelope("AppointmentListResponse"), appointmentsListSuccessExample),
        simpleValidationResponse("Parâmetros inválidos.", "startDate", "Formato de data inválido: 'invalid'. Formato esperado: ISO 8601."),
        unauthorizedResponse(),
        notFoundResponse("Clínica não encontrada."),
        internalErrorResponse
      )
    )

  private val appointmentsPatchOperation: Json =
    operation(
      tag = tagName,
      summary = "Atualiza independentemente os status de exame, laudo ou pagamento de um agendamento.",
      description = "Permite atualizar exam_status, report_status e payment_status de forma independente.",
      operationId = "updateAppointmentStatus",
      parameters = List(pathIdParam("id", "ID do agendamento (Snowflake).")),
      requestBody = Some("UpdateAppointmentStatusRequest"),
      responses = List(
        "200" -> jsonResponseWithExample("Status atualizado com sucesso.", successEnvelope("AppointmentResponse"), updateAppointmentStatusSuccessExample),
        simpleValidationResponse("Requisição inválida ou nenhum status fornecido.", "status", "Ao menos um status deve ser informado para atualização."),
        unauthorizedResponse(),
        notFoundResponse("Agendamento não encontrado."),
        internalErrorResponse
      )
    )

  val paths: List[(String, Json)] = List(
    "/api/v1/appointments" -> obj("get" -> appointmentsGetOperation, "post" -> appointmentsPostOperation),
    "/api/v1/appointments/{id}/status" -> obj("patch" -> appointmentsPatchOperation)
  )

  val schemas: List[(String, Json)] = List(
    "ExamStatus" -> obj(
      "type" -> str("string"),
      "enum" -> Json.arr(str("SCHEDULED"), str("IN_PROGRESS"), str("COMPLETED"), str("CANCELLED")),
      "description" -> str("Status de realização do exame.")
    ),
    "ReportStatus" -> obj(
      "type" -> str("string"),
      "enum" -> Json.arr(str("PENDING"), str("DRAFT"), str("COMPLETED")),
      "description" -> str("Status de elaboração do laudo.")
    ),
    "PaymentStatus" -> obj(
      "type" -> str("string"),
      "enum" -> Json.arr(str("UNPAID"), str("PAID"), str("INSURANCE")),
      "description" -> str("Status de liquidação financeira.")
    ),
    "CreateAppointmentRequest" -> requiredObject(
      List("clinic_id", "patient_id", "scheduled_at", "procedure_name"),
      "clinic_id" -> obj("type" -> str("integer"), "description" -> str("ID da clínica (Snowflake).")),
      "patient_id" -> obj("type" -> str("integer"), "description" -> str("ID do paciente (Snowflake).")),
      "scheduled_at" -> obj("type" -> str("string"), "format" -> str("date-time"), "description" -> str("Data e hora agendada (ISO 8601).")),
      "procedure_name" -> obj("type" -> str("string"), "description" -> str("Nome do procedimento ou exame.")),
      "price" -> numberNullableField,
      "payment_status" -> ref("PaymentStatus")
    ),
    "UpdateAppointmentStatusRequest" -> obj(
      "type" -> str("object"),
      "properties" -> obj(
        "exam_status" -> ref("ExamStatus"),
        "report_status" -> ref("ReportStatus"),
        "payment_status" -> ref("PaymentStatus")
      ),
      "description" -> str("Atualização parcial independente de status do agendamento.")
    ),
    "AppointmentResponse" -> requiredObject(
      List(
        "id", "userId", "clinicId", "patientId", "scheduledAt", "procedureName",
        "examStatus", "reportStatus", "paymentStatus", "createdAt", "updatedAt",
        "patientName", "patientType", "clientName", "clinicName"
      ),
      "id" -> obj("type" -> str("string"), "description" -> str("ID do agendamento (Snowflake).")),
      "userId" -> obj("type" -> str("string"), "description" -> str("ID do médico responsável (Snowflake).")),
      "clinicId" -> obj("type" -> str("string"), "description" -> str("ID da clínica (Snowflake).")),
      "patientId" -> obj("type" -> str("string"), "description" -> str("ID do paciente (Snowflake).")),
      "scheduledAt" -> dateTimeField,
      "procedureName" -> stringField,
      "examStatus" -> ref("ExamStatus"),
      "reportStatus" -> ref("ReportStatus"),
      "paymentStatus" -> ref("PaymentStatus"),
      "price" -> numberNullableField,
      "createdAt" -> dateTimeField,
      "updatedAt" -> dateTimeField,
      "patientName" -> stringField,
      "patientType" -> stringField,
      "clientName" -> stringField,
      "clinicName" -> stringField
    ),
    "CreateAppointmentResponse" -> requiredObject(
      List("appointment"),
      "appointment" -> ref("AppointmentResponse")
    ),
    "AppointmentListResponse" -> requiredObject(
      List("appointments"),
      "appointments" -> arrayOf(ref("AppointmentResponse"))
    )
  )
