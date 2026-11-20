package com.visoris.backend.patients.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.patients.domain.Patient
import com.visoris.backend.patients.dto.*
import com.visoris.backend.patients.service.{PatientService, PatientsError}
import com.visoris.backend.shared.http.HttpResponses
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object PatientController:

  private def toResponse(patient: Patient): PatientResponse =
    PatientResponse(
      id = patient.id.toString,
      clientId = patient.clientId.map(_.toString),
      name = patient.name,
      patientType = patient.patientType.toString,
      birthDate = patient.birthDate.map(_.toString),
      biologicalDetails = patient.biologicalDetails,
      createdAt = patient.createdAt
    )

  private def mapError[F[_]: Async](err: PatientsError, notFoundMsg: String): F[Response[F]] =
    err match
      case PatientsError.Validation(errors) =>
        HttpResponses.validationError(errors.map(e => (e.field, e.message)))
      case PatientsError.NotFound =>
        HttpResponses.notFound(notFoundMsg)
      case PatientsError.ConflictAppointments =>
        HttpResponses.conflict("Não é possível excluir o paciente pois existem consultas vinculadas.")
      case PatientsError.Internal(msg) =>
        HttpResponses.internalError(msg)

  def routes[F[_]: Async](service: PatientService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object ClientIdQueryParamMatcher extends QueryParamDecoderMatcher[String]("clientId")
    object OptionalLimitQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("limit")
    object OptionalOffsetQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("offset")

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "patients" :? ClientIdQueryParamMatcher(rawClientId) +& OptionalLimitQueryParamMatcher(rawLimit) +& OptionalOffsetQueryParamMatcher(rawOffset) as user =>
        val clientIdEither = rawClientId.trim.toLongOption.toRight(("clientId", "Parâmetro 'clientId' deve ser um número inteiro válido."))
        val limitEither = HttpResponses.parseOptionalLong("limit", rawLimit)
        val offsetEither = HttpResponses.parseOptionalLong("offset", rawOffset)
        val paramErrors = List(clientIdEither.left.toOption, limitEither.left.toOption, offsetEither.left.toOption).flatten
        if paramErrors.nonEmpty then HttpResponses.validationError(paramErrors)
        else
          service.listByClient(clientIdEither.toOption.get, user.id, limitEither.toOption.flatten.getOrElse(50L), offsetEither.toOption.flatten.getOrElse(0L)).flatMap {
            case Left(err)       => mapError(err, "Cliente não encontrado.")
            case Right(patients) => HttpResponses.ok("Pacientes listados com sucesso.", PatientListResponse(patients.map(toResponse)))
          }

      case req @ POST -> Root / "api" / "v1" / "patients" as user =>
        req.req.as[PatientRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(patientReq) =>
            service.create(patientReq, user.id).flatMap {
              case Left(err)      => mapError(err, "Cliente não encontrado.")
              case Right(patient) => HttpResponses.created("Paciente cadastrado com sucesso.", CreatePatientResponse(toResponse(patient)))
            }
        }

      case req @ PUT -> Root / "api" / "v1" / "patients" / LongVar(patientId) as user =>
        req.req.as[UpdatePatientRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(patientReq) =>
            service.update(patientId, patientReq, user.id).flatMap {
              case Left(err)      => mapError(err, "Paciente não encontrado.")
              case Right(patient) => HttpResponses.ok("Paciente atualizado com sucesso.", toResponse(patient))
            }
        }

      case DELETE -> Root / "api" / "v1" / "patients" / LongVar(patientId) as user =>
        service.delete(patientId, user.id).flatMap {
          case Left(err) => mapError(err, "Paciente não encontrado.")
          case Right(_)  => HttpResponses.ok("Paciente excluído com sucesso.", Option.empty[String])
        }
    }
