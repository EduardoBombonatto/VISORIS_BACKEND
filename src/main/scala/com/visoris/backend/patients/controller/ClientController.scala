package com.visoris.backend.patients.controller

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.patients.domain.Client
import com.visoris.backend.patients.dto.*
import com.visoris.backend.patients.service.{ClientService, ClientsError}
import com.visoris.backend.shared.http.HttpResponses
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

object ClientController:

  private def toResponse(client: Client): ClientResponse =
    ClientResponse(
      id = client.id.toString,
      userId = client.userId.toString,
      fullName = client.fullName,
      documentCpf = client.documentCpf,
      email = client.email,
      phone = client.phone,
      createdAt = client.createdAt
    )

  private def mapError[F[_]: Async](err: ClientsError, notFoundMsg: String): F[Response[F]] =
    err match
      case ClientsError.Validation(errors) =>
        HttpResponses.validationError(errors.map(e => (e.field, e.message)))
      case ClientsError.NotFound =>
        HttpResponses.notFound(notFoundMsg)
      case ClientsError.ConflictCpf =>
        HttpResponses.conflict("Este CPF já está cadastrado para este usuário.")
      case ClientsError.ConflictEmail =>
        HttpResponses.conflict("Este e-mail já está cadastrado para este usuário.")
      case ClientsError.ConflictPhone =>
        HttpResponses.conflict("Este telefone já está cadastrado para este usuário.")
      case ClientsError.ConflictPatients =>
        HttpResponses.conflict("Não é possível excluir o tutor pois existem pacientes vinculados.")
      case ClientsError.Internal(msg) =>
        HttpResponses.internalError(msg)

  def routes[F[_]: Async](service: ClientService[F]): AuthedRoutes[User, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object OptionalLimitQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("limit")
    object OptionalOffsetQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("offset")

    AuthedRoutes.of[User, F] {
      case GET -> Root / "api" / "v1" / "clients" :? OptionalLimitQueryParamMatcher(rawLimit) +& OptionalOffsetQueryParamMatcher(rawOffset) as user =>
        val limitEither = HttpResponses.parseOptionalLong("limit", rawLimit)
        val offsetEither = HttpResponses.parseOptionalLong("offset", rawOffset)
        val paramErrors = List(limitEither.left.toOption, offsetEither.left.toOption).flatten
        if paramErrors.nonEmpty then HttpResponses.validationError(paramErrors)
        else
          service.listByUser(user.id, limitEither.toOption.flatten.getOrElse(50L), offsetEither.toOption.flatten.getOrElse(0L)).flatMap {
            case Left(err)      => mapError(err, "Usuário não encontrado.")
            case Right(clients) => HttpResponses.ok("Clientes listados com sucesso.", ClientListResponse(clients.map(toResponse)))
          }

      case req @ POST -> Root / "api" / "v1" / "clients" as user =>
        req.req.as[ClientRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(clientReq) =>
            service.create(clientReq, user.id).flatMap {
              case Left(err)     => mapError(err, "Usuário não encontrado.")
              case Right(client) => HttpResponses.created("Cliente cadastrado com sucesso.", CreateClientResponse(client.id.toString))
            }
        }

      case req @ PUT -> Root / "api" / "v1" / "clients" / LongVar(clientId) as user =>
        req.req.as[ClientRequest].attempt.flatMap {
          case Left(_) => HttpResponses.badRequest("Requisição inválida. Verifique o formato dos dados.")
          case Right(clientReq) =>
            service.update(clientId, clientReq, user.id).flatMap {
              case Left(err)     => mapError(err, "Cliente não encontrado.")
              case Right(client) => HttpResponses.ok("Cliente atualizado com sucesso.", toResponse(client))
            }
        }

      case DELETE -> Root / "api" / "v1" / "clients" / LongVar(clientId) as user =>
        service.delete(clientId, user.id).flatMap {
          case Left(err) => mapError(err, "Cliente não encontrado.")
          case Right(_)  => HttpResponses.ok("Cliente excluído com sucesso.", Option.empty[String])
        }
    }
