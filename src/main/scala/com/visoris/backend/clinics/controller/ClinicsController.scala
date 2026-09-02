package com.visoris.backend.clinics.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.clinics.domain.Clinic
import com.visoris.backend.clinics.dto.*
import com.visoris.backend.clinics.service.{ClinicsError, ClinicsService, CreateResult}
import com.visoris.backend.iam.domain.User
import com.visoris.backend.shared.dto.ApiResponse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object ClinicsController:

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

  private def clinicData(clinic: Clinic): ClinicResponse =
    ClinicResponse(
      id = clinic.id.toString,
      name = clinic.name,
      cnpj = clinic.cnpj,
      phone = clinic.phone,
      address = clinic.address
    )

  def routes[F[_]: Async](service: ClinicsService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "clinics" as user =>
        service.listForDoctor(user.id).flatMap {
          case Left(_) =>
            InternalServerError(ApiResponse.error("Erro interno do servidor. Tente novamente.", 500).asJson)
              .map(_.withContentType(jsonContent))
          case Right(clinics) =>
            val response = ClinicListResponse(clinics.map(clinicData))
            Ok(ApiResponse.success("Clínicas listadas com sucesso.", response, 200).asJson)
              .map(_.withContentType(jsonContent))
        }

      case req @ POST -> Root / "api" / "v1" / "clinics" as user =>
        req.req.as[ClinicRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(clinicReq) =>
            service.createOrReuse(clinicReq, user.id).flatMap {
              case Left(ClinicsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(ClinicsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Left(ClinicsError.NotFound) =>
                NotFound(ApiResponse.error("Clínica não encontrada.", 404).asJson).map(_.withContentType(jsonContent))
              case Right(CreateResult.Created(clinic)) =>
                val response = CreateClinicResponse(clinicData(clinic))
                Created(ApiResponse.success("Clínica criada com sucesso.", response, 201).asJson)
                  .map(_.withContentType(jsonContent))
              case Right(CreateResult.Reused(clinic)) =>
                val response = CreateClinicResponse(clinicData(clinic))
                Ok(ApiResponse.success("Clínica vinculada com sucesso.", response, 200).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }

    }
