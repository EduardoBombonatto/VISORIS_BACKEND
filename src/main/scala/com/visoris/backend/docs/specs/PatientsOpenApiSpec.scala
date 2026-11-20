package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object PatientsOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Patients"
  val tagDescription: String = "Pacientes (PET ou HUMAN) vinculados a tutores."

  private val samplePatient: Json =
    obj(
      "id" -> str("8712345678901238888"),
      "clientId" -> str("8712345678901239999"),
      "name" -> str("Rex"),
      "patientType" -> str("PET"),
      "birthDate" -> str("2022-05-10"),
      "biologicalDetails" -> obj("species" -> str("Canino"), "breed" -> str("Golden")),
      "createdAt" -> str(timestampExample)
    )

  private val createPatientSuccessExample: Json =
    successExample("Paciente cadastrado com sucesso.", obj("patient" -> samplePatient), httpcode = 201)

  private val patientsListSuccessExample: Json =
    successExample("Pacientes listados com sucesso.", obj("patients" -> Json.arr(samplePatient)))

  private val patientsGetOperation: Json =
    operation(
      tag = tagName,
      summary = "Lista os pacientes vinculados a um tutor.",
      description = "Retorna a lista de pacientes vinculados ao cliente informado. Requer que o cliente pertença a uma clínica do médico autenticado.",
      operationId = "patientsList",
      parameters =
        queryParam("clientId", stringField, required = true, description = Some("ID do cliente/tutor (Snowflake).")) ::
          paginationParams,
      responses = List(
        "200" -> jsonResponseWithExample("Pacientes listados com sucesso.", successEnvelope("PatientListResponse"), patientsListSuccessExample),
        unauthorizedResponse(),
        notFoundResponse("Cliente não encontrado."),
        internalErrorResponse
      )
    )

  private val patientsPostOperation: Json =
    operation(
      tag = tagName,
      summary = "Cadastra um novo paciente vinculado a um tutor.",
      description = "Cadastra o paciente (PET ou HUMAN) com detalhes biológicos livres em JSON.",
      operationId = "patientsCreate",
      requestBody = Some("PatientRequest"),
      responses = List(
        "201" -> jsonResponseWithExample("Paciente cadastrado com sucesso.", successEnvelope("CreatePatientResponse"), createPatientSuccessExample),
        simpleValidationResponse("Requisição inválida ou campos incorretos.", "name", "Nome do paciente é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Cliente não encontrado."),
        internalErrorResponse
      )
    )

  private val patientsPutOperation: Json =
    operation(
      tag = tagName,
      summary = "Atualiza os dados de um paciente.",
      description = "Atualiza as informações do paciente pertencente ao tutor do usuário autenticado.",
      operationId = "patientsUpdate",
      parameters = List(pathIdParam("id", "ID do paciente.")),
      requestBody = Some("UpdatePatientRequest"),
      responses = List(
        "200" -> jsonResponseWithExample("Paciente atualizado com sucesso.", successEnvelope("PatientResponse"), createPatientSuccessExample),
        simpleValidationResponse("Requisição inválida ou campos incorretos.", "name", "Nome do paciente é obrigatório."),
        unauthorizedResponse(),
        notFoundResponse("Paciente não encontrado."),
        internalErrorResponse
      )
    )

  private val patientsDeleteOperation: Json =
    operation(
      tag = tagName,
      summary = "Exclui um paciente.",
      description = "Remove o paciente pertencente ao usuário autenticado caso não haja consultas vinculadas.",
      operationId = "patientsDelete",
      parameters = List(pathIdParam("id", "ID do paciente.")),
      responses = List(
        "200" -> jsonResponseWithExample(
          "Paciente excluído com sucesso.",
          errorEnvelope,
          successExample("Paciente excluído com sucesso.", Json.Null)
        ),
        unauthorizedResponse(),
        notFoundResponse("Paciente não encontrado."),
        conflictResponse("Conflito. Existem consultas vinculadas.", "Não é possível excluir o paciente pois existem consultas vinculadas."),
        internalErrorResponse
      )
    )

  val paths: List[(String, Json)] = List(
    "/api/v1/patients" -> obj("get" -> patientsGetOperation, "post" -> patientsPostOperation),
    "/api/v1/patients/{id}" -> obj("put" -> patientsPutOperation, "delete" -> patientsDeleteOperation)
  )

  private val patientTypeEnum: Json = Json.arr(str("PET"), str("HUMAN"))

  val schemas: List[(String, Json)] = List(
    "PatientRequest" -> requiredObject(
      List("client_id", "name", "patient_type", "birth_date", "biological_details"),
      "client_id" -> obj("type" -> str("integer"), "description" -> str("ID do tutor/cliente (Snowflake).")),
      "name" -> obj("type" -> str("string"), "description" -> str("Nome do paciente.")),
      "patient_type" -> obj("type" -> str("string"), "enum" -> patientTypeEnum, "description" -> str("Tipo de paciente (PET ou HUMAN).")),
      "birth_date" -> obj("type" -> str("string"), "format" -> str("date"), "description" -> str("Data de nascimento (YYYY-MM-DD).")),
      "biological_details" -> obj("type" -> str("object"), "description" -> str("Detalhes biológicos livres em formato JSON."))
    ),
    "PatientResponse" -> requiredObject(
      List("id", "name", "patientType", "biologicalDetails", "createdAt"),
      "id" -> obj("type" -> str("string"), "description" -> str("ID do paciente (Snowflake).")),
      "clientId" -> stringNullableField,
      "name" -> stringField,
      "patientType" -> obj("type" -> str("string"), "enum" -> patientTypeEnum),
      "birthDate" -> stringNullableField,
      "biologicalDetails" -> obj("type" -> str("object")),
      "createdAt" -> dateTimeField
    ),
    "CreatePatientResponse" -> requiredObject(
      List("patient"),
      "patient" -> ref("PatientResponse")
    ),
    "PatientListResponse" -> requiredObject(
      List("patients"),
      "patients" -> arrayOf(ref("PatientResponse"))
    ),
    "UpdatePatientRequest" -> requiredObject(
      List("name", "patient_type", "biological_details"),
      "name" -> obj("type" -> str("string"), "description" -> str("Nome do paciente.")),
      "patient_type" -> obj("type" -> str("string"), "enum" -> patientTypeEnum, "description" -> str("Tipo de paciente (PET ou HUMAN).")),
      "birth_date" -> obj("type" -> str("string"), "format" -> str("date"), "nullable" -> bool(true), "description" -> str("Data de nascimento (YYYY-MM-DD).")),
      "biological_details" -> obj("type" -> str("object"), "description" -> str("Detalhes biológicos livres em formato JSON (raça, pelagem, idade, etc.)."))
    )
  )
