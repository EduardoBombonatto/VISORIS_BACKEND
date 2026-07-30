package com.visoris.backend.iam.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.dto.*
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.iam.service.{RegistrationError, RegistrationService}
import com.visoris.backend.shared.dto.ApiResponse
import doobie.util.transactor.Transactor
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import org.http4s.{HttpRoutes, MediaType, ResponseCookie, SameSite}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

class AuthController[F[_]: Async: Logger](
  jwtSecret: String,
  transactor: Transactor[F]
)(implicit
  userRepo: UserRepository,
  refreshTokenRepo: RefreshTokenRepository
) extends Http4sDsl[F]:

  private def errorResponse(errors: List[ValidationError]) =
    val data = Map("errors" -> errors.map(e => Map("field" -> e.field, "message" -> e.message)))
    ApiResponse(
      erro = true,
      message = "Dados inválidos.",
      data = Some(data),
      httpcode = 400,
      timestamp = java.time.Instant.now
    )

  private val refreshTokenMaxAge = 7 * 24 * 60 * 60L

  private def jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "api" / "v1" / "auth" / "register" =>
      req.as[RegisterRequest].attempt.flatMap {
        case Left(_) =>
          BadRequest(
            ApiResponse.error("Requisição inválida. Verifique o formato dos dados.", 400).asJson
          ).map(_.withContentType(jsonContent))

        case Right(registerRequest) =>
          val deviceInfo = req.headers.get[org.http4s.headers.`User-Agent`].map(_.product.toString)
          val ipAddress = req.from.map(_.toString)

          RegistrationService.register[F](
            registerRequest, jwtSecret, transactor, deviceInfo, ipAddress
          ).flatMap {
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
              val refreshCookie = ResponseCookie(
                name = "refreshToken",
                content = result.refreshToken,
                maxAge = Some(refreshTokenMaxAge),
                path = Some("/api/v1/auth"),
                httpOnly = true,
                secure = true,
                sameSite = Some(SameSite.Strict)
              )
              Created(apiSuccess.asJson)
                .map(_.addCookie(refreshCookie))
                .map(_.withContentType(jsonContent))
          }
      }
  }
