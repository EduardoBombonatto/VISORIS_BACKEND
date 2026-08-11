package com.visoris.backend.shared.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.repository.UserRepository
import com.visoris.backend.shared.dto.ApiResponse
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.server.{AuthMiddleware => Http4sAuthMiddleware}
import org.typelevel.log4cats.Logger

object AuthMiddleware:
  def make[F[_]: Async: Logger](
    tokenService: TokenService,
    userRepo: UserRepository[F],
    transactor: Transactor[F]
  ): Http4sAuthMiddleware[F, User] =

    val authUser: Kleisli[F, Request[F], Either[String, User]] =
      Kleisli { req =>
        val tokenOpt = req.cookies.find(_.name == "accessToken").map(_.content)
        tokenOpt match
          case Some(token) =>
            tokenService.validateToken[F](token, "ACCESS").flatMap {
              case Some(claims) =>
                userRepo.findById(claims.userId.toLong).transact(transactor).map {
                  case Some(user) => Right(user)
                  case None       => Left("Usuário não encontrado")
                }
              case None =>
                Logger[F].warn("Token validation failed") *>
                  (Left("Token JWT inválido ou expirado"): Either[String, User]).pure[F]
            }
          case None =>
            (Left("Cookie accessToken ausente"): Either[String, User]).pure[F]
      }

    val onFailure: AuthedRoutes[String, F] =
      Kleisli { req =>
        OptionT.liftF(
          Async[F].pure(
            Response[F](status = Status.Unauthorized)
              .withEntity(ApiResponse.error(req.context, 401))
          )
        )
      }

    Http4sAuthMiddleware(authUser, onFailure)
