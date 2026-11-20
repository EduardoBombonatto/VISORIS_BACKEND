package com.visoris.backend

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.visoris.backend.appointments.controller.AppointmentController
import com.visoris.backend.appointments.repository.AppointmentRepository
import com.visoris.backend.appointments.service.AppointmentService
import com.visoris.backend.clinics.controller.ClinicsController
import com.visoris.backend.clinics.repository.{ClinicRepository, DoctorClinicRepository}
import com.visoris.backend.clinics.service.ClinicsService
import com.visoris.backend.config.{AppConfig, Database}
import com.visoris.backend.docs.DocsController
import com.visoris.backend.iam.controller.AuthController
import com.visoris.backend.iam.repository.{RefreshTokenRepository, UserRepository}
import com.visoris.backend.iam.service.{AuthService, RegistrationService}
import com.visoris.backend.patients.controller.{ClientController, PatientController}
import com.visoris.backend.patients.repository.{ClientRepository, PatientRepository}
import com.visoris.backend.patients.service.{ClientService, PatientService}
import com.visoris.backend.reports.controller.ReportController
import com.visoris.backend.reports.repository.{ReportImageRepository, ReportRepository}
import com.visoris.backend.reports.service.ReportService
import com.visoris.backend.shared.auth.{AuthMiddleware, TokenService}
import com.visoris.backend.shared.dto.ApiResponse
import com.visoris.backend.templates.controller.TemplateController
import com.visoris.backend.templates.repository.TemplateRepository
import com.visoris.backend.templates.service.TemplateService
import fs2.io.net.Network
import io.circe.syntax.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.{CORS, CORSPolicy, EntityLimiter}
import org.http4s.{Header, Headers, HttpApp, MediaType, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object BackendServer:

  private val MaxRequestBodyBytes: Long = 1048576L // 1 MB
  private val AuthRateLimitWindowMs: Long = 60000L
  private val AuthRateLimitMaxRequests: Int = 120
  private val RateLimitedAuthPaths: Set[String] = Set(
    "/api/v1/auth/login",
    "/api/v1/auth/register",
    "/api/v1/auth/refresh"
  )

  private val securityResponseHeaders: Headers = Headers(
    Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
    Header.Raw(CIString("X-Frame-Options"), "DENY"),
    Header.Raw(CIString("Referrer-Policy"), "no-referrer"),
    Header.Raw(CIString("Permissions-Policy"), "geolocation=(), microphone=(), camera=()"),
    Header.Raw(CIString("Strict-Transport-Security"), "max-age=31536000; includeSubDomains")
  )

  private def corsConfig(allowedOrigins: Set[CIString]): CORSPolicy =
    CORS.policy
      .withAllowOriginHostCi(allowedOrigins)
      .withAllowCredentials(true)

  private def securityHeaders[F[_] : Async](app: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { req =>
      app(req).map(_.putHeaders(securityResponseHeaders))
    }

  private def authRateLimiter[F[_] : Async](
    stateRef: Ref[F, Map[String, List[Long]]]
  )(app: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { req =>
      val path = req.uri.path.renderString
      if RateLimitedAuthPaths.contains(path) then
        val clientKey = req.from.map(_.toString).getOrElse("unknown") + ":" + path
        for
          now <- Async[F].realTime.map(_.toMillis)
          cutoff = now - AuthRateLimitWindowMs
          allowed <- stateRef.modify { buckets =>
            val recent = buckets.getOrElse(clientKey, Nil).filter(_ > cutoff)
            if recent.size >= AuthRateLimitMaxRequests then
              (buckets.updated(clientKey, recent), false)
            else
              val pruned = if buckets.size > 10000 then buckets.view.mapValues(_.filter(_ > cutoff)).filter(_._2.nonEmpty).toMap else buckets
              (pruned.updated(clientKey, now :: recent), true)
          }
          resp <-
            if allowed then app(req)
            else
              Async[F].pure(
                Response[F](Status.TooManyRequests)
                  .withEntity(ApiResponse.error("Muitas requisições. Tente novamente em instantes.", 429).asJson.noSpaces)
                  .withContentType(`Content-Type`(MediaType.application.json))
              )
        yield resp
      else app(req)
    }

  private def errorHandler[F[_] : Async : Logger](app: HttpApp[F]): HttpApp[F] =
    HttpApp[F] { req =>
      app(req).handleErrorWith {
        case _: EntityLimiter.EntityTooLarge =>
          Async[F].pure(
            Response[F](Status.PayloadTooLarge)
              .withEntity(ApiResponse.error("Corpo da requisição excede o limite máximo permitido.", 413).asJson.noSpaces)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        case err =>
          Logger[F].error(err)("Unhandled error processing request") *>
            Async[F].pure(
              Response[F](Status.InternalServerError)
                .withEntity(ApiResponse.error("Erro interno do servidor. Tente novamente.", 500).asJson.noSpaces)
                .withContentType(`Content-Type`(MediaType.application.json))
            )
      }
    }

  def run[F[_] : Async : Network]: F[Nothing] =
    AppConfig.load[F].flatMap(config => run[F](config))

  def run[F[_] : Async : Network](config: AppConfig): F[Nothing] = {
    given Logger[F] = Slf4jLogger.getLogger[F]

    val appResource = for {
      transactor <- Database.makeTransactor[F](config.database)
      _ <- Resource.eval(Database.runMigrations[F](transactor))

      tokenService = TokenService.make(config.jwtSecret.value)
      userRepo = UserRepository.make[F](transactor)
      refreshTokenRepo = RefreshTokenRepository.make[F](transactor)
      registrationService = RegistrationService.make[F](tokenService, transactor, userRepo, refreshTokenRepo)
      authService = AuthService.make[F](tokenService, transactor, userRepo, refreshTokenRepo)
      authMiddleware = AuthMiddleware.make[F](tokenService, userRepo, transactor)

      clinicRepo = ClinicRepository.make[F](transactor)
      doctorClinicRepo = DoctorClinicRepository.make[F](transactor)
      clinicsService = ClinicsService.make[F](clinicRepo, doctorClinicRepo, transactor)
      clinicsRoutes = authMiddleware(ClinicsController.routes[F](clinicsService))

      clientRepo = ClientRepository.make[F](transactor)
      patientRepo = PatientRepository.make[F](transactor)
      clientService = ClientService.make[F](clientRepo, transactor)
      patientService = PatientService.make[F](patientRepo, clientRepo, transactor)
      patientsModuleRoutes = authMiddleware(
        ClientController.routes[F](clientService) <+> PatientController.routes[F](patientService)
      )

      appointmentRepo = AppointmentRepository.make[F](transactor)
      appointmentService = AppointmentService.make[F](appointmentRepo, doctorClinicRepo, patientRepo, transactor)
      appointmentsRoutes = authMiddleware(AppointmentController.routes[F](appointmentService))

      templateRepo = TemplateRepository.make[F](transactor)
      templateService = TemplateService.make[F](templateRepo, transactor)
      templatesRoutes = authMiddleware(TemplateController.routes[F](templateService))

      reportRepo = ReportRepository.make[F](transactor)
      reportImageRepo = ReportImageRepository.make[F](transactor)
      reportService = ReportService.make[F](
        reportRepo,
        reportImageRepo,
        appointmentRepo,
        clinicRepo,
        patientRepo,
        transactor
      )
      reportsRoutes = authMiddleware(ReportController.routes[F](reportService))

      rateLimitState <- Resource.eval(Ref.of[F, Map[String, List[Long]]](Map.empty))

      authRoutes = AuthController.routes[F](registrationService, authService)
      docsRoutes = DocsController.routes[F](config.enableDocs)
      baseApp = (authRoutes <+> docsRoutes <+> clinicsRoutes <+> patientsModuleRoutes <+> appointmentsRoutes <+> templatesRoutes <+> reportsRoutes).orNotFound
      httpApp = securityHeaders(
        corsConfig(config.cors.allowedOrigins)(
          errorHandler(
            authRateLimiter(rateLimitState)(
              EntityLimiter.httpApp(baseApp, MaxRequestBodyBytes)
            )
          )
        )
      )

      _ <-
        EmberServerBuilder.default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(httpApp)
          .build
    } yield ()
    appResource.useForever
  }
