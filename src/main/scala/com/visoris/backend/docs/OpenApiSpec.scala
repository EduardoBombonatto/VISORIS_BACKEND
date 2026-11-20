package com.visoris.backend.docs

import com.visoris.backend.docs.specs.*
import com.visoris.backend.docs.specs.OpenApiDsl.*
import io.circe.Json

object OpenApiSpec:

  private val domains: List[DomainOpenApiSpec] = List(
    AuthOpenApiSpec,
    ClinicsOpenApiSpec,
    ClientsOpenApiSpec,
    PatientsOpenApiSpec,
    AppointmentsOpenApiSpec,
    TemplatesOpenApiSpec,
    ReportsOpenApiSpec
  )

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

  private val sharedSchemas: List[(String, Json)] = List(
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
        domains.map(d => obj("name" -> str(d.tagName), "description" -> str(d.tagDescription)))*
      ),
      "paths" -> obj(domains.flatMap(_.paths)*),
      "components" -> obj(
        "securitySchemes" -> securitySchemes,
        "schemas" -> obj((sharedSchemas ++ domains.flatMap(_.schemas))*)
      )
    )

  def json: Json = document
