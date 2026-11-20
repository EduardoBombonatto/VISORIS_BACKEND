package com.visoris.backend.patients.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.patients.domain.Patient
import com.visoris.backend.patients.dto.*
import com.visoris.backend.patients.service.{PatientService, PatientsError}
import com.visoris.backend.shared.dto.ApiResponse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object PatientController:

  private def errorResponse(errors: List[ValidationError]): ApiResponse[Map[String, List[Map[String, String]]]] =
    val data = Map("errors" -> errors.map(e => Map("field" -> e.field, "message" -> e.message)))
    ApiResponse(
      erro = true,
      message = "Dados inválidos.",
      data = Some(data),
      httpcode = 400,
      timestamp = java.time.Instant.now
    )

  private def jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

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

  def routes[F[_]: Async](service: PatientService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object ClientIdQueryParamMatcher extends QueryParamDecoderMatcher[String]("clientId")
    object OptionalLimitQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("limit")
    object OptionalOffsetQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("offset")

    def parseOptionalLong(field: String, raw: Option[String]): Either[ValidationError, Option[Long]] =
      raw match
        case None => Right(None)
        case Some(v) =>
          v.trim.toLongOption.toRight(ValidationError(field, s"Parâmetro '$field' deve ser um número inteiro válido.")).map(Some(_))

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "patients" :? ClientIdQueryParamMatcher(rawClientId) +& OptionalLimitQueryParamMatcher(rawLimit) +& OptionalOffsetQueryParamMatcher(rawOffset) as user =>
        val clientIdEither = rawClientId.trim.toLongOption.toRight(ValidationError("clientId", "Parâmetro 'clientId' deve ser um número inteiro válido."))
        val limitEither = parseOptionalLong("limit", rawLimit)
        val offsetEither = parseOptionalLong("offset", rawOffset)
        val paramErrors = List(clientIdEither.left.toOption, limitEither.left.toOption, offsetEither.left.toOption).flatten
        if paramErrors.nonEmpty then
          BadRequest(errorResponse(paramErrors).asJson).map(_.withContentType(jsonContent))
        else
          service.listByClient(clientIdEither.toOption.get, user.id, limitEither.toOption.flatten.getOrElse(50L), offsetEither.toOption.flatten.getOrElse(0L)).flatMap {
            case Left(PatientsError.NotFound) =>
              NotFound(ApiResponse.error("Cliente não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
            case Left(PatientsError.Internal(msg)) =>
              InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
            case Left(_) =>
              BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
            case Right(patients) =>
              val response = PatientListResponse(patients.map(toResponse))
              Ok(ApiResponse.success("Pacientes listados com sucesso.", response, 200).asJson).map(_.withContentType(jsonContent))
          }

      case req @ POST -> Root / "api" / "v1" / "patients" as user =>
        req.req.as[PatientRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(patientReq) =>
            service.create(patientReq, user.id).flatMap {
              case Left(PatientsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(PatientsError.NotFound) =>
                NotFound(ApiResponse.error("Cliente não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
              case Left(PatientsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Left(_) =>
                BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
              case Right(patient) =>
                val response = CreatePatientResponse(toResponse(patient))
                Created(ApiResponse.success("Paciente cadastrado com sucesso.", response, 201).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case req @ PUT -> Root / "api" / "v1" / "patients" / LongVar(patientId) as user =>
        req.req.as[UpdatePatientRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(patientReq) =>
            service.update(patientId, patientReq, user.id).flatMap {
              case Left(PatientsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(PatientsError.NotFound) =>
                NotFound(ApiResponse.error("Paciente não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
              case Left(PatientsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Left(_) =>
                BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
              case Right(patient) =>
                Ok(ApiResponse.success("Paciente atualizado com sucesso.", toResponse(patient), 200).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

      case DELETE -> Root / "api" / "v1" / "patients" / LongVar(patientId) as user =>
        service.delete(patientId, user.id).flatMap {
          case Left(PatientsError.NotFound) =>
            NotFound(ApiResponse.error("Paciente não encontrado.", 404).asJson).map(_.withContentType(jsonContent))
          case Left(PatientsError.ConflictAppointments) =>
            Conflict(ApiResponse.error("Não é possível excluir o paciente pois existem consultas vinculadas.", 409).asJson)
              .map(_.withContentType(jsonContent))
          case Left(PatientsError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(_) =>
            Ok(ApiResponse.success("Paciente excluído com sucesso.", Option.empty[String], 200).asJson)
              .map(_.withContentType(jsonContent))
        }
    }
