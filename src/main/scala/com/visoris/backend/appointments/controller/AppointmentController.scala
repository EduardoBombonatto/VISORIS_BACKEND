package com.visoris.backend.appointments.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.appointments.dto.*
import com.visoris.backend.appointments.service.{AppointmentService, AppointmentsError}
import com.visoris.backend.iam.domain.User
import com.visoris.backend.shared.http.HttpResponses
import java.time.Instant
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import scala.util.Try

object AppointmentController:

  private def mapError[F[_]: Async](err: AppointmentsError, notFoundMsg: String): F[Response[F]] =
    err match
      case AppointmentsError.Validation(errors) =>
        HttpResponses.validationError(errors.map(e => (e.field, e.message)))
      case AppointmentsError.NotFound =>
        HttpResponses.notFound(notFoundMsg)
      case AppointmentsError.Internal(msg) =>
        HttpResponses.internalError(msg)

  private def parseOptionalInstant(field: String, raw: Option[String]): Either[(String, String), Option[Instant]] =
    raw match
      case None => Right(None)
      case Some(v) =>
        Try(Instant.parse(v.trim)).toEither
          .left.map(_ => (field, s"Formato de data inválido para '$field'. Formato esperado: ISO 8601."))
          .flatMap { ts =>
            if ts.isBefore(AppointmentService.MinScheduledAt) || ts.isAfter(AppointmentService.MaxScheduledAt) then
              Left((field, s"Data fora do intervalo permitido para '$field' (1900 a 2100)."))
            else Right(Some(ts))
          }

  def routes[F[_]: Async](service: AppointmentService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object OptionalStartDateQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("startDate")
    object OptionalEndDateQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("endDate")
    object OptionalClinicIdQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("clinicId")

    AuthedRoutes.of[User, F] {
      case req @ POST -> Root / "api" / "v1" / "appointments" as user =>
        req.req.as[CreateAppointmentRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(createReq) =>
            service.create(createReq, user.id).flatMap {
              case Left(err)   => mapError(err, "Clínica ou paciente não encontrados.")
              case Right(resp) => HttpResponses.created("Agendamento criado com sucesso.", CreateAppointmentResponse(resp))
            }
        }

      case GET -> Root / "api" / "v1" / "appointments" :?
          OptionalStartDateQueryParamMatcher(rawStartDate) +&
          OptionalEndDateQueryParamMatcher(rawEndDate) +&
          OptionalClinicIdQueryParamMatcher(rawClinicId) as user =>
        val startEither = parseOptionalInstant("startDate", rawStartDate)
        val endEither = parseOptionalInstant("endDate", rawEndDate)
        val clinicEither = HttpResponses.parseOptionalLong("clinicId", rawClinicId)
        val errors = List(startEither.left.toOption, endEither.left.toOption, clinicEither.left.toOption).flatten
        if errors.nonEmpty then HttpResponses.validationError(errors)
        else
          service.listFiltered(user.id, startEither.toOption.flatten, endEither.toOption.flatten, clinicEither.toOption.flatten).flatMap {
            case Left(err)   => mapError(err, "Clínica não encontrada.")
            case Right(list) => HttpResponses.ok("Agendamentos listados com sucesso.", AppointmentListResponse(list))
          }

      case req @ PATCH -> Root / "api" / "v1" / "appointments" / LongVar(id) / "status" as user =>
        req.req.as[UpdateAppointmentStatusRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique os status fornecidos.")
          case Right(updateReq) =>
            service.updateStatus(id, user.id, updateReq.examStatus, updateReq.reportStatus, updateReq.paymentStatus).flatMap {
              case Left(err)   => mapError(err, "Agendamento não encontrado.")
              case Right(resp) => HttpResponses.ok("Status atualizado com sucesso.", resp)
            }
        }
    }
