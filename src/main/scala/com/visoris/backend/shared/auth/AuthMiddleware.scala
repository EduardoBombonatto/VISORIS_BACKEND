package com.visoris.backend.shared.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.repository.UserRepository
import com.visoris.backend.shared.dto.ApiResponse
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.server.{AuthMiddleware => Http4sAuthMiddleware}
import org.typelevel.log4cats.Logger

object AuthMiddleware:
  def make[F[_]: Async: Logger](
    jwtSecret: String,
    userRepo: UserRepository[F]
  ): Http4sAuthMiddleware[F, User] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    val authUser: Kleisli[F, Request[F], Either[String, User]] =
      Kleisli { req =>
        val tokenOpt = req.headers.get[Authorization].map(_.credentials).collect {
          case Credentials.Token(AuthScheme.Bearer, token) => token
        }
        tokenOpt match
          case Some(token) =>
            JwtService.decodeAndValidate[F](jwtSecret, token).flatMap {
              case Right(claims) =>
                userRepo.findByEmail(claims.email).map {
                  case Some(user) => Right(user)
                  case None       => Left("Usuário não encontrado")
                }
              case Left(err) =>
                Logger[F].warn(s"Token validation failed: $err") *>
                  (Left("Token JWT inválido ou expirado"): Either[String, User]).pure[F]
            }
          case None =>
            (Left("Cabeçalho Authorization com Bearer ausente"): Either[String, User]).pure[F]
      }

    val onFailure: AuthedRoutes[String, F] =
      Kleisli { req =>
        OptionT.liftF(
          Unauthorized(
            `WWW-Authenticate`(Challenge("Bearer", "visoris-api")),
            ApiResponse.error(req.context, 401)
          )
        )
      }

    Http4sAuthMiddleware(authUser, onFailure)
