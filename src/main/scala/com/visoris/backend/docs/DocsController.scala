package com.visoris.backend.docs

import cats.effect.Async
import cats.syntax.all.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Cache-Control`
import org.http4s.headers.`Content-Type`

object DocsController:

  private val swaggerUiBase = "META-INF/resources/webjars/swagger-ui/5.32.13"

  private val htmlShell: String =
    """<!DOCTYPE html>
      |<html lang="pt-BR">
      |<head>
      |  <meta charset="utf-8">
      |  <meta name="viewport" content="width=device-width, initial-scale=1">
      |  <title>Visoris API — Documentação</title>
      |  <link rel="stylesheet" href="/api/v1/docs/swagger-ui/swagger-ui.css">
      |  <style>
      |    html { box-sizing: border-box; overflow-y: scroll; }
      |    *, *:before, *:after { box-sizing: inherit; }
      |    body { margin: 0; background: #fafafa; }
      |  </style>
      |</head>
      |<body>
      |  <div id="swagger-ui"></div>
      |  <script src="/api/v1/docs/swagger-ui/swagger-ui-bundle.js"></script>
      |  <script src="/api/v1/docs/swagger-ui/swagger-ui-standalone-preset.js"></script>
      |  <script>
      |    window.onload = function () {
      |      window.ui = SwaggerUIBundle({
      |        url: "/api/v1/docs/openapi.json",
      |        dom_id: "#swagger-ui",
      |        deepLinking: true,
      |        presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
      |        layout: "StandaloneLayout"
      |      });
      |    };
      |  </script>
      |</body>
      |</html>""".stripMargin

  private def htmlContent: `Content-Type` = `Content-Type`(MediaType.text.html, org.http4s.Charset.`UTF-8`)
  private def jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

  private def noStore: `Cache-Control` = `Cache-Control`(cats.data.NonEmptyList.one(CacheDirective.`no-store`))

  def routes[F[_]: Async]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case GET -> Root / "api" / "v1" / "docs" =>
        Ok(htmlShell).map(_.withContentType(htmlContent).putHeaders(noStore))

      case GET -> Root / "api" / "v1" / "docs" / "" =>
        Ok(htmlShell).map(_.withContentType(htmlContent).putHeaders(noStore))

      case GET -> Root / "api" / "v1" / "docs" / "openapi.json" =>
        Ok(OpenApiSpec.json.noSpaces).map(_.withContentType(jsonContent).putHeaders(noStore))

      case req @ GET -> Root / "api" / "v1" / "docs" / "swagger-ui" / path =>
        StaticFile
          .fromResource[F](s"$swaggerUiBase/$path", Some(req))
          .map(_.putHeaders(noStore))
          .getOrElseF(NotFound())
    }
