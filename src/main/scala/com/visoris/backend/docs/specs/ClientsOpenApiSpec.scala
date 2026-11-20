package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object ClientsOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Clients"
  val tagDescription: String = "Tutores e clientes vinculados a clínicas."

  private val sampleClient: Json =
    obj(
      "id" -> str("8712345678901239999"),
      "userId" -> str("8712345678901234567"),
      "fullName" -> str("Carlos Silva"),
      "documentCpf" -> str("12345678909"),
      "email" -> str("carlos@example.com"),
      "phone" -> str("11987654321"),
      "createdAt" -> str(timestampExample)
    )

  private val createClientSuccessExample: Json =
    successExample("Cliente cadastrado com sucesso.", obj("id" -> str("8712345678901239999")), httpcode = 201)

  private val clientsListSuccessExample: Json =
    successExample("Clientes listados com sucesso.", obj("clients" -> Json.arr(sampleClient)))

  private val clientsGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Lista os tutores/clientes vinculados ao usuário logado.",
      description = "Retorna a lista paginada de tutores do usuário autenticado.",
      operationId = "clientsList",
      parameters = paginationParams,
      responses = List(
        "200" -> jsonResponseWithExample("Clientes listados com sucesso.", successEnvelope("ClientListResponse"), clientsListSuccessExample),
        unauthorizedResponse(),
        notFoundResponse("Usuário não encontrado."),
        internalErrorResponse
      )
    )

  private val clientsPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Cadastra um novo tutor/cliente vinculado ao usuário logado.",
      description = "Cadastra o cliente e retorna o ID gerado.",
      operationId = "clientsCreate",
      requestBody = Some("ClientRequest"),
      responses = List(
        "201" -> jsonResponseWithExample("Cliente cadastrado com sucesso.", successEnvelope("CreateClientResponse"), createClientSuccessExample),
        simpleValidationResponse("Requisição inválida ou campos obrigatórios ausentes.", "full_name", "Nome do tutor é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Usuário não encontrado."),
        conflictResponse("Conflito de CPF.", "Este CPF já está cadastrado para este usuário."),
        internalErrorResponse
      )
    )

  private val clientsPutOperation: Json =
    operation(
      tag = tagName,
      summary = "Atualiza os dados de um tutor/cliente.",
      description = "Atualiza as informações do tutor pertencente ao usuário autenticado.",
      operationId = "clientsUpdate",
      parameters = List(pathIdParam("id", "ID do cliente.")),
      requestBody = Some("ClientRequest"),
      responses = List(
        "200" -> jsonResponseWithExample(
          "Cliente atualizado com sucesso.",
          successEnvelope("ClientResponse"),
          successExample("Cliente atualizado com sucesso.", sampleClient)
        ),
        simpleValidationResponse("Requisição inválida ou campos obrigatórios ausentes.", "full_name", "Nome do tutor é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Cliente não encontrado."),
        conflictResponse("Conflito de CPF, e-mail ou telefone.", "Este CPF já está cadastrado para este usuário."),
        internalErrorResponse
      )
    )

  private val clientsDeleteOperation: Json =
    operation(
      tag = tagName,
      summary = "Exclui um tutor/cliente.",
      description = "Remove um tutor vinculado ao usuário autenticado caso não haja pacientes vinculados.",
      operationId = "clientsDelete",
      parameters = List(pathIdParam("id", "ID do cliente.")),
      responses = List(
        "200" -> jsonResponseWithExample(
          "Cliente excluído com sucesso.",
          errorEnvelope,
          successExample("Cliente excluído com sucesso.", Json.Null)
        ),
        unauthorizedResponse(),
        notFoundResponse("Cliente não encontrado."),
        conflictResponse("Conflito. Existem pacientes vinculados.", "Não é possível excluir o tutor pois existem pacientes vinculados."),
        internalErrorResponse
      )
    )

  val paths: List[(String, Json)] = List(
    "/api/v1/clients" -> obj("get" -> clientsGetOperation, "post" -> clientsPostOperation),
    "/api/v1/clients/{id}" -> obj("put" -> clientsPutOperation, "delete" -> clientsDeleteOperation)
  )

  val schemas: List[(String, Json)] = List(
    "ClientRequest" -> requiredObject(
      List("full_name", "document_cpf", "email", "phone"),
      "full_name" -> obj("type" -> str("string"), "description" -> str("Nome completo do tutor.")),
      "document_cpf" -> obj("type" -> str("string"), "description" -> str("CPF do tutor (11 dígitos).")),
      "email" -> obj("type" -> str("string"), "description" -> str("E-mail do tutor.")),
      "phone" -> obj("type" -> str("string"), "description" -> str("Telefone de contato do tutor."))
    ),
    "CreateClientResponse" -> requiredObject(
      List("id"),
      "id" -> obj("type" -> str("string"), "description" -> str("ID do cliente gerado (Snowflake)."))
    ),
    "ClientResponse" -> requiredObject(
      List("id", "userId", "fullName", "createdAt"),
      "id" -> obj("type" -> str("string"), "description" -> str("ID do cliente (Snowflake).")),
      "userId" -> obj("type" -> str("string"), "description" -> str("ID do usuário logado.")),
      "fullName" -> stringField,
      "documentCpf" -> stringNullableField,
      "email" -> stringNullableField,
      "phone" -> stringNullableField,
      "createdAt" -> dateTimeField
    ),
    "ClientListResponse" -> requiredObject(
      List("clients"),
      "clients" -> arrayOf(ref("ClientResponse"))
    )
  )
