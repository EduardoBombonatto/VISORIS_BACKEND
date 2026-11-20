package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object ClinicsOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Clinics"
  val tagDescription: String = "Clínicas (locais de atendimento) do usuário autenticado."

  private val sampleClinic: Json =
    obj(
      "id" -> str("8712345678901234567"),
      "name" -> str("Clínica Vida"),
      "cnpj" -> str("11444777000161"),
      "phone" -> str("(11) 5555-0000"),
      "address" -> str("Av. Paulista, 1000")
    )

  private val clinicsListSuccessExample: Json =
    successExample("Clínicas listadas com sucesso.", obj("clinics" -> Json.arr(sampleClinic)))

  private val createClinicSuccessExample: Json =
    successExample("Clínica criada com sucesso.", obj("clinic" -> sampleClinic), httpcode = 201)

  private val reuseClinicSuccessExample: Json =
    successExample("Clínica vinculada com sucesso.", obj("clinic" -> sampleClinic))

  private val clinicsGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Lista as clínicas vinculadas ao usuário autenticado.",
      description =
        """Retorna somente as clínicas vinculadas ao usuário autenticado (JOIN entre
          |`clinics` e `doctor_clinics` via `user_id`). Nunca retorna clínicas de outros
          |usuários. Retorna uma lista vazia quando não há vínculos.""".stripMargin,
      operationId = "clinicsList",
      responses = List(
        "200" -> jsonResponseWithExample(
          "Lista de clínicas do usuário autenticado.",
          successEnvelope("ClinicListResponse"),
          clinicsListSuccessExample
        ),
        unauthorizedResponse("Não autenticado. Token de acesso ausente, inválido ou expirado."),
        internalErrorResponse
      )
    )

  private val clinicsPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Registra ou vincula uma clínica ao usuário autenticado.",
      description =
        """Cria uma nova clínica e a vincula ao usuário autenticado, ou reutiliza uma clínica
          |existente identificada pelo CNPJ. Nunca duplica clínicas nem vínculos.
          |
          |Regras:
          |- `name` é obrigatório (não pode ser vazio, até 255 caracteres).
          |- `cnpj` é opcional; quando informado deve ter 14 dígitos e dígitos verificadores
          |  válidos (Módulo 11) e é normalizado para apenas dígitos.
          |- Quando o CNPJ corresponde a uma clínica existente, os campos `name`/`phone`/`address`
          |  enviados são ignorados e o vínculo é criado (ou mantido) de forma idempotente.
          |- Retorna HTTP 201 quando a clínica é criada e HTTP 200 quando uma clínica existente
          |  é reutilizada/vinculada.""".stripMargin,
      operationId = "clinicsCreate",
      requestBody = Some("CreateClinicRequest"),
      responses = List(
        "201" -> jsonResponseWithExample(
          "Clínica criada e vinculada com sucesso.",
          successEnvelope("CreateClinicResponse"),
          createClinicSuccessExample
        ),
        "200" -> jsonResponseWithExample(
          "Clínica existente reutilizada/vinculada (campos enviados ignorados).",
          successEnvelope("CreateClinicResponse"),
          reuseClinicSuccessExample
        ),
        "400" -> validationResponse(
          """Requisição inválida. Dois cenários possíveis:
            |1. Corpo malformado ou tipos incorretos — `data=null` com message "Requisição inválida. Verifique o formato dos dados.".
            |2. Falha de validação — `data.errors` com um erro por campo, incluindo:
            |   nome obrigatório, tamanhos de telefone/endereço e CNPJ com dígitos verificadores inválidos.""".stripMargin,
          List(
            "corpo-malformado" -> genericErrorExample("Requisição inválida. Verifique o formato dos dados.", 400),
            "nome-obrigatorio" -> validationExample(List("name" -> "Nome da clínica é obrigatório.")),
            "cnpj-invalido" -> validationExample(List("cnpj" -> "CNPJ inválido."))
          )
        ),
        unauthorizedResponse("Não autenticado. Token de acesso ausente, inválido ou expirado."),
        internalErrorResponse
      )
    )

  val paths: List[(String, Json)] = List(
    "/api/v1/clinics" -> obj("get" -> clinicsGetOperation, "post" -> clinicsPostOperation)
  )

  val schemas: List[(String, Json)] = List(
    "ClinicData" -> requiredObject(
      List("id", "name"),
      "id" -> obj("type" -> str("string"), "description" -> str("ID da clínica (Snowflake).")),
      "name" -> stringField,
      "cnpj" -> stringNullableField,
      "phone" -> stringNullableField,
      "address" -> stringNullableField
    ),
    "CreateClinicRequest" -> requiredObject(
      List("name"),
      "name" -> obj(
        "type" -> str("string"),
        "minLength" -> int(2),
        "maxLength" -> int(255),
        "description" -> str("Nome da clínica (obrigatório, mínimo 2, máximo 255 caracteres).")
      ),
      "cnpj" -> obj(
        "type" -> str("string"),
        "nullable" -> bool(true),
        "pattern" -> str("^\\d{14}$"),
        "description" -> str("CNPJ opcional. Exatamente 14 dígitos com dígitos verificadores válidos (Módulo 11); normalizado para apenas dígitos.")
      ),
      "phone" -> obj(
        "type" -> str("string"),
        "nullable" -> bool(true),
        "pattern" -> str("^\\d{10,11}$"),
        "description" -> str("Telefone opcional com 10 ou 11 dígitos (DDD + número), normalizado para dígitos puros.")
      ),
      "address" -> obj(
        "type" -> str("string"),
        "nullable" -> bool(true),
        "maxLength" -> int(500),
        "description" -> str("Endereço opcional, máximo de 500 caracteres.")
      )
    ),
    "ClinicListResponse" -> requiredObject(
      List("clinics"),
      "clinics" -> arrayOf(ref("ClinicData"))
    ),
    "CreateClinicResponse" -> requiredObject(
      List("clinic"),
      "clinic" -> ref("ClinicData")
    )
  )
