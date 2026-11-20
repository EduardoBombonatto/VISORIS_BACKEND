package com.visoris.backend.docs.specs

import OpenApiDsl.*
import io.circe.Json

object AuthOpenApiSpec extends DomainOpenApiSpec:

  val tagName: String = "Auth"
  val tagDescription: String = "Autenticação, registro e renovação de sessão."

  private val sampleUser: Json =
    obj(
      "id" -> str("8712345678901234567"),
      "fullName" -> str("Dra. Maria Souza"),
      "professionalDocument" -> str("CRM/SP 123456")
    )

  private val loginSuccessExample: Json =
    successExample("Autenticado com sucesso.", obj("user" -> sampleUser))

  private val registerSuccessExample: Json =
    successExample("Conta criada com sucesso.", obj("user" -> sampleUser), httpcode = 201)

  private val meSuccessExample: Json =
    successExample("Sessão restaurada com sucesso.", obj("user" -> sampleUser))

  private val refreshSuccessExample: Json =
    successExample("Sessão renovada com sucesso.", obj("expiresIn" -> int(900)))

  private val logoutSuccessExample: Json =
    successExample("Logout realizado com sucesso.", Json.Null)

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

  private val loginPath: (String, Json) =
    "/api/v1/auth/login" -> obj(
      "post" -> operation(
        tag = tagName,
        summary = "Autentica um usuário e inicia a sessão.",
        description =
          """Valida as credenciais do usuário. Em caso de sucesso retorna os dados do usuário
            |(ID, nome completo e documento profissional) e define os cookies `accessToken`
            |(900s) e `refreshToken` (7 dias).
            |
            |Falhas de autenticação retornam sempre a mesma mensagem genérica
            |"Credenciais inválidas." para não revelar quais credenciais estão incorretas.""".stripMargin,
        operationId = "authLogin",
        security = None,
        requestBody = Some("LoginRequest"),
        responses = List(
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
      "post" -> operation(
        tag = tagName,
        summary = "Renova a sessão usando o refresh token (cookie).",
        description =
          """Rotaciona o `refreshToken` recebido via cookie e emite um novo `accessToken` + novo `refreshToken`.
            |O refresh token antigo é revogado imediatamente (single-use). Não possui corpo de requisição.""".stripMargin,
        operationId = "authRefresh",
        security = Some(refreshTokenSecurity),
        responses = List(
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
          internalErrorResponse
        )
      )
    )

  private val registerPath: (String, Json) =
    "/api/v1/auth/register" -> obj(
      "post" -> operation(
        tag = tagName,
        summary = "Cria uma conta de usuário (DOCTOR) e inicia a sessão.",
        description =
          """Cria a conta e já emite os cookies `accessToken` (900s) e `refreshToken` (7 dias).
            |
            |Regras de validação:
            |- `fullName`, `email`, `password` e `professionalDocument` são obrigatórios (o documento pode vir `null`, mas não vazio).
            |- `email` deve ter formato válido e não ser de domínio descartável.
            |- `password` deve ter no mínimo 8 caracteres, conter maiúscula, minúscula, número e caractere especial, e apenas caracteres ASCII.
            |- Limites: `fullName` e `email` até 255 caracteres; `professionalDocument` até 50.""".stripMargin,
        operationId = "authRegister",
        security = None,
        requestBody = Some("RegisterRequest"),
        responses = List(
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
          conflictResponse("Conflito. O e-mail informado já está cadastrado.", "Este e-mail já está cadastrado."),
          internalErrorResponse
        )
      )
    )

  private val mePath: (String, Json) =
    "/api/v1/auth/me" -> obj(
      "get" -> operation(
        tag = tagName,
        summary = "Restaura a sessão do usuário autenticado.",
        description =
          """Retorna o usuário autenticado a partir dos cookies.
            |Se o `accessToken` for válido, a sessão é retornada diretamente. Caso contrário,
            |tenta rotacionar a sessão usando o `refreshToken` (emitindo novos cookies `accessToken`
            |e `refreshToken`) antes de retornar os dados.""".stripMargin,
        operationId = "authMe",
        security = Some(Json.arr(obj("accessTokenCookie" -> Json.arr()), obj("refreshTokenCookie" -> Json.arr()))),
        responses = List(
          "200" -> jsonResponseWithCookies(
            "Sessão restaurada com sucesso. Retorna o usuário. Quando a sessão é renovada, define os cookies `accessToken` e novo `refreshToken`.",
            successEnvelope("LoginResponse"),
            meSuccessExample,
            refreshedSessionCookies
          ),
          "401" -> errorResponses(
            """Não autenticado. Retornado quando não há `accessToken` válido e o `refreshToken`
              |está ausente, expirado, desconhecido ou revogado.""".stripMargin,
            List("nao-autenticado" -> genericErrorExample("Não autenticado.", 401))
          ),
          internalErrorResponse
        )
      )
    )

  private val logoutPath: (String, Json) =
    "/api/v1/auth/logout" -> obj(
      "post" -> operation(
        tag = tagName,
        summary = "Encerra a sessão atual e limpa os cookies de sessão.",
        description =
          """Revoga o `refreshToken` recebido via cookie e limpa os cookies `accessToken` e `refreshToken`
            |(Max-Age=0). Não possui corpo de requisição e é idempotente: cookies ausentes,
            |desconhecidos ou já revogados ainda retornam 200.""".stripMargin,
        operationId = "authLogout",
        security = Some(refreshTokenSecurity),
        responses = List(
          "200" -> jsonResponseWithCookies(
            "Sessão encerrada. Limpa os cookies `accessToken` e `refreshToken` (Max-Age=0).",
            errorEnvelope,
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

  val paths: List[(String, Json)] =
    List(loginPath, refreshPath, registerPath, mePath, logoutPath)

  val schemas: List[(String, Json)] = List(
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
      "expiresIn" -> obj(
        "type" -> str("integer"),
        "format" -> str("int32"),
        "description" -> str("Validade do access token em segundos.")
      )
    )
  )
