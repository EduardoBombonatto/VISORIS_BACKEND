package com.visoris.backend.clinics.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.clinics.domain.Clinic
import com.visoris.backend.clinics.dto.*
import com.visoris.backend.clinics.service.{ClinicsError, ClinicsService, CreateResult}
import com.visoris.backend.iam.domain.User
import com.visoris.backend.shared.http.HttpResponses
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object ClinicsController:

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
            HttpResponses.internalError("Erro interno do servidor. Tente novamente.")
          case Right(clinics) =>
            HttpResponses.ok("Clínicas listadas com sucesso.", ClinicListResponse(clinics.map(clinicData)))
        }

      case req @ POST -> Root / "api" / "v1" / "clinics" as user =>
        req.req.as[ClinicRequest].attempt.flatMap {
          case Left(_) =>
            HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(clinicReq) =>
            service.createOrReuse(clinicReq, user.id).flatMap {
              case Left(ClinicsError.Validation(errors)) =>
                HttpResponses.validationError(errors.map(e => (e.field, e.message)))
              case Left(ClinicsError.Internal(msg)) =>
                HttpResponses.internalError(msg)
              case Left(ClinicsError.NotFound) =>
                HttpResponses.notFound("Clínica não encontrada.")
              case Left(ClinicsError.ConflictCnpj) =>
                HttpResponses.conflict("Não foi possível cadastrar clínica com este CNPJ.")
              case Right(CreateResult.Created(clinic)) =>
                HttpResponses.created("Clínica criada com sucesso.", CreateClinicResponse(clinicData(clinic)))
              case Right(CreateResult.Reused(clinic)) =>
                HttpResponses.ok("Clínica vinculada com sucesso.", CreateClinicResponse(clinicData(clinic)))
            }
        }
    }
