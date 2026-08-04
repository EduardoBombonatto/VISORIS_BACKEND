package com.visoris.backend.iam.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.dto.*
import com.visoris.backend.iam.service.{AuthService, LoginError, RefreshError, RegistrationError, RegistrationService}
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

  def routes[F[_]: Async](registrationService: RegistrationService[F], authService: AuthService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    val isProd = false

    HttpRoutes.of[F] {
      case req @ POST -> Root / "api" / "v1" / "auth" / "login" =>
        req.as[LoginRequest].attempt.flatMap {
          case Right(loginRequest) =>
            val deviceInfo = req.headers.get[org.http4s.headers.`User-Agent`].map(_.product.toString)
            val ipAddress = req.from.map(_.toString)
            authService.login(loginRequest, deviceInfo, ipAddress).flatMap {
              case Left(LoginError.Validation(errors)) =>
                BadRequest(errorResponse(errors).asJson).map(_.withContentType(jsonContent))
              case Left(LoginError.InvalidCredentials) =>
                val resp = Response[F](status = Status.Unauthorized)
                  .withEntity(ApiResponse.error("Credenciais inválidas.", 401).asJson)
                  .withContentType(jsonContent)
                resp.pure[F]
              case Left(LoginError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(result) =>
                val userData = UserData(
                  id = result.user.id.toString,
                  fullName = result.user.fullName,
                  professionalDocument = result.user.professionalDocument
                )
                val workspaceDataList = result.workspaces.map(ws =>
                  WorkspaceData(clinicId = ws.clinicId.toString, name = ws.name, role = ws.role)
                )
                val loginResponse = LoginResponse(
                  baseToken = result.baseToken,
                  user = userData,
                  workspaces = workspaceDataList
                )
                val apiSuccess = ApiResponse(
                  erro = false,
                  message = "Autenticado. Selecione o Workspace.",
                  data = Some(loginResponse),
                  httpcode = 200,
                  timestamp = java.time.Instant.now
                )
                val refreshCookie = CookieUtils.createRefreshCookie(result.refreshToken, isProd)
                Ok(apiSuccess.asJson)
                  .map(_.addCookie(refreshCookie))
                  .map(_.withContentType(jsonContent))
            }
          case Left(_) =>
                BadRequest(ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson)
                  .map(_.withContentType(jsonContent))
            }

      case req @ POST -> Root / "api" / "v1" / "auth" / "refresh" =>
        val cookie = req.cookies.find(_.name == "refreshToken").map(_.content)
        cookie match
          case None =>
            val resp = Response[F](status = Status.Unauthorized)
              .withEntity(ApiResponse.error("Refresh token inválido.", 401).asJson)
              .withContentType(jsonContent)
            resp.pure[F]
          case Some(token) =>
            authService.refresh(token).flatMap {
              case Left(RefreshError.Unknown) =>
                val resp = Response[F](status = Status.Unauthorized)
                  .withEntity(ApiResponse.error("Refresh token inválido.", 401).asJson)
                  .withContentType(jsonContent)
                resp.pure[F]
              case Left(RefreshError.Missing) =>
                val resp = Response[F](status = Status.Unauthorized)
                  .withEntity(ApiResponse.error("Refresh token inválido.", 401).asJson)
                  .withContentType(jsonContent)
                resp.pure[F]
              case Left(RefreshError.Expired) =>
                val resp = Response[F](status = Status.Unauthorized)
                  .withEntity(ApiResponse.error("Sessão expirada.", 401).asJson)
                  .withContentType(jsonContent)
                resp.pure[F]
              case Left(RefreshError.Revoked) =>
                val resp = Response[F](status = Status.Unauthorized)
                  .withEntity(ApiResponse.error("Refresh token revogado.", 401).asJson)
                  .withContentType(jsonContent)
                resp.pure[F]
              case Left(RefreshError.Internal(msg)) =>
                InternalServerError(ApiResponse.error(msg, 500).asJson).map(_.withContentType(jsonContent))
              case Right(result) =>
                val refreshPayload = RefreshResponse(accessToken = result.accessToken, expiresIn = result.expiresIn)
                val apiSuccess = ApiResponse(
                  erro = false,
                  message = "Sessão renovada com sucesso.",
                  data = Some(refreshPayload),
                  httpcode = 200,
                  timestamp = java.time.Instant.now
                )
                val refreshCookie = CookieUtils.createRefreshCookie(result.newRefreshToken, isProd)
                Ok(apiSuccess.asJson)
                  .map(_.addCookie(refreshCookie))
                  .map(_.withContentType(jsonContent))
            }

      case req @ POST -> Root / "api" / "v1" / "auth" / "register" =>
        req.as[RegisterRequest].attempt.flatMap {
          case Left(_) =>
            BadRequest(
              ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson
            ).map(_.withContentType(jsonContent))

          case Right(registerRequest) =>
            val deviceInfo = req.headers.get[org.http4s.headers.`User-Agent`].map(_.product.toString)
            val ipAddress = req.from.map(_.toString)

            registrationService.register(registerRequest, deviceInfo, ipAddress).flatMap {
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
