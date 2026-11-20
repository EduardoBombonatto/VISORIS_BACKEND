package com.visoris.backend.docs.specs

import io.circe.Json

trait DomainOpenApiSpec:
  def tagName: String
  def tagDescription: String
  def paths: List[(String, Json)]
  def schemas: List[(String, Json)]

object OpenApiDsl:

  val timestampExample: String = "2026-08-12T10:15:30Z"

  def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  def str(s: String): Json = Json.fromString(s)
  def int(i: Int): Json = Json.fromInt(i)
  def bool(b: Boolean): Json = Json.fromBoolean(b)
  def ref(name: String): Json = obj("$ref" -> str(s"#/components/schemas/$name"))

  val stringField: Json = obj("type" -> str("string"))
  val stringNullableField: Json = obj("type" -> str("string"), "nullable" -> bool(true))
  val numberNullableField: Json = obj("type" -> str("number"), "nullable" -> bool(true))
  val int32Field: Json = obj("type" -> str("integer"), "format" -> str("int32"))
  val booleanField: Json = obj("type" -> str("boolean"))
  val dateTimeField: Json = obj("type" -> str("string"), "format" -> str("date-time"))

  val accessTokenSecurity: Json = Json.arr(obj("accessTokenCookie" -> Json.arr()))
  val refreshTokenSecurity: Json = Json.arr(obj("refreshTokenCookie" -> Json.arr()))

  def requiredObject(required: List[String], props: (String, Json)*): Json =
    obj(
      "type" -> str("object"),
      "required" -> Json.arr(required.map(str)*),
      "properties" -> obj(props*)
    )

  def arrayOf(itemSchema: Json): Json =
    obj("type" -> str("array"), "items" -> itemSchema)

  def envelope(dataSchema: Json): Json =
    requiredObject(
      List("erro", "message", "data", "httpcode", "timestamp"),
      "erro" -> booleanField,
      "message" -> stringField,
      "data" -> dataSchema,
      "httpcode" -> int32Field,
      "timestamp" -> dateTimeField
    )

  val errorEnvelope: Json =
    envelope(obj("type" -> str("object"), "nullable" -> bool(true)))

  val validationEnvelope: Json =
    envelope(
      requiredObject(
        List("errors"),
        "errors" -> arrayOf(ref("ValidationError"))
      )
    )

  val validationOrErrorSchema: Json =
    obj("oneOf" -> Json.arr(errorEnvelope, validationEnvelope))

  def successEnvelope(dataRef: String): Json =
    envelope(ref(dataRef))

  def successExample(message: String, data: Json, httpcode: Int = 200): Json =
    obj(
      "erro" -> bool(false),
      "message" -> str(message),
      "data" -> data,
      "httpcode" -> int(httpcode),
      "timestamp" -> str(timestampExample)
    )

  def genericErrorExample(message: String, httpcode: Int): Json =
    obj(
      "erro" -> bool(true),
      "message" -> str(message),
      "data" -> Json.Null,
      "httpcode" -> int(httpcode),
      "timestamp" -> str(timestampExample)
    )

  def validationExample(errors: List[(String, String)]): Json =
    obj(
      "erro" -> bool(true),
      "message" -> str("Dados inválidos."),
      "data" -> obj(
        "errors" -> Json.arr(errors.map { case (f, m) => obj("field" -> str(f), "message" -> str(m)) }*)
      ),
      "httpcode" -> int(400),
      "timestamp" -> str(timestampExample)
    )

  def jsonRequestBody(schemaRef: String): Json =
    obj(
      "required" -> bool(true),
      "content" -> obj("application/json" -> obj("schema" -> ref(schemaRef)))
    )

  def jsonResponseWithExample(description: String, schema: Json, exampleValue: Json): Json =
    obj(
      "description" -> str(description),
      "content" -> obj(
        "application/json" -> obj("schema" -> schema, "example" -> exampleValue)
      )
    )

  def jsonResponseWithExamples(description: String, schema: Json, examples: List[(String, Json)]): Json =
    obj(
      "description" -> str(description),
      "content" -> obj(
        "application/json" -> obj(
          "schema" -> schema,
          "examples" -> obj(examples.map { case (k, v) => k -> obj("value" -> v) }*)
        )
      )
    )

  def jsonResponseWithCookies(
      description: String,
      schema: Json,
      exampleValue: Json,
      cookieDescription: String
  ): Json =
    obj(
      "description" -> str(description),
      "headers" -> obj(
        "Set-Cookie" -> obj(
          "description" -> str(cookieDescription),
          "schema" -> obj("type" -> str("string"))
        )
      ),
      "content" -> obj(
        "application/json" -> obj("schema" -> schema, "example" -> exampleValue)
      )
    )

  def errorResponse(description: String, exampleValue: Json): Json =
    jsonResponseWithExample(description, errorEnvelope, exampleValue)

  def errorResponses(description: String, examples: List[(String, Json)]): Json =
    jsonResponseWithExamples(description, errorEnvelope, examples)

  def validationResponse(description: String, examples: List[(String, Json)]): Json =
    jsonResponseWithExamples(description, validationOrErrorSchema, examples)

  def simpleValidationResponse(description: String, field: String, message: String): (String, Json) =
    "400" -> validationResponse(description, List("dados-invalidos" -> validationExample(List(field -> message))))

  def unauthorizedResponse(description: String = "Não autenticado."): (String, Json) =
    "401" -> errorResponse(description, genericErrorExample("Não autenticado.", 401))

  def notFoundResponse(message: String): (String, Json) =
    "404" -> errorResponse(message, genericErrorExample(message, 404))

  def conflictResponse(description: String, message: String): (String, Json) =
    "409" -> errorResponse(description, genericErrorExample(message, 409))

  val internalErrorResponse: (String, Json) =
    "500" -> errorResponse(
      "Erro interno do servidor.",
      genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
    )

  def pathIdParam(name: String, description: String): Json =
    obj(
      "name" -> str(name),
      "in" -> str("path"),
      "required" -> bool(true),
      "schema" -> obj("type" -> str("integer")),
      "description" -> str(description)
    )

  def queryParam(name: String, schema: Json, required: Boolean = false, description: Option[String] = None): Json =
    val base = List(
      "name" -> str(name),
      "in" -> str("query"),
      "required" -> bool(required),
      "schema" -> schema
    )
    obj((base ++ description.map(d => "description" -> str(d)))*)

  val paginationParams: List[Json] = List(
    queryParam("limit", obj("type" -> str("integer"), "default" -> int(50))),
    queryParam("offset", obj("type" -> str("integer"), "default" -> int(0)))
  )

  def operation(
      tag: String,
      summary: String,
      description: String,
      operationId: String,
      responses: List[(String, Json)],
      security: Option[Json] = Some(accessTokenSecurity),
      parameters: List[Json] = Nil,
      requestBody: Option[String] = None
  ): Json =
    val base = List(
      "tags" -> Json.arr(str(tag)),
      "summary" -> str(summary),
      "description" -> str(description),
      "operationId" -> str(operationId)
    )
    val optSecurity = security.map("security" -> _).toList
    val optParams = if parameters.nonEmpty then List("parameters" -> Json.arr(parameters*)) else Nil
    val optBody = requestBody.map(r => "requestBody" -> jsonRequestBody(r)).toList
    obj((base ++ optSecurity ++ optParams ++ optBody ++ List("responses" -> obj(responses*)))*)
