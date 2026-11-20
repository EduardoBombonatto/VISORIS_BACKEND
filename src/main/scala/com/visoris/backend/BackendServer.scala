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
import doobie.implicits.*
import fs2.io.net.Network
import io.circe.syntax.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.{CORS, CORSPolicy, EntityLimiter}
import org.http4s.{Header, Headers, HttpApp, MediaType, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration.*

object BackendServer:

  private val MaxRequestBodyBytes: Long = 1048576L // 1 MB
  private val AuthRateLimitWindowMs: Long = 60000L
  private val AuthRateLimitMaxRequests: Int = 120
  private val RateLimitPruneThreshold: Int = 5000
  private val RateLimitHardCap: Int = 10000
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

  private def pruneBuckets(buckets: Map[String, List[Long]], cutoff: Long): Map[String, List[Long]] =
    buckets.view.mapValues(_.filter(_ > cutoff)).filter(_._2.nonEmpty).toMap

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
            val base =
              if buckets.size >= RateLimitPruneThreshold then pruneBuckets(buckets, cutoff)
              else buckets
            val recent = base.getOrElse(clientKey, Nil).filter(_ > cutoff)
            if recent.size >= AuthRateLimitMaxRequests then
              (base.updated(clientKey, recent), false)
            else if !base.contains(clientKey) && base.size >= RateLimitHardCap then
              (base, false)
            else
              (base.updated(clientKey, now :: recent), true)
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

  private def periodicRateLimitCleanup[F[_] : Async](
    stateRef: Ref[F, Map[String, List[Long]]]
  ): F[Unit] =
    (Async[F].sleep(60.seconds) *>
      Async[F].realTime.map(_.toMillis).flatMap { now =>
        stateRef.update(pruneBuckets(_, now - AuthRateLimitWindowMs))
      }).foreverM

  private def periodicExpiredTokenCleanup[F[_] : Async : Logger](
    refreshTokenRepo: RefreshTokenRepository[F],
    transactor: doobie.util.transactor.Transactor[F]
  ): F[Unit] =
    (Async[F].sleep(1.hour) *>
      refreshTokenRepo.deleteExpired().transact(transactor).attempt.flatMap {
        case Right(deleted) if deleted > 0 =>
          Logger[F].info(s"Purged $deleted expired refresh token(s)")
        case Right(_) => Async[F].unit
        case Left(err) =>
          Logger[F].warn(err)("Failed to purge expired refresh tokens")
      }).foreverM

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
      userRepo = UserRepository.make[F]
      refreshTokenRepo = RefreshTokenRepository.make[F]
      registrationService = RegistrationService.make[F](tokenService, transactor, userRepo, refreshTokenRepo)
      authService = AuthService.make[F](tokenService, transactor, userRepo, refreshTokenRepo)
      authMiddleware = AuthMiddleware.make[F](tokenService, userRepo, transactor)

      clinicRepo = ClinicRepository.make[F]
      doctorClinicRepo = DoctorClinicRepository.make[F]
      clinicsService = ClinicsService.make[F](clinicRepo, doctorClinicRepo, transactor)
      clinicsRoutes = authMiddleware(ClinicsController.routes[F](clinicsService))

      clientRepo = ClientRepository.make[F]
      patientRepo = PatientRepository.make[F]
      clientService = ClientService.make[F](clientRepo, transactor)
      patientService = PatientService.make[F](patientRepo, clientRepo, transactor)
      patientsModuleRoutes = authMiddleware(
        ClientController.routes[F](clientService) <+> PatientController.routes[F](patientService)
      )

      appointmentRepo = AppointmentRepository.make[F]
      appointmentService = AppointmentService.make[F](appointmentRepo, doctorClinicRepo, patientRepo, transactor)
      appointmentsRoutes = authMiddleware(AppointmentController.routes[F](appointmentService))

      templateRepo = TemplateRepository.make[F]
      templateService = TemplateService.make[F](templateRepo, transactor)
      templatesRoutes = authMiddleware(TemplateController.routes[F](templateService))

      reportRepo = ReportRepository.make[F]
      reportImageRepo = ReportImageRepository.make[F]
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
      _ <- Async[F].background(periodicRateLimitCleanup[F](rateLimitState))
      _ <- Async[F].background(periodicExpiredTokenCleanup[F](refreshTokenRepo, transactor))

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
