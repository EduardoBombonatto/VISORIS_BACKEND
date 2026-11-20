package com.visoris.backend.appointments.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.appointments.dto.*
import com.visoris.backend.appointments.service.{AppointmentService, AppointmentsError}
import com.visoris.backend.iam.domain.User
import com.visoris.backend.shared.dto.ApiResponse
import io.circe.syntax.*
import java.time.Instant
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import scala.util.Try

object AppointmentController:

  private def errorResponse(errors: List[ValidationError]): ApiResponse[Map[String, List[Map[String, String]]]] =
    val data = Map("errors" -> errors.map(e => Map("field" -> e.field, "message" -> e.message)))
    ApiResponse(
      erro = true,
      message = "Dados inválidos.",
      data = Some(data),
      httpcode = 400,
      timestamp = Instant.now
    )

  private def jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

  def routes[F[_]: Async](service: AppointmentService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object OptionalStartDateQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("startDate")
    object OptionalEndDateQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("endDate")
    object OptionalClinicIdQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("clinicId")

    def parseOptionalInstant(field: String, raw: Option[String]): Either[ValidationError, Option[Instant]] =
      raw match
        case None => Right(None)
        case Some(v) =>
          Try(Instant.parse(v.trim)).toEither
            .left.map(_ => ValidationError(field, s"Formato de data inválido para '$field'. Formato esperado: ISO 8601."))
            .flatMap { ts =>
              if ts.isBefore(AppointmentService.MinScheduledAt) || ts.isAfter(AppointmentService.MaxScheduledAt) then
                Left(ValidationError(field, s"Data fora do intervalo permitido para '$field' (1900 a 2100)."))
              else Right(Some(ts))
            }

    AuthedRoutes.of[User, F] {
      case req @ POST -> Root / "api" / "v1" / "appointments" as user =>
        req.req.as[CreateAppointmentRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(createReq) =>
            service.create(createReq, user.id).flatMap {
              case Left(AppointmentsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(AppointmentsError.NotFound) =>
                NotFound(ApiResponse.error("Clínica ou paciente não encontrados.", 404).asJson)
                  .map(_.withContentType(jsonContent))
              case Left(AppointmentsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(resp) =>
                Created(ApiResponse.success("Agendamento criado com sucesso.", CreateAppointmentResponse(resp), 201).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case GET -> Root / "api" / "v1" / "appointments" :?
          OptionalStartDateQueryParamMatcher(rawStartDate) +&
          OptionalEndDateQueryParamMatcher(rawEndDate) +&
          OptionalClinicIdQueryParamMatcher(rawClinicId) as user =>
        val startInstantEither = parseOptionalInstant("startDate", rawStartDate)
        val endInstantEither = parseOptionalInstant("endDate", rawEndDate)
        val clinicIdEither = rawClinicId match
          case None => Right(None)
          case Some(v) =>
            v.trim.toLongOption
              .toRight(ValidationError("clinicId", "Parâmetro 'clinicId' deve ser um número inteiro válido."))
              .map(Some(_))

        val errors = List(startInstantEither.left.toOption, endInstantEither.left.toOption, clinicIdEither.left.toOption).flatten
        if errors.nonEmpty then
          BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
        else
          service.listFiltered(user.id, startInstantEither.toOption.flatten, endInstantEither.toOption.flatten, clinicIdEither.toOption.flatten).flatMap {
            case Left(AppointmentsError.Validation(validationErrors)) =>
              BadRequest(errorResponse(validationErrors).asJson).map(_.withContentType(jsonContent))
            case Left(AppointmentsError.NotFound) =>
              NotFound(ApiResponse.error("Clínica não encontrada.", 404).asJson).map(_.withContentType(jsonContent))
            case Left(AppointmentsError.Internal(msg)) =>
              InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
            case Right(list) =>
              Ok(ApiResponse.success("Agendamentos listados com sucesso.", AppointmentListResponse(list), 200).asJson)
                .map(_.withContentType(jsonContent))
          }

      case req @ PATCH -> Root / "api" / "v1" / "appointments" / LongVar(id) / "status" as user =>
        req.req.as[UpdateAppointmentStatusRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique os status fornecidos.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(updateReq) =>
            service.updateStatus(id, user.id, updateReq.examStatus, updateReq.reportStatus, updateReq.paymentStatus).flatMap {
              case Left(AppointmentsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(AppointmentsError.NotFound) =>
                NotFound(ApiResponse.error("Agendamento não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
              case Left(AppointmentsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(resp) =>
                Ok(ApiResponse.success("Status atualizado com sucesso.", resp, 200).asJson).map(_.withContentType(jsonContent))
            }
        }
    }
