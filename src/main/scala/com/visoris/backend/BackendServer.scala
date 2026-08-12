package com.visoris.backend

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.visoris.backend.config.Database
import com.visoris.backend.docs.DocsController
import com.visoris.backend.iam.controller.AuthController
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.iam.service.{AuthService, RegistrationService, WorkspaceService}
import com.visoris.backend.shared.auth.{AuthMiddleware, TokenBlacklist, TokenService}
import com.visoris.backend.shared.dto.ApiResponse
import fs2.io.net.Network
import io.circe.syntax.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.{CORS, CORSPolicy}
import org.http4s.{HttpApp, MediaType}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object BackendServer:

  private def allowedOrigins: Set[CIString] =
    sys.env
      .getOrElse("CORS_ALLOWED_ORIGINS", "http://localhost:3000")
      .split(',')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(CIString(_))
      .toSet

  private def corsConfig: CORSPolicy =
    CORS.policy
      .withAllowOriginHostCi(allowedOrigins)
      .withAllowCredentials(true)

  private def errorHandler[F[_]: Async: Logger](app: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { req =>
      app(req).handleErrorWith { err =>
        Logger[F].error(err)("Unhandled error processing request") *>
          Async[F].pure(
            org.http4s.Response[F](
              org.http4s.Status.InternalServerError
            ).withEntity(
              ApiResponse.error("Erro interno do servidor. Tente novamente.", 500).asJson.noSpaces
            ).withContentType(`Content-Type`(MediaType.application.json))
          )
      }
    }

  def run[F[_]: Async: Network]: F[Nothing] = {
    given Logger[F] = Slf4jLogger.getLogger[F]

    val appResource = for {
      dbUrl  <- Resource.pure[F, String](sys.env.getOrElse("DB_URL", "jdbc:postgresql://postgres:5432/visoris_db"))
      dbUser <- Resource.pure[F, String](sys.env.getOrElse("DB_USER", "postgres"))
      dbPass <- Resource.pure[F, String](sys.env.getOrElse("DB_PASSWORD", "postgres"))
      jwtSecret <- Resource.pure[F, String](sys.env.getOrElse("JWT_SECRET", "changeme-dev-secret"))

      transactor <- Database.makeTransactor[F](dbUrl, dbUser, dbPass)
      _ <- Resource.eval(Database.runMigrations[F](transactor))

      tokenService = TokenService.make(jwtSecret)
      userRepo = UserRepository.make[F](transactor)
      refreshTokenRepo = RefreshTokenRepository.make[F](transactor)
      registrationService = RegistrationService.make[F](tokenService, transactor, userRepo, refreshTokenRepo)
      authService = AuthService.make[F](tokenService, transactor, userRepo, refreshTokenRepo)
      authMiddleware = AuthMiddleware.make[F](tokenService, userRepo, transactor)
      tokenBlacklist <- Resource.eval(TokenBlacklist.make[F])
      workspaceService = WorkspaceService.make[F](tokenService, tokenBlacklist, transactor, userRepo, refreshTokenRepo)

      authRoutes = AuthController.routes[F](registrationService, authService, workspaceService)
      docsRoutes = DocsController.routes[F]
      httpApp = corsConfig(errorHandler((authRoutes <+> docsRoutes).orNotFound))

      _ <-
        EmberServerBuilder.default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(httpApp)
          .build
    } yield ()
    appResource.useForever
  }
