package com.visoris.backend.templates.dto

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import java.time.Instant

final case class ValidationError(field: String, message: String)

object ValidationError:
  given Encoder[ValidationError] = deriveEncoder
  given Decoder[ValidationError] = deriveDecoder

final case class CreateTemplateRequest(
  title: String,
  content: String
):
  def sanitized: CreateTemplateRequest =
    CreateTemplateRequest(
      title = title.trim,
      content = content
    )

object CreateTemplateRequest:
  given Decoder[CreateTemplateRequest] = Decoder.instance { cursor =>
    for
      title <- cursor.downField("title").as[String].orElse(Right(""))
      content <- cursor.downField("content").as[String].orElse(Right(""))
    yield CreateTemplateRequest(title, content)
  }
  given Encoder[CreateTemplateRequest] = deriveEncoder

final case class CreateTemplateResponse(
  id: String
)

object CreateTemplateResponse:
  given Encoder[CreateTemplateResponse] = deriveEncoder
  given Decoder[CreateTemplateResponse] = deriveDecoder

final case class UpdateTemplateRequest(
  title: Option[String],
  content: Option[String]
):
  def sanitized: UpdateTemplateRequest =
    UpdateTemplateRequest(
      title = title.map(_.trim),
      content = content
    )

object UpdateTemplateRequest:
  given Decoder[UpdateTemplateRequest] = Decoder.instance { cursor =>
    for
      title <- cursor.downField("title").as[Option[String]]
      content <- cursor.downField("content").as[Option[String]]
    yield UpdateTemplateRequest(title, content)
  }
  given Encoder[UpdateTemplateRequest] = deriveEncoder

final case class TemplateSummaryResponse(
  id: String,
  title: String,
  createdAt: Instant
)

object TemplateSummaryResponse:
  given Encoder[TemplateSummaryResponse] = deriveEncoder
  given Decoder[TemplateSummaryResponse] = deriveDecoder

final case class TemplateListResponse(
  templates: List[TemplateSummaryResponse]
)

object TemplateListResponse:
  given Encoder[TemplateListResponse] = deriveEncoder
  given Decoder[TemplateListResponse] = deriveDecoder

final case class TemplateDetailResponse(
  id: String,
  userId: String,
  title: String,
  content: String,
  createdAt: Instant,
  updatedAt: Instant
)

object TemplateDetailResponse:
  given Encoder[TemplateDetailResponse] = deriveEncoder
  given Decoder[TemplateDetailResponse] = deriveDecoder
