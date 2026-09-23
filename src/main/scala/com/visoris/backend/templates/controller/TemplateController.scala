package com.visoris.backend.templates.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.shared.dto.ApiResponse
import com.visoris.backend.templates.domain.{Template, TemplateSummary}
import com.visoris.backend.templates.dto.*
import com.visoris.backend.templates.service.{TemplateService, TemplatesError}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object TemplateController:

  private def jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

  private def errorResponse(errors: List[ValidationError]): ApiResponse[Map[String, List[Map[String, String]]]] =
    val data = Map("errors" -> errors.map(e => Map("field" -> e.field, "message" -> e.message)))
    ApiResponse(
      erro = true,
      message = "Dados inválidos.",
      data = Some(data),
      httpcode = 400,
      timestamp = java.time.Instant.now
    )

  private def toSummaryResponse(s: TemplateSummary): TemplateSummaryResponse =
    TemplateSummaryResponse(
      id = s.id.toString,
      title = s.title,
      createdAt = s.createdAt
    )

  private def toDetailResponse(t: Template): TemplateDetailResponse =
    TemplateDetailResponse(
      id = t.id.toString,
      userId = t.userId.toString,
      title = t.title,
      content = t.content,
      createdAt = t.createdAt,
      updatedAt = t.updatedAt
    )

  def routes[F[_]: Async](service: TemplateService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    AuthedRoutes.of[User, F] {
      case req @ POST -> Root / "api" / "v1" / "templates" as user =>
        req.req.as[CreateTemplateRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(createReq) =>
            service.create(createReq, user.id).flatMap {
              case Left(TemplatesError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(TemplatesError.NotFound) =>
                NotFound(ApiResponse.error("Usuário não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
              case Left(TemplatesError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(template) =>
                val response = CreateTemplateResponse(template.id.toString)
                Created(ApiResponse.success("Template cadastrado com sucesso.", response, 201).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case GET -> Root / "api" / "v1" / "templates" as user =>
        service.listByUser(user.id).flatMap {
          case Left(TemplatesError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(templates) =>
            val response = TemplateListResponse(templates.map(toSummaryResponse))
            Ok(ApiResponse.success("Templates listados com sucesso.", response, 200).asJson)
              .map(_.withContentType(jsonContent))
        }

      case GET -> Root / "api" / "v1" / "templates" / LongVar(templateId) as user =>
        service.findById(templateId, user.id).flatMap {
          case Left(TemplatesError.NotFound) =>
            NotFound(ApiResponse.error("Template não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
          case Left(TemplatesError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(template) =>
            Ok(ApiResponse.success("Template obtido com sucesso.", toDetailResponse(template), 200).asJson)
              .map(_.withContentType(jsonContent))
        }

      case req @ PUT -> Root / "api" / "v1" / "templates" / LongVar(templateId) as user =>
        req.req.as[UpdateTemplateRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(updateReq) =>
            service.update(templateId, updateReq, user.id).flatMap {
              case Left(TemplatesError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(TemplatesError.NotFound) =>
                NotFound(ApiResponse.error("Template não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
              case Left(TemplatesError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(template) =>
                Ok(ApiResponse.success("Template atualizado com sucesso.", toDetailResponse(template), 200).asJson)
              .map(_.withContentType(jsonContent))
            }
        }

      case DELETE -> Root / "api" / "v1" / "templates" / LongVar(templateId) as user =>
        service.delete(templateId, user.id).flatMap {
          case Left(TemplatesError.NotFound) =>
            NotFound(ApiResponse.error("Template não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
          case Left(TemplatesError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(_) =>
            Ok(ApiResponse.success("Template excluído com sucesso.", Option.empty[String], 200).asJson)
              .map(_.withContentType(jsonContent))
        }
    }
