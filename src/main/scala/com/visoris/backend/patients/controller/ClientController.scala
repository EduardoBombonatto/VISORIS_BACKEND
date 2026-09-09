package com.visoris.backend.patients.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.patients.domain.Client
import com.visoris.backend.patients.dto.*
import com.visoris.backend.patients.service.{ClientService, ClientsError}
import com.visoris.backend.shared.dto.ApiResponse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object ClientController:

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

  private def toResponse(client: Client): ClientResponse =
    ClientResponse(
      id = client.id.toString,
      clinicId = client.clinicId.toString,
      fullName = client.fullName,
      documentCpf = client.documentCpf,
      email = client.email,
      phone = client.phone,
      createdAt = client.createdAt
    )

  def routes[F[_]: Async](service: ClientService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object ClinicIdQueryParamMatcher extends QueryParamDecoderMatcher[Long]("clinicId")
    object OptionalLimitQueryParamMatcher extends OptionalQueryParamDecoderMatcher[Long]("limit")
    object OptionalOffsetQueryParamMatcher extends OptionalQueryParamDecoderMatcher[Long]("offset")

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "clients" :? ClinicIdQueryParamMatcher(clinicId) +& OptionalLimitQueryParamMatcher(limit) +& OptionalOffsetQueryParamMatcher(offset) as user =>
        service.listByClinic(clinicId, user.id, limit.getOrElse(50L), offset.getOrElse(0L)).flatMap {
          case Left(ClientsError.NotFound) =>
            NotFound(ApiResponse.error("Clínica não encontrada.", 404).asJson).map(_.withContentType(jsonContent))
          case Left(ClientsError.Internal(msg)) =>
            InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida.", 400).asJson).map(_.withContentType(jsonContent))
          case Right(clients) =>
            val response = ClientListResponse(clients.map(toResponse))
            Ok(ApiResponse.success("Clientes listados com sucesso.", response, 200).asJson).map(_.withContentType(jsonContent))
        }

      case req @ POST -> Root / "api" / "v1" / "clients" as user =>
        req.req.as[ClientRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
              .map(_.withContentType(jsonContent))
          case Right(clientReq) =>
            service.create(clientReq, user.id).flatMap {
              case Left(ClientsError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(ClientsError.NotFound) =>
                NotFound(ApiResponse.error("Clínica não encontrada.", 404).asJson).map(_.withContentType(jsonContent))
              case Left(ClientsError.ConflictCpf) =>
                Conflict(ApiResponse.error("Este CPF já está cadastrado nesta clínica.", 409).asJson)
                  .map(_.withContentType(jsonContent))
              case Left(ClientsError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(client) =>
                val response = CreateClientResponse(client.id.toString)
                Created(ApiResponse.success("Cliente cadastrado com sucesso.", response, 201).asJson)
                  .map(_.withContentType(jsonContent))
            }
        }
    }
