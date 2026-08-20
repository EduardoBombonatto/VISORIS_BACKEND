package com.visoris.backend.docs

import io.circe.Json

object OpenApiSpec:

  private def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  private def str(s: String): Json = Json.fromString(s)
  private def int(i: Int): Json = Json.fromInt(i)
  private def bool(b: Boolean): Json = Json.fromBoolean(b)
  private def ref(name: String): Json = obj("$ref" -> str(s"#/components/schemas/$name"))

  private val stringField: Json = obj("type" -> str("string"))
  private val stringNullableField: Json = obj("type" -> str("string"), "nullable" -> bool(true))
  private val int32Field: Json = obj("type" -> str("integer"), "format" -> str("int32"))
  private val booleanField: Json = obj("type" -> str("boolean"))
  private val dateTimeField: Json = obj("type" -> str("string"), "format" -> str("date-time"))

  private def requiredObject(required: List[String], props: (String, Json)*): Json =
    obj(
      "type" -> str("object"),
      "required" -> Json.arr(required.map(str)*),
      "properties" -> obj(props*)
    )

  private def arrayOf(itemSchema: Json): Json =
    obj("type" -> str("array"), "items" -> itemSchema)

  private def envelope(dataSchema: Json): Json =
    requiredObject(
      List("erro", "message", "data", "httpcode", "timestamp"),
      "erro" -> booleanField,
      "message" -> stringField,
      "data" -> dataSchema,
      "httpcode" -> int32Field,
      "timestamp" -> dateTimeField
    )

  private val errorEnvelope: Json =
    envelope(obj("type" -> str("object"), "nullable" -> bool(true)))

  private val validationEnvelope: Json =
    envelope(
      requiredObject(
        List("errors"),
        "errors" -> arrayOf(ref("ValidationError"))
      )
    )

  private def validationOrErrorSchema: Json =
    obj("oneOf" -> Json.arr(errorEnvelope, validationEnvelope))

  private def successEnvelope(dataRef: String): Json =
    envelope(ref(dataRef))

  private def jsonResponseWithExample(description: String, schema: Json, exampleValue: Json): Json =
    obj(
      "description" -> str(description),
      "content" -> obj(
        "application/json" -> obj("schema" -> schema, "example" -> exampleValue)
      )
    )

  private def jsonResponseWithExamples(description: String, schema: Json, examples: List[(String, Json)]): Json =
    obj(
      "description" -> str(description),
      "content" -> obj(
        "application/json" -> obj(
          "schema" -> schema,
          "examples" -> obj(examples.map { case (k, v) => k -> obj("value" -> v) }*)
        )
      )
    )

  private def jsonResponseWithCookies(description: String, schema: Json, exampleValue: Json, cookieDescription: String): Json =
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

  private def errorResponse(description: String, exampleValue: Json): Json =
    jsonResponseWithExample(description, errorEnvelope, exampleValue)

  private def errorResponses(description: String, examples: List[(String, Json)]): Json =
    jsonResponseWithExamples(description, errorEnvelope, examples)

  private def validationResponse(description: String, examples: List[(String, Json)]): Json =
    jsonResponseWithExamples(description, validationOrErrorSchema, examples)

  // Examples -----------------------------------------------------------------

  private val timestampExample: String = "2026-08-12T10:15:30Z"

  private def genericErrorExample(message: String, httpcode: Int): Json =
    obj(
      "erro" -> bool(true),
      "message" -> str(message),
      "data" -> Json.Null,
      "httpcode" -> int(httpcode),
      "timestamp" -> str(timestampExample)
    )

  private def validationExample(errors: List[(String, String)]): Json =
    obj(
      "erro" -> bool(true),
      "message" -> str("Dados inválidos."),
      "data" -> obj(
        "errors" -> Json.arr(errors.map { case (f, m) => obj("field" -> str(f), "message" -> str(m)) }*)
      ),
      "httpcode" -> int(400),
      "timestamp" -> str(timestampExample)
    )

  private val loginSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Autenticado com sucesso."),
      "data" -> obj(
        "user" -> obj(
          "id" -> str("8712345678901234567"),
          "fullName" -> str("Dra. Maria Souza"),
          "professionalDocument" -> str("CRM/SP 123456")
        )
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val registerSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Conta criada com sucesso."),
      "data" -> obj(
        "user" -> obj(
          "id" -> str("8712345678901234567"),
          "fullName" -> str("Dra. Maria Souza"),
          "professionalDocument" -> str("CRM/SP 123456")
        )
      ),
      "httpcode" -> int(201),
      "timestamp" -> str(timestampExample)
    )

  private val meSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Sessão restaurada com sucesso."),
      "data" -> obj(
        "user" -> obj(
          "id" -> str("8712345678901234567"),
          "fullName" -> str("Dra. Maria Souza"),
          "professionalDocument" -> str("CRM/SP 123456")
        )
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val refreshSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Sessão renovada com sucesso."),
      "data" -> obj("expiresIn" -> int(900)),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val logoutSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Logout realizado com sucesso."),
      "data" -> Json.Null,
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  // Cookie descriptions -------------------------------------------------------

  private val sessionCookies: String =
    "Define os cookies de sessão: `accessToken` (HttpOnly, Secure, SameSite=Strict, " +
      "Path=/api/v1, Max-Age=900) e `refreshToken` (HttpOnly, Secure, SameSite=Strict, " +
      "Path=/, Max-Age=604800)."

  private val refreshedSessionCookies: String =
    "Define os cookies de sessão renovada: `accessToken` (HttpOnly, Secure, SameSite=Strict, " +
      "Path=/api/v1, Max-Age=900) e novo `refreshToken` (HttpOnly, Secure, SameSite=Strict, " +
      "Path=/, Max-Age=604800). O refresh token anterior é rotacionado (revogado)."

  private val logoutClearingCookies: String =
    "Limpa os cookies de sessão: `accessToken` (Path=/api/v1) e `refreshToken` (Path=/), " +
      "todos com `Max-Age=0` para que o navegador os remova imediatamente."

  // Operations ----------------------------------------------------------------

  private val authTag = "Auth"

  private val loginPath: (String, Json) =
    "/api/v1/auth/login" -> obj(
      "post" -> obj(
        "tags" -> Json.arr(str(authTag)),
        "summary" -> str("Autentica um usuário e inicia a sessão."),
        "description" -> str(
          """Valida as credenciais do usuário. Em caso de sucesso retorna os dados do usuário
            |(ID, nome completo e documento profissional) e define os cookies `accessToken`
            |(900s) e `refreshToken` (7 dias).
            |
            |Falhas de autenticação retornam sempre a mesma mensagem genérica
            |"Credenciais inválidas." para não revelar quais credenciais estão incorretas.""".stripMargin),
        "operationId" -> str("authLogin"),
        "requestBody" -> obj(
          "required" -> bool(true),
          "content" -> obj("application/json" -> obj("schema" -> ref("LoginRequest")))
        ),
        "responses" -> obj(
          "200" -> jsonResponseWithCookies(
            "Login bem-sucedido. Retorna os dados do usuário e define os cookies `accessToken` e `refreshToken`.",
            successEnvelope("LoginResponse"),
            loginSuccessExample,
            sessionCookies
          ),
          "400" -> validationResponse(
            """Requisição inválida. Dois cenários possíveis:
              |1. Corpo malformado ou campos com tipos incorretos — retorna `erro=true`, `data=null` e
              |   message "Requisição inválida. Verifique o formato dos dados.".
              |2. Falha de validação dos campos — retorna `data.errors` com um erro por campo:
              |   e-mail obrigatório, formato de e-mail inválido e senha obrigatória.""".stripMargin,
            List(
              "corpo-malformado" -> genericErrorExample("Requisição inválida. Verifique o formato dos dados.", 400),
              "dados-invalidos" -> validationExample(List(
                "email" -> "Formato de e-mail inválido.",
                "password" -> "Senha é obrigatória."
              ))
            )
          ),
          "401" -> errorResponse(
            """Credenciais inválidas. Retornado quando o e-mail não existe ou a senha está incorreta.
              |A mensagem é genérica de propósito.""".stripMargin,
            genericErrorExample("Credenciais inválidas.", 401)
          ),
          "500" -> errorResponse(
            "Erro interno do servidor. Retorna uma mensagem genérica sem detalhes técnicos.",
            genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
          )
        )
      )
    )

  private val refreshPath: (String, Json) =
    "/api/v1/auth/refresh" -> obj(
      "post" -> obj(
        "tags" -> Json.arr(str(authTag)),
        "summary" -> str("Renova a sessão usando o refresh token (cookie)."),
        "description" -> str(
          """Rotaciona o `refreshToken` recebido via cookie e emite um novo `accessToken` + novo `refreshToken`.
            |O refresh token antigo é revogado imediatamente (single-use). Não possui corpo de requisição.""".stripMargin),
        "operationId" -> str("authRefresh"),
        "security" -> Json.arr(obj("refreshTokenCookie" -> Json.arr())),
        "responses" -> obj(
          "200" -> jsonResponseWithCookies(
            "Sessão renovada com sucesso. Define os cookies `accessToken` (900s) e novo `refreshToken` (7 dias).",
            successEnvelope("RefreshResponse"),
            refreshSuccessExample,
            refreshedSessionCookies
          ),
          "401" -> errorResponses(
            """Não autorizado. Vários cenários possíveis, todos com `erro=true` e `data=null`:
              |1. Cookie `refreshToken` ausente → "Refresh token inválido."
              |2. Refresh token desconhecido → "Refresh token inválido."
              |3. Refresh token expirado → "Sessão expirada."
              |4. Refresh token revogado → "Refresh token revogado." (indício de roubo; todas as sessões do usuário são revogadas)""".stripMargin,
            List(
              "cookie-ausente" -> genericErrorExample("Refresh token inválido.", 401),
              "token-expirado" -> genericErrorExample("Sessão expirada.", 401),
              "token-revogado" -> genericErrorExample("Refresh token revogado.", 401)
            )
          ),
          "500" -> errorResponse(
            "Erro interno do servidor.",
            genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
          )
        )
      )
    )

  private val registerPath: (String, Json) =
    "/api/v1/auth/register" -> obj(
      "post" -> obj(
        "tags" -> Json.arr(str(authTag)),
        "summary" -> str("Cria uma conta de usuário (DOCTOR) e inicia a sessão."),
        "description" -> str(
          """Cria a conta e já emite os cookies `accessToken` (900s) e `refreshToken` (7 dias).
            |
            |Regras de validação:
            |- `fullName`, `email`, `password` e `professionalDocument` são obrigatórios (o documento pode vir `null`, mas não vazio).
            |- `email` deve ter formato válido e não ser de domínio descartável.
            |- `password` deve ter no mínimo 8 caracteres, conter maiúscula, minúscula, número e caractere especial, e apenas caracteres ASCII.
            |- Limites: `fullName` e `email` até 255 caracteres; `professionalDocument` até 50.""".stripMargin),
        "operationId" -> str("authRegister"),
        "requestBody" -> obj(
          "required" -> bool(true),
          "content" -> obj("application/json" -> obj("schema" -> ref("RegisterRequest")))
        ),
        "responses" -> obj(
          "201" -> jsonResponseWithCookies(
            "Conta criada com sucesso. Retorna os dados do usuário e define os cookies `accessToken` e `refreshToken`.",
            successEnvelope("RegisterResponse"),
            registerSuccessExample,
            sessionCookies
          ),
          "400" -> validationResponse(
            """Requisição inválida. Dois cenários possíveis:
              |1. Corpo malformado ou tipos incorretos — `data=null` com message "Requisição inválida. Verifique o formato dos dados.".
              |2. Falha de validação — `data.errors` com um erro por campo, incluindo:
              |   campos obrigatórios, formato de e-mail, e-mail descartável, regras de senha,
              |   limites de tamanho e documento profissional duplicado.""".stripMargin,
            List(
              "corpo-malformado" -> genericErrorExample("Requisição inválida. Verifique o formato dos dados.", 400),
              "senha-fraca" -> validationExample(List(
                "password" -> "A senha deve conter pelo menos 8 caracteres.",
                "password" -> "A senha deve conter pelo menos uma letra maiúscula.",
                "password" -> "A senha deve conter pelo menos um caractere especial."
              )),
              "e-mail-descartavel" -> validationExample(List(
                "email" -> "Por favor, use um e-mail profissional ou pessoal válido."
              )),
              "documento-duplicado" -> validationExample(List(
                "professionalDocument" -> "Documento profissional já cadastrado."
              ))
            )
          ),
          "409" -> errorResponse(
            "Conflito. O e-mail informado já está cadastrado.",
            genericErrorExample("Este e-mail já está cadastrado.", 409)
          ),
          "500" -> errorResponse(
            "Erro interno do servidor.",
            genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
          )
        )
      )
    )

  private val mePath: (String, Json) =
    "/api/v1/auth/me" -> obj(
      "get" -> obj(
        "tags" -> Json.arr(str(authTag)),
        "summary" -> str("Restaura a sessão do usuário autenticado."),
        "description" -> str(
          """Retorna o usuário autenticado a partir dos cookies.
            |Se o `accessToken` for válido, a sessão é retornada diretamente. Caso contrário,
            |tenta rotacionar a sessão usando o `refreshToken` (emitindo novos cookies `accessToken`
            |e `refreshToken`) antes de retornar os dados.""".stripMargin),
        "operationId" -> str("authMe"),
        "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr()), obj("refreshTokenCookie" -> Json.arr())),
        "responses" -> obj(
          "200" -> jsonResponseWithCookies(
            "Sessão restaurada com sucesso. Retorna o usuário. Quando a sessão é renovada, define os cookies `accessToken` e novo `refreshToken`.",
            successEnvelope("LoginResponse"),
            meSuccessExample,
            refreshedSessionCookies
          ),
          "401" -> errorResponses(
            """Não autenticado. Retornado quando não há `accessToken` válido e o `refreshToken`
              |está ausente, expirado, desconhecido ou revogado.""".stripMargin,
            List(
              "nao-autenticado" -> genericErrorExample("Não autenticado.", 401)
            )
          ),
          "500" -> errorResponse(
            "Erro interno do servidor.",
            genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
          )
        )
      )
    )

  private val logoutPath: (String, Json) =
    "/api/v1/auth/logout" -> obj(
      "post" -> obj(
        "tags" -> Json.arr(str(authTag)),
        "summary" -> str("Encerra a sessão atual e limpa os cookies de sessão."),
        "description" -> str(
          """Revoga o `refreshToken` recebido via cookie e limpa os cookies `accessToken` e `refreshToken`
            |(Max-Age=0). Não possui corpo de requisição e é idempotente: cookies ausentes,
            |desconhecidos ou já revogados ainda retornam 200.""".stripMargin),
        "operationId" -> str("authLogout"),
        "security" -> Json.arr(obj("refreshTokenCookie" -> Json.arr())),
        "responses" -> obj(
          "200" -> jsonResponseWithCookies(
            "Sessão encerrada. Limpa os cookies `accessToken` e `refreshToken` (Max-Age=0).",
            envelope(obj("type" -> str("object"), "nullable" -> bool(true))),
            logoutSuccessExample,
            logoutClearingCookies
          ),
          "500" -> errorResponse(
            "Erro interno do servidor. Os cookies de sessão ainda são limpos.",
            genericErrorExample("Erro interno ao processar o logout.", 500)
          )
        )
      )
    )

  // Components ------------------------------------------------------------------

  private val securitySchemes: Json =
    obj(
      "refreshTokenCookie" -> obj(
        "type" -> str("apiKey"),
        "in" -> str("cookie"),
        "name" -> str("refreshToken"),
        "description" -> str(
          "Refresh Token emitido em login/registro e rotacionado em POST /api/v1/auth/refresh. " +
            "Expira em 7 dias (Path=/). Use o valor exibido no cookie após login/registro."
        )
      ),
      "accessTokenCookie" -> obj(
        "type" -> str("apiKey"),
        "in" -> str("cookie"),
        "name" -> str("accessToken"),
        "description" -> str(
          "Access Token emitido em login/registro e renovado em POST /api/v1/auth/refresh. " +
            "Expira em 900s e autentica as demais rotas sob /api/v1."
        )
      )
    )

  private val schemas: Json =
    obj(
      "LoginRequest" -> requiredObject(
        List("email", "password"),
        "email" -> obj(
          "type" -> str("string"),
          "description" -> str("E-mail do usuário (não pode ser vazio e deve ter formato válido).")
        ),
        "password" -> obj(
          "type" -> str("string"),
          "description" -> str("Senha do usuário (não pode ser vazia).")
        )
      ),
      "RegisterRequest" -> requiredObject(
        List("fullName", "email", "password"),
        "fullName" -> stringField,
        "email" -> stringField,
        "password" -> stringField,
        "professionalDocument" -> stringNullableField
      ),
      "UserData" -> requiredObject(
        List("id", "fullName"),
        "id" -> obj("type" -> str("string"), "description" -> str("ID do usuário (Snowflake).")),
        "fullName" -> stringField,
        "professionalDocument" -> stringNullableField
      ),
      "LoginResponse" -> requiredObject(
        List("user"),
        "user" -> ref("UserData")
      ),
      "RegisterResponse" -> requiredObject(
        List("user"),
        "user" -> ref("UserData")
      ),
      "RefreshResponse" -> requiredObject(
        List("expiresIn"),
        "expiresIn" -> obj("type" -> str("integer"), "format" -> str("int32"), "description" -> str("Validade do access token em segundos."))
      ),
      "ValidationError" -> requiredObject(
        List("field", "message"),
        "field" -> obj("type" -> str("string"), "description" -> str("Nome do campo que falhou na validação.")),
        "message" -> obj("type" -> str("string"), "description" -> str("Mensagem de erro do campo."))
      )
    )

  private val document: Json =
    obj(
      "openapi" -> str("3.0.1"),
      "info" -> obj(
        "title" -> str("Visoris API"),
        "version" -> str("0.0.1-SNAPSHOT"),
        "description" -> str(
          """API de autenticação e sessões do Visoris.
            |
            |O fluxo de autenticação é baseado em cookies HttpOnly:
            |1. `POST /api/v1/auth/register` ou `POST /api/v1/auth/login` → recebe os cookies `accessToken` e `refreshToken`.
            |2. `POST /api/v1/auth/refresh` com o `refreshToken` → renova `accessToken` e `refreshToken`.
            |3. `POST /api/v1/auth/logout` com o `refreshToken` → revoga o token e limpa os cookies de sessão.
            |
            |Todos os tokens são transportados exclusivamente via cookies. Para testar no Swagger UI,
            |faça login/registro e copie os valores dos cookies de resposta para a seção Authorize.""".stripMargin
        )
      ),
      "servers" -> Json.arr(
        obj("url" -> str("http://localhost:8080"), "description" -> str("Servidor de desenvolvimento local"))
      ),
      "tags" -> Json.arr(
        obj("name" -> str(authTag), "description" -> str("Autenticação, registro e renovação de sessão."))
      ),
      "paths" -> obj(loginPath, refreshPath, registerPath, mePath, logoutPath),
      "components" -> obj(
        "securitySchemes" -> securitySchemes,
        "schemas" -> schemas
      )
    )

  def json: Json = document
