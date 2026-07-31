package com.visoris.backend.iam.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.dto.*
import com.visoris.backend.iam.service.{RegistrationError, RegistrationService}
import com.visoris.backend.shared.dto.ApiResponse
import com.visoris.backend.shared.utils.CookieUtils
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object AuthController:
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

  def routes[F[_]: Async](service: RegistrationService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    val isProd = false

    HttpRoutes.of[F] {
      case req @ POST -> Root / "api" / "v1" / "auth" / "register" =>
        req.as[RegisterRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(
              ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson
            ).map(_.withContentType(jsonContent))

          case Right(registerRequest) =>
            val deviceInfo = req.headers.get[org.http4s.headers.`User-Agent`].map(_.product.toString)
            val ipAddress = req.from.map(_.toString)

            service.register(registerRequest, deviceInfo, ipAddress).flatMap {
              case Left(RegistrationError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson)
                  .map(_.withContentType(jsonContent))

              case Left(RegistrationError.DuplicateField(field, msg)) =>
                if field == "email" then
                  Conflict(
                    ApiResponse.error(msg, 409).asJson
                  ).map(_.withContentType(jsonContent))
                else
                  BadRequest(
                    errorResponse(List(ValidationError(field, msg))).asJson
                  ).map(_.withContentType(jsonContent))

              case Left(RegistrationError.Internal(msg)) =>
                InternalServerError(
                  ApiResponse.error(msg, 500).asJson
                ).map(_.withContentType(jsonContent))

              case Right(result) =>
                val userData = UserData(
                  id = result.user.id.toString,
                  fullName = result.user.fullName,
                  professionalDocument = result.user.professionalDocument
                )
                val registerResponse = RegisterResponse(
                  baseToken = result.baseToken,
                  user = userData,
                  workspaces = List.empty
                )
                val apiSuccess = ApiResponse(
                  erro = false,
                  message = "Conta criada com sucesso.",
                  data = Some(registerResponse),
                  httpcode = 201,
                  timestamp = java.time.Instant.now
                )
                val refreshCookie = CookieUtils.createRefreshCookie(result.refreshToken, isProd)
                Created(apiSuccess.asJson)
                  .map(_.addCookie(refreshCookie))
                  .map(_.withContentType(jsonContent))
            }
        }
    }
