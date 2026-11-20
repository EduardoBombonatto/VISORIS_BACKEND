package com.visoris.backend.shared.http

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.shared.dto.ApiResponse
import io.circe.Encoder
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`

object HttpResponses:
  val jsonContent: `Content-Type` = `Content-Type`(MediaType.application.json)

  def ok[F[_]: Async, T: Encoder](message: String, data: T): F[Response[F]] =
    Response[F](Status.Ok)
      .withEntity(ApiResponse.success(message, data, 200).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def created[F[_]: Async, T: Encoder](message: String, data: T): F[Response[F]] =
    Response[F](Status.Created)
      .withEntity(ApiResponse.success(message, data, 201).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def badRequest[F[_]: Async](message: String): F[Response[F]] =
    Response[F](Status.BadRequest)
      .withEntity(ApiResponse.error(message, 400).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def validationError[F[_]: Async](errors: List[(String, String)]): F[Response[F]] =
    val data = Map("errors" -> errors.map { case (f, m) => Map("field" -> f, "message" -> m) })
    val body = ApiResponse(
      erro = true,
      message = "Dados inválidos.",
      data = Some(data),
      httpcode = 400,
      timestamp = java.time.Instant.now
    )
    Response[F](Status.BadRequest)
      .withEntity(body.asJson)
      .withContentType(jsonContent)
      .pure[F]

  def unauthorized[F[_]: Async](message: String): F[Response[F]] =
    Response[F](Status.Unauthorized)
      .withEntity(ApiResponse.error(message, 401).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def notFound[F[_]: Async](message: String): F[Response[F]] =
    Response[F](Status.NotFound)
      .withEntity(ApiResponse.error(message, 404).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def conflict[F[_]: Async](message: String): F[Response[F]] =
    Response[F](Status.Conflict)
      .withEntity(ApiResponse.error(message, 409).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def internalError[F[_]: Async](message: String): F[Response[F]] =
    Response[F](Status.InternalServerError)
      .withEntity(ApiResponse.error(message, 500).asJson)
      .withContentType(jsonContent)
      .pure[F]

  def parseOptionalLong(field: String, raw: Option[String]): Either[(String, String), Option[Long]] =
    raw match
      case None => Right(None)
      case Some(v) =>
        v.trim.toLongOption
          .toRight((field, s"Parâmetro '$field' deve ser um número inteiro válido."))
          .map(Some(_))
