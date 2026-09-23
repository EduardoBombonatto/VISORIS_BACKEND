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
  private val numberNullableField: Json = obj("type" -> str("number"), "nullable" -> bool(true))
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
  private val clinicsTag = "Clinics"
  private val clientsTag = "Clients"
  private val patientsTag = "Patients"
  private val appointmentsTag = "Appointments"
  private val templatesTag = "Templates"

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

  // Clinics ---------------------------------------------------------------------

  private val clinicsListSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Clínicas listadas com sucesso."),
      "data" -> obj(
        "clinics" -> Json.arr(
          obj(
            "id" -> str("8712345678901234567"),
            "name" -> str("Clínica Vida"),
            "cnpj" -> str("11444777000161"),
            "phone" -> str("(11) 5555-0000"),
            "address" -> str("Av. Paulista, 1000")
          )
        )
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val createClinicSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Clínica criada com sucesso."),
      "data" -> obj(
        "clinic" -> obj(
          "id" -> str("8712345678901234567"),
          "name" -> str("Clínica Vida"),
          "cnpj" -> str("11444777000161"),
          "phone" -> str("(11) 5555-0000"),
          "address" -> str("Av. Paulista, 1000")
        )
      ),
      "httpcode" -> int(201),
      "timestamp" -> str(timestampExample)
    )

  private val reuseClinicSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Clínica vinculada com sucesso."),
      "data" -> obj(
        "clinic" -> obj(
          "id" -> str("8712345678901234567"),
          "name" -> str("Clínica Vida"),
          "cnpj" -> str("11444777000161"),
          "phone" -> str("(11) 5555-0000"),
          "address" -> str("Av. Paulista, 1000")
        )
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val clinicsGetOperation: Json =
    obj(
      "tags" -> Json.arr(str(clinicsTag)),
      "summary" -> str("Lista as clínicas vinculadas ao usuário autenticado."),
      "description" -> str(
        """Retorna somente as clínicas vinculadas ao usuário autenticado (JOIN entre
          |`clinics` e `doctor_clinics` via `user_id`). Nunca retorna clínicas de outros
          |usuários. Retorna uma lista vazia quando não há vínculos.""".stripMargin),
      "operationId" -> str("clinicsList"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "responses" -> obj(
        "200" -> jsonResponseWithExample(
          "Lista de clínicas do usuário autenticado.",
          successEnvelope("ClinicListResponse"),
          clinicsListSuccessExample
        ),
        "401" -> errorResponse(
          "Não autenticado. Token de acesso ausente, inválido ou expirado.",
          genericErrorExample("Não autenticado.", 401)
        ),
        "500" -> errorResponse(
          "Erro interno do servidor.",
          genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
        )
      )
    )

  private val clinicsPostOperation: Json =
    obj(
      "tags" -> Json.arr(str(clinicsTag)),
      "summary" -> str("Registra ou vincula uma clínica ao usuário autenticado."),
      "description" -> str(
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
          |  é reutilizada/vinculada.""".stripMargin),
      "operationId" -> str("clinicsCreate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("CreateClinicRequest")))
      ),
      "responses" -> obj(
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
            "nome-obrigatorio" -> validationExample(List(
              "name" -> "Nome da clínica é obrigatório."
            )),
            "cnpj-invalido" -> validationExample(List(
              "cnpj" -> "CNPJ inválido."
            ))
          )
        ),
        "401" -> errorResponse(
          "Não autenticado. Token de acesso ausente, inválido ou expirado.",
          genericErrorExample("Não autenticado.", 401)
        ),
        "500" -> errorResponse(
          "Erro interno do servidor.",
          genericErrorExample("Erro interno do servidor. Tente novamente.", 500)
        )
      )
    )

  private val clinicsPath: (String, Json) =
    "/api/v1/clinics" -> obj("get" -> clinicsGetOperation, "post" -> clinicsPostOperation)

  // Clients ---------------------------------------------------------------------

  private val createClientSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Cliente cadastrado com sucesso."),
      "data" -> obj("id" -> str("8712345678901239999")),
      "httpcode" -> int(201),
      "timestamp" -> str(timestampExample)
    )

  private val clientsListSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Clientes listados com sucesso."),
      "data" -> obj(
        "clients" -> Json.arr(
          obj(
            "id" -> str("8712345678901239999"),
            "userId" -> str("8712345678901234567"),
            "fullName" -> str("Carlos Silva"),
            "documentCpf" -> str("12345678909"),
            "email" -> str("carlos@example.com"),
            "phone" -> str("11987654321"),
            "createdAt" -> str(timestampExample)
          )
        )
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val clientsGetOperation: Json =
    obj(
      "tags" -> Json.arr(str(clientsTag)),
      "summary" -> str("Lista os tutores/clientes vinculados ao usuário logado."),
      "description" -> str("Retorna a lista paginada de tutores do usuário autenticado."),
      "operationId" -> str("clientsList"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("limit"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("integer"), "default" -> int(50))),
        obj("name" -> str("offset"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("integer"), "default" -> int(0)))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Clientes listados com sucesso.", successEnvelope("ClientListResponse"), clientsListSuccessExample),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Usuário não encontrado.", genericErrorExample("Usuário não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val clientsPostOperation: Json =
    obj(
      "tags" -> Json.arr(str(clientsTag)),
      "summary" -> str("Cadastra um novo tutor/cliente vinculado ao usuário logado."),
      "description" -> str("Cadastra o cliente e retorna o ID gerado."),
      "operationId" -> str("clientsCreate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("ClientRequest")))
      ),
      "responses" -> obj(
        "201" -> jsonResponseWithExample("Cliente cadastrado com sucesso.", successEnvelope("CreateClientResponse"), createClientSuccessExample),
        "400" -> validationResponse("Requisição inválida ou campos obrigatórios ausentes.", List("dados-invalidos" -> validationExample(List("full_name" -> "Nome do tutor é obrigatório.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Usuário não encontrado.", genericErrorExample("Usuário não encontrado.", 404)),
        "409" -> errorResponse("Conflito de CPF.", genericErrorExample("Este CPF já está cadastrado para este usuário.", 409)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val clientsPath: (String, Json) =
    "/api/v1/clients" -> obj("get" -> clientsGetOperation, "post" -> clientsPostOperation)

  private val clientsPutOperation: Json =
    obj(
      "tags" -> Json.arr(str(clientsTag)),
      "summary" -> str("Atualiza os dados de um tutor/cliente."),
      "description" -> str("Atualiza as informações do tutor pertencente ao usuário autenticado."),
      "operationId" -> str("clientsUpdate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do cliente."))
      ),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("ClientRequest")))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Cliente atualizado com sucesso.", successEnvelope("ClientResponse"), obj("erro" -> bool(false), "message" -> str("Cliente atualizado com sucesso."), "data" -> obj("id" -> str("8712345678901239999"), "userId" -> str("8712345678901234567"), "fullName" -> str("Carlos Silva"), "documentCpf" -> str("12345678909"), "email" -> str("carlos@example.com"), "phone" -> str("11987654321"), "createdAt" -> str(timestampExample)), "httpcode" -> int(200), "timestamp" -> str(timestampExample))),
        "400" -> validationResponse("Requisição inválida ou campos obrigatórios ausentes.", List("dados-invalidos" -> validationExample(List("full_name" -> "Nome do tutor é obrigatório.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Cliente não encontrado.", genericErrorExample("Cliente não encontrado.", 404)),
        "409" -> errorResponse("Conflito de CPF, e-mail ou telefone.", genericErrorExample("Este CPF já está cadastrado para este usuário.", 409)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val clientsDeleteOperation: Json =
    obj(
      "tags" -> Json.arr(str(clientsTag)),
      "summary" -> str("Exclui um tutor/cliente."),
      "description" -> str("Remove um tutor vinculado ao usuário autenticado caso não haja pacientes vinculados."),
      "operationId" -> str("clientsDelete"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do cliente."))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Cliente excluído com sucesso.", errorEnvelope, obj("erro" -> bool(false), "message" -> str("Cliente excluído com sucesso."), "data" -> Json.Null, "httpcode" -> int(200), "timestamp" -> str(timestampExample))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Cliente não encontrado.", genericErrorExample("Cliente não encontrado.", 404)),
        "409" -> errorResponse("Conflito. Existem pacientes vinculados.", genericErrorExample("Não é possível excluir o tutor pois existem pacientes vinculados.", 409)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val clientsItemPath: (String, Json) =
    "/api/v1/clients/{id}" -> obj("put" -> clientsPutOperation, "delete" -> clientsDeleteOperation)

  // Patients --------------------------------------------------------------------

  private val createPatientSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Paciente cadastrado com sucesso."),
      "data" -> obj(
        "patient" -> obj(
          "id" -> str("8712345678901238888"),
          "clientId" -> str("8712345678901239999"),
          "name" -> str("Rex"),
          "patientType" -> str("PET"),
          "birthDate" -> str("2022-05-10"),
          "biologicalDetails" -> obj("species" -> str("Canino"), "breed" -> str("Golden")),
          "createdAt" -> str(timestampExample)
        )
      ),
      "httpcode" -> int(201),
      "timestamp" -> str(timestampExample)
    )

  private val patientsListSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Pacientes listados com sucesso."),
      "data" -> obj(
        "patients" -> Json.arr(
          obj(
            "id" -> str("8712345678901238888"),
            "clientId" -> str("8712345678901239999"),
            "name" -> str("Rex"),
            "patientType" -> str("PET"),
            "birthDate" -> str("2022-05-10"),
            "biologicalDetails" -> obj("species" -> str("Canino"), "breed" -> str("Golden")),
            "createdAt" -> str(timestampExample)
          )
        )
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val patientsGetOperation: Json =
    obj(
      "tags" -> Json.arr(str(patientsTag)),
      "summary" -> str("Lista os pacientes vinculados a um tutor."),
      "description" -> str("Retorna a lista de pacientes vinculados ao cliente informado. Requer que o cliente pertença a uma clínica do médico autenticado."),
      "operationId" -> str("patientsList"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("clientId"), "in" -> str("query"), "required" -> bool(true), "schema" -> obj("type" -> str("string")), "description" -> str("ID do cliente/tutor (Snowflake).")),
        obj("name" -> str("limit"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("integer"), "default" -> int(50))),
        obj("name" -> str("offset"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("integer"), "default" -> int(0)))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Pacientes listados com sucesso.", successEnvelope("PatientListResponse"), patientsListSuccessExample),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Cliente não encontrado.", genericErrorExample("Cliente não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val patientsPostOperation: Json =
    obj(
      "tags" -> Json.arr(str(patientsTag)),
      "summary" -> str("Cadastra um novo paciente vinculado a um tutor."),
      "description" -> str("Cadastra o paciente (PET ou HUMAN) com detalhes biológicos livres em JSON."),
      "operationId" -> str("patientsCreate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("PatientRequest")))
      ),
      "responses" -> obj(
        "201" -> jsonResponseWithExample("Paciente cadastrado com sucesso.", successEnvelope("CreatePatientResponse"), createPatientSuccessExample),
        "400" -> validationResponse("Requisição inválida ou campos incorretos.", List("dados-invalidos" -> validationExample(List("name" -> "Nome do paciente é obrigatório.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Cliente não encontrado.", genericErrorExample("Cliente não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val patientsPath: (String, Json) =
    "/api/v1/patients" -> obj("get" -> patientsGetOperation, "post" -> patientsPostOperation)

  private val patientsPutOperation: Json =
    obj(
      "tags" -> Json.arr(str(patientsTag)),
      "summary" -> str("Atualiza os dados de um paciente."),
      "description" -> str("Atualiza as informações do paciente pertencente ao tutor do usuário autenticado."),
      "operationId" -> str("patientsUpdate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do paciente."))
      ),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("UpdatePatientRequest")))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Paciente atualizado com sucesso.", successEnvelope("PatientResponse"), createPatientSuccessExample),
        "400" -> validationResponse("Requisição inválida ou campos incorretos.", List("dados-invalidos" -> validationExample(List("name" -> "Nome do paciente é obrigatório.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Paciente não encontrado.", genericErrorExample("Paciente não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val patientsDeleteOperation: Json =
    obj(
      "tags" -> Json.arr(str(patientsTag)),
      "summary" -> str("Exclui um paciente."),
      "description" -> str("Remove o paciente pertencente ao usuário autenticado caso não haja consultas vinculadas."),
      "operationId" -> str("patientsDelete"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do paciente."))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Paciente excluído com sucesso.", errorEnvelope, obj("erro" -> bool(false), "message" -> str("Paciente excluído com sucesso."), "data" -> Json.Null, "httpcode" -> int(200), "timestamp" -> str(timestampExample))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Paciente não encontrado.", genericErrorExample("Paciente não encontrado.", 404)),
        "409" -> errorResponse("Conflito. Existem consultas vinculadas.", genericErrorExample("Não é possível excluir o paciente pois existem consultas vinculadas.", 409)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val patientsItemPath: (String, Json) =
    "/api/v1/patients/{id}" -> obj("put" -> patientsPutOperation, "delete" -> patientsDeleteOperation)

  // Appointments ----------------------------------------------------------------

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
    obj(
      "erro" -> bool(false),
      "message" -> str("Agendamento criado com sucesso."),
      "data" -> obj("appointment" -> appointmentResponseExample),
      "httpcode" -> int(201),
      "timestamp" -> str(timestampExample)
    )

  private val appointmentsListSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Agendamentos listados com sucesso."),
      "data" -> obj("appointments" -> Json.arr(appointmentResponseExample)),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val updateAppointmentStatusSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Status atualizado com sucesso."),
      "data" -> appointmentResponseExample,
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val appointmentsPostOperation: Json =
    obj(
      "tags" -> Json.arr(str(appointmentsTag)),
      "summary" -> str("Cria um novo agendamento na agenda do médico."),
      "description" -> str(
        """Cria o agendamento com status iniciais SCHEDULED, PENDING e UNPAID.
          |Valida se clinic_id e patient_id pertencem ao médico autenticado, retornando 404 caso não pertençam.""".stripMargin),
      "operationId" -> str("createAppointment"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("CreateAppointmentRequest")))
      ),
      "responses" -> obj(
        "201" -> jsonResponseWithExample("Agendamento criado com sucesso.", successEnvelope("CreateAppointmentResponse"), createAppointmentSuccessExample),
        "400" -> validationResponse("Requisição inválida ou dados incorretos.", List("dados-invalidos" -> validationExample(List("procedure_name" -> "Nome do procedimento é obrigatório.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Clínica ou paciente não encontrados.", genericErrorExample("Clínica ou paciente não encontrados.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val appointmentsGetOperation: Json =
    obj(
      "tags" -> Json.arr(str(appointmentsTag)),
      "summary" -> str("Lista agendamentos do médico autenticado com dados enriquecidos."),
      "description" -> str(
        """Retorna a lista de agendamentos do médico autenticado enriquecida com dados do paciente, tutor e clínica via JOIN.
          |Filtros opcionais: startDate, endDate e clinicId.""".stripMargin),
      "operationId" -> str("listAppointments"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("startDate"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("string"), "format" -> str("date-time")), "description" -> str("Filtro data inicial (ISO 8601).")),
        obj("name" -> str("endDate"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("string"), "format" -> str("date-time")), "description" -> str("Filtro data final (ISO 8601).")),
        obj("name" -> str("clinicId"), "in" -> str("query"), "required" -> bool(false), "schema" -> obj("type" -> str("integer")), "description" -> str("Filtro por ID da clínica."))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Agendamentos listados com sucesso.", successEnvelope("AppointmentListResponse"), appointmentsListSuccessExample),
        "400" -> validationResponse("Parâmetros inválidos.", List("dados-invalidos" -> validationExample(List("startDate" -> "Formato de data inválido: 'invalid'. Formato esperado: ISO 8601.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Clínica não encontrada.", genericErrorExample("Clínica não encontrada.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val appointmentsPath: (String, Json) =
    "/api/v1/appointments" -> obj("get" -> appointmentsGetOperation, "post" -> appointmentsPostOperation)

  private val appointmentsPatchOperation: Json =
    obj(
      "tags" -> Json.arr(str(appointmentsTag)),
      "summary" -> str("Atualiza independentemente os status de exame, laudo ou pagamento de um agendamento."),
      "description" -> str("Permite atualizar exam_status, report_status e payment_status de forma independente."),
      "operationId" -> str("updateAppointmentStatus"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do agendamento (Snowflake)."))
      ),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("UpdateAppointmentStatusRequest")))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Status atualizado com sucesso.", successEnvelope("AppointmentResponse"), updateAppointmentStatusSuccessExample),
        "400" -> validationResponse("Requisição inválida ou nenhum status fornecido.", List("dados-invalidos" -> validationExample(List("status" -> "Ao menos um status deve ser informado para atualização.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Agendamento não encontrado.", genericErrorExample("Agendamento não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val appointmentsStatusPath: (String, Json) =
    "/api/v1/appointments/{id}/status" -> obj("patch" -> appointmentsPatchOperation)

  // Templates Operations & Examples -------------------------------------------

  private val createTemplateSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Template cadastrado com sucesso."),
      "data" -> obj("id" -> str("123456789012345678")),
      "httpcode" -> int(201),
      "timestamp" -> str(timestampExample)
    )

  private val templateSummaryExample: Json =
    obj(
      "id" -> str("123456789012345678"),
      "title" -> str("Ecocardiograma Normal"),
      "createdAt" -> str(timestampExample)
    )

  private val templateListSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Templates listados com sucesso."),
      "data" -> obj("templates" -> Json.arr(templateSummaryExample)),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val templateDetailSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Template obtido com sucesso."),
      "data" -> obj(
        "id" -> str("123456789012345678"),
        "userId" -> str("987654321098765432"),
        "title" -> str("Ecocardiograma Normal"),
        "content" -> str("<p>Estruturas cardíacas dentro dos limites da normalidade.</p>"),
        "createdAt" -> str(timestampExample),
        "updatedAt" -> str(timestampExample)
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val updateTemplateSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Template atualizado com sucesso."),
      "data" -> obj(
        "id" -> str("123456789012345678"),
        "userId" -> str("987654321098765432"),
        "title" -> str("Ecocardiograma Atualizado"),
        "content" -> str("<p>Estruturas cardíacas dentro dos limites da normalidade.</p>"),
        "createdAt" -> str(timestampExample),
        "updatedAt" -> str(timestampExample)
      ),
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val deleteTemplateSuccessExample: Json =
    obj(
      "erro" -> bool(false),
      "message" -> str("Template excluído com sucesso."),
      "data" -> Json.Null,
      "httpcode" -> int(200),
      "timestamp" -> str(timestampExample)
    )

  private val templatesPostOperation: Json =
    obj(
      "tags" -> Json.arr(str(templatesTag)),
      "summary" -> str("Cria um novo template"),
      "description" -> str("Cria um template com título e conteúdo rico/HTML para o utilizador autenticado."),
      "operationId" -> str("createTemplate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("CreateTemplateRequest")))
      ),
      "responses" -> obj(
        "201" -> jsonResponseWithExample("Template cadastrado com sucesso.", successEnvelope("CreateTemplateResponse"), createTemplateSuccessExample),
        "400" -> validationResponse("Dados de requisição inválidos.", List("dados-invalidos" -> validationExample(List("title" -> "Título do template é obrigatório.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val templatesGetOperation: Json =
    obj(
      "tags" -> Json.arr(str(templatesTag)),
      "summary" -> str("Lista os templates do médico"),
      "description" -> str("Retorna os templates pertencentes exclusivamente ao utilizador logado, omitindo o conteúdo rico para otimizar o payload."),
      "operationId" -> str("listTemplates"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Templates listados com sucesso.", successEnvelope("TemplateListResponse"), templateListSuccessExample),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val templatesPath: (String, Json) =
    "/api/v1/templates" -> obj("get" -> templatesGetOperation, "post" -> templatesPostOperation)

  private val templatesItemGetOperation: Json =
    obj(
      "tags" -> Json.arr(str(templatesTag)),
      "summary" -> str("Obtém um template por ID"),
      "description" -> str("Retorna o template completo incluindo o conteúdo rico/HTML caso pertença ao médico autenticado."),
      "operationId" -> str("getTemplateById"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do template (Snowflake)."))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Template obtido com sucesso.", successEnvelope("TemplateDetailResponse"), templateDetailSuccessExample),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Template não encontrado.", genericErrorExample("Template não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val templatesItemPutOperation: Json =
    obj(
      "tags" -> Json.arr(str(templatesTag)),
      "summary" -> str("Atualiza um template existente"),
      "description" -> str("Atualiza título e/ou conteúdo do template garantindo que pertença ao médico autenticado."),
      "operationId" -> str("updateTemplate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do template (Snowflake)."))
      ),
      "requestBody" -> obj(
        "required" -> bool(true),
        "content" -> obj("application/json" -> obj("schema" -> ref("UpdateTemplateRequest")))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Template atualizado com sucesso.", successEnvelope("TemplateDetailResponse"), updateTemplateSuccessExample),
        "400" -> validationResponse("Dados de requisição inválidos.", List("dados-invalidos" -> validationExample(List("body" -> "Pelo menos um campo ('title' ou 'content') deve ser fornecido para atualização.")))),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Template não encontrado.", genericErrorExample("Template não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val templatesItemDeleteOperation: Json =
    obj(
      "tags" -> Json.arr(str(templatesTag)),
      "summary" -> str("Exclui um template"),
      "description" -> str("Remove o template caso pertença ao médico autenticado."),
      "operationId" -> str("deleteTemplate"),
      "security" -> Json.arr(obj("accessTokenCookie" -> Json.arr())),
      "parameters" -> Json.arr(
        obj("name" -> str("id"), "in" -> str("path"), "required" -> bool(true), "schema" -> obj("type" -> str("integer")), "description" -> str("ID do template (Snowflake)."))
      ),
      "responses" -> obj(
        "200" -> jsonResponseWithExample("Template excluído com sucesso.", obj("erro" -> bool(false), "message" -> str("Template excluído com sucesso."), "httpcode" -> int(200), "timestamp" -> dateTimeField), deleteTemplateSuccessExample),
        "401" -> errorResponse("Não autenticado.", genericErrorExample("Não autenticado.", 401)),
        "404" -> errorResponse("Template não encontrado.", genericErrorExample("Template não encontrado.", 404)),
        "500" -> errorResponse("Erro interno do servidor.", genericErrorExample("Erro interno do servidor. Tente novamente.", 500))
      )
    )

  private val templatesItemPath: (String, Json) =
    "/api/v1/templates/{id}" -> obj("get" -> templatesItemGetOperation, "put" -> templatesItemPutOperation, "delete" -> templatesItemDeleteOperation)

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
      ),
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
        "name" -> obj("type" -> str("string"), "minLength" -> int(2), "maxLength" -> int(255), "description" -> str("Nome da clínica (obrigatório, mínimo 2, máximo 255 caracteres).")),
        "cnpj" -> obj("type" -> str("string"), "nullable" -> bool(true), "pattern" -> str("^\\d{14}$"), "description" -> str("CNPJ opcional. Exatamente 14 dígitos com dígitos verificadores válidos (Módulo 11); normalizado para apenas dígitos.")),
        "phone" -> obj("type" -> str("string"), "nullable" -> bool(true), "pattern" -> str("^\\d{10,11}$"), "description" -> str("Telefone opcional com 10 ou 11 dígitos (DDD + número), normalizado para dígitos puros.")),
        "address" -> obj("type" -> str("string"), "nullable" -> bool(true), "maxLength" -> int(500), "description" -> str("Endereço opcional, máximo de 500 caracteres."))
      ),
      "ClinicListResponse" -> requiredObject(
        List("clinics"),
        "clinics" -> arrayOf(ref("ClinicData"))
      ),
      "CreateClinicResponse" -> requiredObject(
        List("clinic"),
        "clinic" -> ref("ClinicData")
      ),
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
      ),
      "PatientRequest" -> requiredObject(
        List("client_id", "name", "patient_type", "birth_date", "biological_details"),
        "client_id" -> obj("type" -> str("integer"), "description" -> str("ID do tutor/cliente (Snowflake).")),
        "name" -> obj("type" -> str("string"), "description" -> str("Nome do paciente.")),
        "patient_type" -> obj("type" -> str("string"), "enum" -> Json.arr(str("PET"), str("HUMAN")), "description" -> str("Tipo de paciente (PET ou HUMAN).")),
        "birth_date" -> obj("type" -> str("string"), "format" -> str("date"), "description" -> str("Data de nascimento (YYYY-MM-DD).")),
        "biological_details" -> obj("type" -> str("object"), "description" -> str("Detalhes biológicos livres em formato JSON."))
      ),
      "PatientResponse" -> requiredObject(
        List("id", "name", "patientType", "biologicalDetails", "createdAt"),
        "id" -> obj("type" -> str("string"), "description" -> str("ID do paciente (Snowflake).")),
        "clientId" -> stringNullableField,
        "name" -> stringField,
        "patientType" -> obj("type" -> str("string"), "enum" -> Json.arr(str("PET"), str("HUMAN"))),
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
        "patient_type" -> obj("type" -> str("string"), "enum" -> Json.arr(str("PET"), str("HUMAN")), "description" -> str("Tipo de paciente (PET ou HUMAN).")),
        "birth_date" -> obj("type" -> str("string"), "format" -> str("date"), "nullable" -> bool(true), "description" -> str("Data de nascimento (YYYY-MM-DD).")),
        "biological_details" -> obj("type" -> str("object"), "description" -> str("Detalhes biológicos livres em formato JSON (raça, pelagem, idade, etc.)."))
      ),
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
        List("id", "userId", "clinicId", "patientId", "scheduledAt", "procedureName", "examStatus", "reportStatus", "paymentStatus", "createdAt", "updatedAt", "patientName", "patientType", "clientName", "clinicName"),
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
      ),
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
        obj("name" -> str(authTag), "description" -> str("Autenticação, registro e renovação de sessão.")),
        obj("name" -> str(clinicsTag), "description" -> str("Clínicas (locais de atendimento) do usuário autenticado.")),
        obj("name" -> str(clientsTag), "description" -> str("Tutores e clientes vinculados a clínicas.")),
        obj("name" -> str(patientsTag), "description" -> str("Pacientes (PET ou HUMAN) vinculados a tutores.")),
        obj("name" -> str(appointmentsTag), "description" -> str("Agendamento e controle financeiro de procedimentos.")),
        obj("name" -> str(templatesTag), "description" -> str("Templates de laudos clínicos do médico."))
      ),
      "paths" -> obj(
        loginPath, refreshPath, registerPath, mePath, logoutPath, clinicsPath,
        clientsPath, clientsItemPath, patientsPath, patientsItemPath,
        appointmentsPath, appointmentsStatusPath,
        templatesPath, templatesItemPath
      ),
      "components" -> obj(
        "securitySchemes" -> securitySchemes,
        "schemas" -> schemas
      )
    )

  def json: Json = document
