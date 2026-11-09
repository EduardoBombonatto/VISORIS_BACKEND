package com.visoris.backend.reports.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.reports.dto.*
import com.visoris.backend.reports.service.{ReportService, ReportsError}
import com.visoris.backend.shared.dto.ApiResponse
import io.circe.syntax.*
import java.time.Instant
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object ReportController:

  private def jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

  private def errorResponse(errors: List[ValidationError]): ApiResponse[Map[String, List[Map[String, String]]]] =
    val data = Map("errors" -> errors.map(e => Map("field" -> e.field, "message" -> e.message)))
    ApiResponse(
      erro = true,
      message = "Dados inválidos.",
      data = Some(data),
      httpcode = 400,
      timestamp = Instant.now
    )

  def routes[F[_]: Async](service: ReportService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "reports" / "appointment" / LongVar(appointmentId) as user =>
        service.getByAppointmentId(appointmentId, user.id).flatMap {
          case Left(ReportsError.NotFound) =>
            NotFound(ApiResponse.error("Laudo não encontrado para este agendamento.", 404).asJson)
              .map(_.withContentType(jsonContent))
          case Left(ReportsError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(reportWithImages) =>
            Ok(ApiResponse.success("Laudo obtido com sucesso.", ReportResponse.fromDomain(reportWithImages), 200).asJson)
              .map(_.withContentType(jsonContent))
        }

      case req @ POST -> Root / "api" / "v1" / "reports" as user =>
        req.req.as[CreateReportRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(createReq) =>
            service.createDraft(createReq, user.id).flatMap {
              case Left(ReportsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(ReportsError.NotFound) =>
                NotFound(ApiResponse.error("Agendamento não encontrado.", 404).asJson)
                  .map(_.withContentType(jsonContent))
              case Left(ReportsError.Conflict(msg)) =>
                Conflict(ApiResponse.error(msg, 409).asJson).map(_.withContentType(jsonContent))
              case Left(ReportsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(reportWithImages) =>
                Created(ApiResponse.success("Laudo rascunho criado com sucesso.", ReportResponse.fromDomain(reportWithImages), 201).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case req @ PUT -> Root / "api" / "v1" / "reports" / LongVar(reportId) as user =>
        req.req.as[UpdateReportRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(updateReq) =>
            service.updateContent(reportId, updateReq, user.id).flatMap {
              case Left(ReportsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(ReportsError.NotFound) =>
                NotFound(ApiResponse.error("Laudo não encontrado.", 404).asJson)
                  .map(_.withContentType(jsonContent))
              case Left(ReportsError.Conflict(msg)) =>
                Conflict(ApiResponse.error(msg, 409).asJson).map(_.withContentType(jsonContent))
              case Left(ReportsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(reportWithImages) =>
                Ok(ApiResponse.success("Laudo atualizado com sucesso.", ReportResponse.fromDomain(reportWithImages), 200).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case req @ POST -> Root / "api" / "v1" / "reports" / LongVar(reportId) / "images" as user =>
        req.req.as[ReportImageRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(imageReq) =>
            service.addImage(reportId, imageReq, user.id).flatMap {
              case Left(ReportsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(ReportsError.NotFound) =>
                NotFound(ApiResponse.error("Laudo não encontrado.", 404).asJson)
                  .map(_.withContentType(jsonContent))
              case Left(ReportsError.Conflict(msg)) =>
                Conflict(ApiResponse.error(msg, 409).asJson).map(_.withContentType(jsonContent))
              case Left(ReportsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(image) =>
                Created(ApiResponse.success("Imagem adicionada ao laudo com sucesso.", ReportImageResponse.fromDomain(image), 201).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case POST -> Root / "api" / "v1" / "reports" / LongVar(reportId) / "sign" as user =>
        service.signReport(reportId, user.id).flatMap {
          case Left(ReportsError.NotFound) =>
            NotFound(ApiResponse.error("Laudo não encontrado.", 404).asJson)
              .map(_.withContentType(jsonContent))
          case Left(ReportsError.Conflict(msg)) =>
            Conflict(ApiResponse.error(msg, 409).asJson).map(_.withContentType(jsonContent))
          case Left(ReportsError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(reportWithImages) =>
            Ok(ApiResponse.success("Laudo assinado e finalizado com sucesso.", ReportResponse.fromDomain(reportWithImages), 200).asJson)
              .map(_.withContentType(jsonContent))
        }
    }
