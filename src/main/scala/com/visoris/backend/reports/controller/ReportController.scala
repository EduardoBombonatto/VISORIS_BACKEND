package com.visoris.backend.reports.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.reports.dto.*
import com.visoris.backend.reports.service.{ReportService, ReportsError}
import com.visoris.backend.shared.http.HttpResponses
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object ReportController:

  private def mapError[F[_]: Async](err: ReportsError, notFoundMsg: String): F[Response[F]] =
    err match
      case ReportsError.Validation(errors) =>
        HttpResponses.validationError(errors.map(e => (e.field, e.message)))
      case ReportsError.NotFound =>
        HttpResponses.notFound(notFoundMsg)
      case ReportsError.Conflict(msg) =>
        HttpResponses.conflict(msg)
      case ReportsError.Internal(msg) =>
        HttpResponses.internalError(msg)

  def routes[F[_]: Async](service: ReportService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "reports" / "appointment" / LongVar(appointmentId) as user =>
        service.getByAppointmentId(appointmentId, user.id).flatMap {
          case Left(err)               => mapError(err, "Laudo não encontrado para este agendamento.")
          case Right(reportWithImages) => HttpResponses.ok("Laudo obtido com sucesso.", ReportResponse.fromDomain(reportWithImages))
        }

      case req @ POST -> Root / "api" / "v1" / "reports" as user =>
        req.req.as[CreateReportRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(createReq) =>
            service.createDraft(createReq, user.id).flatMap {
              case Left(err)               => mapError(err, "Agendamento não encontrado.")
              case Right(reportWithImages) => HttpResponses.created("Laudo rascunho criado com sucesso.", ReportResponse.fromDomain(reportWithImages))
            }
        }

      case req @ PUT -> Root / "api" / "v1" / "reports" / LongVar(reportId) as user =>
        req.req.as[UpdateReportRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(updateReq) =>
            service.updateContent(reportId, updateReq, user.id).flatMap {
              case Left(err)               => mapError(err, "Laudo não encontrado.")
              case Right(reportWithImages) => HttpResponses.ok("Laudo atualizado com sucesso.", ReportResponse.fromDomain(reportWithImages))
            }
        }

      case req @ POST -> Root / "api" / "v1" / "reports" / LongVar(reportId) / "images" as user =>
        req.req.as[ReportImageRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(imageReq) =>
            service.addImage(reportId, imageReq, user.id).flatMap {
              case Left(err)    => mapError(err, "Laudo não encontrado.")
              case Right(image) => HttpResponses.created("Imagem adicionada ao laudo com sucesso.", ReportImageResponse.fromDomain(image))
            }
        }

      case POST -> Root / "api" / "v1" / "reports" / LongVar(reportId) / "sign" as user =>
        service.signReport(reportId, user.id).flatMap {
          case Left(err)               => mapError(err, "Laudo não encontrado.")
          case Right(reportWithImages) => HttpResponses.ok("Laudo assinado e finalizado com sucesso.", ReportResponse.fromDomain(reportWithImages))
        }
    }
