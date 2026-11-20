package com.visoris.backend.iam.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.dto.*
import com.visoris.backend.iam.service.*
import com.visoris.backend.shared.dto.ApiResponse
import com.visoris.backend.shared.http.HttpResponses
import com.visoris.backend.shared.utils.CookieUtils
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object AuthController:

  private def toUserData(user: User): UserData =
    UserData(
      id = user.id.toString,
      fullName = user.fullName,
      professionalDocument = user.professionalDocument
    )

  private def extractClientMeta[F[_]](req: Request[F]): (Option[String], Option[String]) =
    val deviceInfo = req.headers.get[org.http4s.headers.`User-Agent`].map(_.product.toString.take(255))
    val ipAddress = req.from.map(_.toString.take(45))
    (deviceInfo, ipAddress)

  private def withSessionCookies[F[_]](resp: Response[F], accessToken: String, refreshToken: String): Response[F] =
    resp
      .addCookie(CookieUtils.createAccessTokenCookie(accessToken))
      .addCookie(CookieUtils.createRefreshCookie(refreshToken))

  private def withClearedCookies[F[_]](resp: Response[F]): Response[F] =
    resp
      .addCookie(CookieUtils.clearAccessTokenCookie)
      .addCookie(CookieUtils.clearRefreshTokenCookie)

  private def handleLogin[F[_]: Async](req: Request[F], authService: AuthService[F]): F[Response[F]] =
    req.as[LoginRequest].attempt.flatMap {
      case Left(_) =>
        HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
      case Right(loginRequest) =>
        val (deviceInfo, ipAddress) = extractClientMeta(req)
        authService.login(loginRequest, deviceInfo, ipAddress).flatMap {
          case Left(LoginError.Validation(errors)) =>
            HttpResponses.validationError(errors.map(e => (e.field, e.message)))
          case Left(LoginError.InvalidCredentials) =>
            HttpResponses.unauthorized("Credenciais inválidas.")
          case Left(LoginError.Internal(msg)) =>
            HttpResponses.internalError(msg)
          case Right(result) =>
            HttpResponses
              .ok("Autenticado com sucesso.", LoginResponse(toUserData(result.user)))
              .map(withSessionCookies(_, result.accessToken, result.refreshToken))
        }
    }

  private def handleRefresh[F[_]: Async](req: Request[F], authService: AuthService[F]): F[Response[F]] =
    req.cookies.find(_.name == "refreshToken").map(_.content) match
      case None =>
        HttpResponses.unauthorized("Refresh token inválido.")
      case Some(token) =>
        authService.refresh(token).flatMap {
          case Left(RefreshError.Unknown | RefreshError.Missing) =>
            HttpResponses.unauthorized("Refresh token inválido.")
          case Left(RefreshError.Expired) =>
            HttpResponses.unauthorized("Sessão expirada.")
          case Left(RefreshError.Revoked) =>
            HttpResponses.unauthorized("Refresh token revogado.")
          case Left(RefreshError.Internal(msg)) =>
            HttpResponses.internalError(msg)
          case Right(result) =>
            HttpResponses
              .ok("Sessão renovada com sucesso.", RefreshResponse(expiresIn = result.expiresIn))
              .map(withSessionCookies(_, result.accessToken, result.newRefreshToken))
        }

  private def handleMe[F[_]: Async](req: Request[F], authService: AuthService[F]): F[Response[F]] =
    val accessToken = req.cookies.find(_.name == "accessToken").map(_.content)
    val refreshToken = req.cookies.find(_.name == "refreshToken").map(_.content)
    authService.session(accessToken, refreshToken).flatMap {
      case Left(SessionError.Unauthenticated) =>
        HttpResponses.unauthorized("Não autenticado.")
      case Left(SessionError.Internal(msg)) =>
        HttpResponses.internalError(msg)
      case Right(result) =>
        HttpResponses
          .ok("Sessão restaurada com sucesso.", LoginResponse(toUserData(result.user)))
          .map { resp =>
            val withAccess = result.newAccessToken.fold(resp)(t => resp.addCookie(CookieUtils.createAccessTokenCookie(t)))
            result.newRefreshToken.fold(withAccess)(t => withAccess.addCookie(CookieUtils.createRefreshCookie(t)))
          }
    }

  private def handleLogout[F[_]: Async](req: Request[F], authService: AuthService[F]): F[Response[F]] =
    val refreshToken = req.cookies.find(_.name == "refreshToken").map(_.content)
    authService.logout(refreshToken).flatMap {
      case Right(_) =>
        val body: ApiResponse[Unit] = ApiResponse(
          erro = false,
          message = "Logout realizado com sucesso.",
          data = None,
          httpcode = 200,
          timestamp = java.time.Instant.now
        )
        Response[F](Status.Ok)
          .withEntity(body.asJson)
          .withContentType(HttpResponses.jsonContent)
          .pure[F]
          .map(withClearedCookies)
      case Left(LogoutError.Internal(msg)) =>
        HttpResponses.internalError(msg).map(withClearedCookies)
    }

  private def handleRegister[F[_]: Async](req: Request[F], registrationService: RegistrationService[F]): F[Response[F]] =
    req.as[RegisterRequest].attempt.flatMap {
      case Left(_) =>
        HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
      case Right(registerRequest) =>
        val (deviceInfo, ipAddress) = extractClientMeta(req)
        registrationService.register(registerRequest, deviceInfo, ipAddress).flatMap {
          case Left(RegistrationError.Validation(errors)) =>
            HttpResponses.validationError(errors.map(e => (e.field, e.message)))
          case Left(RegistrationError.DuplicateField("email", msg)) =>
            HttpResponses.conflict(msg)
          case Left(RegistrationError.DuplicateField(field, msg)) =>
            HttpResponses.validationError(List((field, msg)))
          case Left(RegistrationError.Internal(msg)) =>
            HttpResponses.internalError(msg)
          case Right(result) =>
            HttpResponses
              .created("Conta criada com sucesso.", RegisterResponse(toUserData(result.user)))
              .map(withSessionCookies(_, result.accessToken, result.refreshToken))
        }
    }

  def routes[F[_]: Async](registrationService: RegistrationService[F], authService: AuthService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case req @ POST -> Root / "api" / "v1" / "auth" / "login"    => handleLogin(req, authService)
      case req @ POST -> Root / "api" / "v1" / "auth" / "refresh"  => handleRefresh(req, authService)
      case req @ GET  -> Root / "api" / "v1" / "auth" / "me"       => handleMe(req, authService)
      case req @ POST -> Root / "api" / "v1" / "auth" / "logout"   => handleLogout(req, authService)
      case req @ POST -> Root / "api" / "v1" / "auth" / "register" => handleRegister(req, registrationService)
    }
