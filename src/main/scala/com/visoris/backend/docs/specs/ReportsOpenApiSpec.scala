package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object ReportsOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Reports"
  val tagDescription: String = "Elaboração, auto-save, anexação de imagens e assinatura de laudos médicos."

  private val reportImageResponseExample: Json =
    obj(
      "id" -> str("223456789012345678"),
      "reportId" -> str("123456789012345678"),
      "imageUrl" -> str("https://storage.visoris.com/reports/123456/img-01.jpg"),
      "caption" -> str("Corte longitudinal do lobo hepático esquerdo"),
      "displayOrder" -> int(1),
      "createdAt" -> str(timestampExample)
    )

  private def reportResponseExample(signed: Boolean): Json =
    obj(
      "id" -> str("123456789012345678"),
      "appointmentId" -> str("111222333444555666"),
      "userId" -> str("987654321098765432"),
      "attendanceLocationName" -> str("Clínica Vida"),
      "patientSnapshot" -> obj(
        "id" -> str("8712345678901238888"),
        "name" -> str("Rex"),
        "patientType" -> str("PET")
      ),
      "clinicSnapshot" -> obj(
        "id" -> str("8712345678901234567"),
        "name" -> str("Clínica Vida")
      ),
      "content" -> str("<h2>Ultrassonografia Abdominal</h2><p>Fígado com dimensões preservadas.</p>"),
      "pdfUrl" -> Json.Null,
      "signedAt" -> (if signed then str(timestampExample) else Json.Null),
      "createdAt" -> str(timestampExample),
      "updatedAt" -> str(timestampExample),
      "images" -> Json.arr(reportImageResponseExample)
    )

  private val reportIdParam: List[Json] =
    List(pathIdParam("id", "ID do laudo (Snowflake)."))

  private val reportsByAppointmentGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Busca o laudo associado a um agendamento",
      description = "Retorna o laudo completo (incluindo snapshots de paciente e clínica) e a lista de imagens anexadas ordenadas por display_order.",
      operationId = "getReportByAppointmentId",
      parameters = List(pathIdParam("appointmentId", "ID do agendamento (Snowflake).")),
      responses = List(
        "200" -> jsonResponseWithExample(
          "Laudo obtido com sucesso.",
          successEnvelope("ReportResponse"),
          successExample("Laudo obtido com sucesso.", reportResponseExample(signed = false))
        ),
        unauthorizedResponse(),
        notFoundResponse("Laudo não encontrado para este agendamento."),
        internalErrorResponse
      )
    )

  private val reportsPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Cria um laudo rascunho para um agendamento",
      description = "Cria um laudo rascunho vinculado ao agendamento, popula snapshots de paciente e clínica, e altera o report_status do agendamento para DRAFT.",
      operationId = "createReportDraft",
      requestBody = Some("CreateReportRequest"),
      responses = List(
        "201" -> jsonResponseWithExample(
          "Laudo rascunho criado com sucesso.",
          successEnvelope("ReportResponse"),
          successExample("Laudo rascunho criado com sucesso.", reportResponseExample(signed = false), httpcode = 201)
        ),
        simpleValidationResponse("Dados de requisição inválidos.", "appointment_id", "O campo 'appointment_id' é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Agendamento não encontrado."),
        conflictResponse("Conflito. Já existe um laudo associado a este agendamento.", "Já existe um laudo associado a este agendamento."),
        internalErrorResponse
      )
    )

  private val reportsItemPutOperation: Json =
    operation(
      tag = tagName,
      summary = "Atualiza o conteúdo do laudo (Auto-save/Rascunho)",
      description = "Atualiza o campo content de um laudo rascunho pertencente ao médico autenticado.",
      operationId = "updateReportContent",
      parameters = reportIdParam,
      requestBody = Some("UpdateReportRequest"),
      responses = List(
        "200" -> jsonResponseWithExample(
          "Laudo atualizado com sucesso.",
          successEnvelope("ReportResponse"),
          successExample("Laudo atualizado com sucesso.", reportResponseExample(signed = false))
        ),
        simpleValidationResponse("Dados inválidos.", "content", "O campo 'content' é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Laudo não encontrado."),
        conflictResponse("Laudo já assinado não pode ser alterado.", "Laudo já assinado não pode ser alterado."),
        internalErrorResponse
      )
    )

  private val reportsImagesPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Adiciona imagens ao laudo",
      description = "Salva os metadados de uma imagem (image_url, caption, display_order) associada a um laudo em rascunho.",
      operationId = "addReportImage",
      parameters = reportIdParam,
      requestBody = Some("ReportImageRequest"),
      responses = List(
        "201" -> jsonResponseWithExample(
          "Imagem adicionada ao laudo com sucesso.",
          successEnvelope("ReportImageResponse"),
          successExample("Imagem adicionada ao laudo com sucesso.", reportImageResponseExample, httpcode = 201)
        ),
        simpleValidationResponse("Dados inválidos.", "image_url", "A URL da imagem é obrigatória."),
        unauthorizedResponse(),
        notFoundResponse("Laudo não encontrado."),
        conflictResponse("Não é permitido adicionar imagens a um laudo já assinado.", "Não é permitido adicionar imagens a um laudo já assinado."),
        internalErrorResponse
      )
    )

  private val reportsSignPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Finaliza e assina o laudo",
      description = "Define o signed_at do laudo e altera atomicamente o report_status do agendamento associado para COMPLETED.",
      operationId = "signReport",
      parameters = reportIdParam,
      responses = List(
        "200" -> jsonResponseWithExample(
          "Laudo assinado e finalizado com sucesso.",
          successEnvelope("ReportResponse"),
          successExample("Laudo assinado e finalizado com sucesso.", reportResponseExample(signed = true))
        ),
        unauthorizedResponse(),
        notFoundResponse("Laudo não encontrado."),
        conflictResponse("Laudo já foi assinado anteriormente.", "Laudo já foi assinado anteriormente."),
        internalErrorResponse
      )
    )

  val paths: List[(String, Json)] = List(
    "/api/v1/reports/appointment/{appointmentId}" -> obj("get" -> reportsByAppointmentGetOperation),
    "/api/v1/reports" -> obj("post" -> reportsPostOperation),
    "/api/v1/reports/{id}" -> obj("put" -> reportsItemPutOperation),
    "/api/v1/reports/{id}/images" -> obj("post" -> reportsImagesPostOperation),
    "/api/v1/reports/{id}/sign" -> obj("post" -> reportsSignPostOperation)
  )

  val schemas: List[(String, Json)] = List(
    "CreateReportRequest" -> requiredObject(
      List("appointment_id", "content"),
      "appointment_id" -> obj("type" -> str("integer"), "description" -> str("ID do agendamento (Snowflake).")),
      "content" -> obj("type" -> str("string"), "description" -> str("Conteúdo HTML/texto rico do laudo."))
    ),
    "UpdateReportRequest" -> requiredObject(
      List("content"),
      "content" -> obj("type" -> str("string"), "description" -> str("Conteúdo atualizado do laudo (auto-save/rascunho)."))
    ),
    "ReportImageRequest" -> requiredObject(
      List("image_url"),
      "image_url" -> obj("type" -> str("string"), "maxLength" -> int(1024), "description" -> str("URL da imagem armazenada no S3/MinIO.")),
      "caption" -> obj("type" -> str("string"), "maxLength" -> int(500), "nullable" -> bool(true), "description" -> str("Legenda opcional da imagem.")),
      "display_order" -> obj("type" -> str("integer"), "default" -> int(0), "description" -> str("Ordem de exibição da imagem no laudo."))
    ),
    "ReportImageResponse" -> requiredObject(
      List("id", "reportId", "imageUrl", "displayOrder", "createdAt"),
      "id" -> stringField,
      "reportId" -> stringField,
      "imageUrl" -> stringField,
      "caption" -> stringNullableField,
      "displayOrder" -> int32Field,
      "createdAt" -> dateTimeField
    ),
    "ReportResponse" -> requiredObject(
      List("id", "appointmentId", "userId", "content", "createdAt", "updatedAt", "images"),
      "id" -> stringField,
      "appointmentId" -> stringField,
      "userId" -> stringField,
      "attendanceLocationName" -> stringNullableField,
      "patientSnapshot" -> obj("type" -> str("object"), "nullable" -> bool(true)),
      "clinicSnapshot" -> obj("type" -> str("object"), "nullable" -> bool(true)),
      "content" -> stringField,
      "pdfUrl" -> stringNullableField,
      "signedAt" -> obj("type" -> str("string"), "format" -> str("date-time"), "nullable" -> bool(true)),
      "createdAt" -> dateTimeField,
      "updatedAt" -> dateTimeField,
      "images" -> arrayOf(ref("ReportImageResponse"))
    )
  )
