package com.visoris.backend.templates.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.shared.http.HttpResponses
import com.visoris.backend.templates.domain.{Template, TemplateSummary}
import com.visoris.backend.templates.dto.*
import com.visoris.backend.templates.service.{TemplateService, TemplatesError}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object TemplateController:

  private def toSummaryResponse(s: TemplateSummary): TemplateSummaryResponse =
    TemplateSummaryResponse(id = s.id.toString, title = s.title, createdAt = s.createdAt)

  private def toDetailResponse(t: Template): TemplateDetailResponse =
    TemplateDetailResponse(
      id = t.id.toString,
      userId = t.userId.toString,
      title = t.title,
      content = t.content,
      createdAt = t.createdAt,
      updatedAt = t.updatedAt
    )

  private def mapError[F[_]: Async](err: TemplatesError, notFoundMsg: String): F[Response[F]] =
    err match
      case TemplatesError.Validation(errors) =>
        HttpResponses.validationError(errors.map(e => (e.field, e.message)))
      case TemplatesError.NotFound =>
        HttpResponses.notFound(notFoundMsg)
      case TemplatesError.Internal(msg) =>
        HttpResponses.internalError(msg)

  def routes[F[_]: Async](service: TemplateService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    AuthedRoutes.of[User, F] {
      case req @ POST -> Root / "api" / "v1" / "templates" as user =>
        req.req.as[CreateTemplateRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(createReq) =>
            service.create(createReq, user.id).flatMap {
              case Left(err)       => mapError(err, "Usuário não encontrado.")
              case Right(template) => HttpResponses.created("Template cadastrado com sucesso.", CreateTemplateResponse(template.id.toString))
            }
        }

      case GET -> Root / "api" / "v1" / "templates" as user =>
        service.listByUser(user.id).flatMap {
          case Left(err)        => mapError(err, "Usuário não encontrado.")
          case Right(templates) => HttpResponses.ok("Templates listados com sucesso.", TemplateListResponse(templates.map(toSummaryResponse)))
        }

      case GET -> Root / "api" / "v1" / "templates" / LongVar(templateId) as user =>
        service.findById(templateId, user.id).flatMap {
          case Left(err)       => mapError(err, "Template não encontrado.")
          case Right(template) => HttpResponses.ok("Template obtido com sucesso.", toDetailResponse(template))
        }

      case req @ PUT -> Root / "api" / "v1" / "templates" / LongVar(templateId) as user =>
        req.req.as[UpdateTemplateRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(updateReq) =>
            service.update(templateId, updateReq, user.id).flatMap {
              case Left(err)       => mapError(err, "Template não encontrado.")
              case Right(template) => HttpResponses.ok("Template atualizado com sucesso.", toDetailResponse(template))
            }
        }

      case DELETE -> Root / "api" / "v1" / "templates" / LongVar(templateId) as user =>
        service.delete(templateId, user.id).flatMap {
          case Left(err) => mapError(err, "Template não encontrado.")
          case Right(_)  => HttpResponses.ok("Template excluído com sucesso.", Option.empty[String])
        }
    }
