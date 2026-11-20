package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object TemplatesOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Templates"
  val tagDescription: String = "Templates de laudos clínicos do médico."

  private def templateDetailData(title: String): Json =
    obj(
      "id" -> str("123456789012345678"),
      "userId" -> str("987654321098765432"),
      "title" -> str(title),
      "content" -> str("<p>Estruturas cardíacas dentro dos limites da normalidade.</p>"),
      "createdAt" -> str(timestampExample),
      "updatedAt" -> str(timestampExample)
    )

  private val createTemplateSuccessExample: Json =
    successExample("Template cadastrado com sucesso.", obj("id" -> str("123456789012345678")), httpcode = 201)

  private val templateSummaryExample: Json =
    obj(
      "id" -> str("123456789012345678"),
      "title" -> str("Ecocardiograma Normal"),
      "createdAt" -> str(timestampExample)
    )

  private val templateListSuccessExample: Json =
    successExample("Templates listados com sucesso.", obj("templates" -> Json.arr(templateSummaryExample)))

  private val templateDetailSuccessExample: Json =
    successExample("Template obtido com sucesso.", templateDetailData("Ecocardiograma Normal"))

  private val updateTemplateSuccessExample: Json =
    successExample("Template atualizado com sucesso.", templateDetailData("Ecocardiograma Atualizado"))

  private val deleteTemplateSuccessExample: Json =
    successExample("Template excluído com sucesso.", Json.Null)

  private val templateIdParam: List[Json] =
    List(pathIdParam("id", "ID do template (Snowflake)."))

  private val templatesPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Cria um novo template",
      description = "Cria um template com título e conteúdo rico/HTML para o utilizador autenticado.",
      operationId = "createTemplate",
      requestBody = Some("CreateTemplateRequest"),
      responses = List(
        "201" -> jsonResponseWithExample("Template cadastrado com sucesso.", successEnvelope("CreateTemplateResponse"), createTemplateSuccessExample),
        simpleValidationResponse("Dados de requisição inválidos.", "title", "Título do template é obrigatório."),
        unauthorizedResponse(),
        internalErrorResponse
      )
    )

  private val templatesGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Lista os templates do médico",
      description = "Retorna os templates pertencentes exclusivamente ao utilizador logado, omitindo o conteúdo rico para otimizar o payload.",
      operationId = "listTemplates",
      responses = List(
        "200" -> jsonResponseWithExample("Templates listados com sucesso.", successEnvelope("TemplateListResponse"), templateListSuccessExample),
        unauthorizedResponse(),
        internalErrorResponse
      )
    )

  private val templatesItemGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Obtém um template por ID",
      description = "Retorna o template completo incluindo o conteúdo rico/HTML caso pertença ao médico autenticado.",
      operationId = "getTemplateById",
      parameters = templateIdParam,
      responses = List(
        "200" -> jsonResponseWithExample("Template obtido com sucesso.", successEnvelope("TemplateDetailResponse"), templateDetailSuccessExample),
        unauthorizedResponse(),
        notFoundResponse("Template não encontrado."),
        internalErrorResponse
      )
    )

  private val templatesItemPutOperation: Json =
    operation(
      tag = tagName,
      summary = "Atualiza um template existente",
      description = "Atualiza título e/ou conteúdo do template garantindo que pertença ao médico autenticado.",
      operationId = "updateTemplate",
      parameters = templateIdParam,
      requestBody = Some("UpdateTemplateRequest"),
      responses = List(
        "200" -> jsonResponseWithExample("Template atualizado com sucesso.", successEnvelope("TemplateDetailResponse"), updateTemplateSuccessExample),
        simpleValidationResponse(
          "Dados de requisição inválidos.",
          "body",
          "Pelo menos um campo ('title' ou 'content') deve ser fornecido para atualização."
        ),
        unauthorizedResponse(),
        notFoundResponse("Template não encontrado."),
        internalErrorResponse
      )
    )

  private val templatesItemDeleteOperation: Json =
    operation(
      tag = tagName,
      summary = "Exclui um template",
      description = "Remove o template caso pertença ao médico autenticado.",
      operationId = "deleteTemplate",
      parameters = templateIdParam,
      responses = List(
        "200" -> jsonResponseWithExample("Template excluído com sucesso.", errorEnvelope, deleteTemplateSuccessExample),
        unauthorizedResponse(),
        notFoundResponse("Template não encontrado."),
        internalErrorResponse
      )
    )

  val paths: List[(String, Json)] = List(
    "/api/v1/templates" -> obj("get" -> templatesGetOperation, "post" -> templatesPostOperation),
    "/api/v1/templates/{id}" -> obj(
      "get" -> templatesItemGetOperation,
      "put" -> templatesItemPutOperation,
      "delete" -> templatesItemDeleteOperation
    )
  )

  val schemas: List[(String, Json)] = List(
    "CreateTemplateRequest" -> requiredObject(
      List("title", "content"),
      "title" -> stringField,
      "content" -> stringField
    ),
    "CreateTemplateResponse" -> requiredObject(
      List("id"),
      "id" -> stringField
    ),
    "UpdateTemplateRequest" -> obj(
      "type" -> str("object"),
      "properties" -> obj(
        "title" -> stringNullableField,
        "content" -> stringNullableField
      )
    ),
    "TemplateSummaryResponse" -> requiredObject(
      List("id", "title", "createdAt"),
      "id" -> stringField,
      "title" -> stringField,
      "createdAt" -> dateTimeField
    ),
    "TemplateListResponse" -> requiredObject(
      List("templates"),
      "templates" -> arrayOf(ref("TemplateSummaryResponse"))
    ),
    "TemplateDetailResponse" -> requiredObject(
      List("id", "userId", "title", "content", "createdAt", "updatedAt"),
      "id" -> stringField,
      "userId" -> stringField,
      "title" -> stringField,
      "content" -> stringField,
      "createdAt" -> dateTimeField,
      "updatedAt" -> dateTimeField
    )
  )
