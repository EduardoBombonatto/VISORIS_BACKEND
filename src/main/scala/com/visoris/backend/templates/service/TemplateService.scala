package com.visoris.backend.templates.service

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.templates.domain.{Template, TemplateSummary}
import com.visoris.backend.templates.dto.{CreateTemplateRequest, UpdateTemplateRequest, ValidationError}
import com.visoris.backend.templates.repository.TemplateRepository
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor

trait TemplateService[F[_]]:
  def create(request: CreateTemplateRequest, userId: Long): F[Either[TemplatesError, Template]]
  def listByUser(userId: Long): F[Either[TemplatesError, List[TemplateSummary]]]
  def findById(id: Long, userId: Long): F[Either[TemplatesError, Template]]
  def update(id: Long, request: UpdateTemplateRequest, userId: Long): F[Either[TemplatesError, Template]]
  def delete(id: Long, userId: Long): F[Either[TemplatesError, Unit]]

object TemplateService:

  val MaxTitleLength = 255

  def validateCreateRequest(request: CreateTemplateRequest): Either[List[ValidationError], CreateTemplateRequest] =
    val sanitized = request.sanitized
    val rawTitle = sanitized.title

    val titleErrors =
      if rawTitle.isEmpty then List(ValidationError("title", "Título do template é obrigatório."))
      else if rawTitle.length > MaxTitleLength then
        List(ValidationError("title", s"Título não pode ter mais de $MaxTitleLength caracteres."))
      else Nil

    if titleErrors.nonEmpty then Left(titleErrors)
    else Right(sanitized)

  def validateUpdateRequest(request: UpdateTemplateRequest): Either[List[ValidationError], UpdateTemplateRequest] =
    val sanitized = request.sanitized

    val emptyBodyErrors =
      if sanitized.title.isEmpty && sanitized.content.isEmpty then
        List(ValidationError("body", "Pelo menos um campo ('title' ou 'content') deve ser fornecido para atualização."))
      else Nil

    val titleErrors = sanitized.title match
      case Some(t) if t.isEmpty => List(ValidationError("title", "Título do template não pode ser vazio."))
      case Some(t) if t.length > MaxTitleLength =>
        List(ValidationError("title", s"Título não pode ter mais de $MaxTitleLength caracteres."))
      case _ => Nil

    val allErrors = emptyBodyErrors ++ titleErrors
    if allErrors.nonEmpty then Left(allErrors)
    else Right(sanitized)

  def make[F[_]: Async](
    templateRepo: TemplateRepository[F],
    xa: Transactor[F]
  ): TemplateService[F] = new TemplateService[F]:

    def create(request: CreateTemplateRequest, userId: Long): F[Either[TemplatesError, Template]] =
      validateCreateRequest(request) match
        case Left(errors) => Async[F].pure(Left(TemplatesError.Validation(errors)))
        case Right(valid) =>
          templateRepo
            .insert(userId, valid.title, valid.content)
            .transact(xa)
            .map(Right(_))

    def listByUser(userId: Long): F[Either[TemplatesError, List[TemplateSummary]]] =
      templateRepo
        .findAllByUserId(userId)
        .transact(xa)
        .map(Right(_))

    def findById(id: Long, userId: Long): F[Either[TemplatesError, Template]] =
      templateRepo
        .findByIdAndUserId(id, userId)
        .transact(xa)
        .map {
          case Some(t) => Right(t)
          case None    => Left(TemplatesError.NotFound)
        }

    def update(id: Long, request: UpdateTemplateRequest, userId: Long): F[Either[TemplatesError, Template]] =
      validateUpdateRequest(request) match
        case Left(errors) => Async[F].pure(Left(TemplatesError.Validation(errors)))
        case Right(valid) =>
          val program: EitherT[ConnectionIO, TemplatesError, Template] = for
            existingOpt <- EitherT.right[TemplatesError](templateRepo.findByIdAndUserId(id, userId))
            existing    <- EitherT.fromOption[ConnectionIO](existingOpt, TemplatesError.NotFound)
            newTitle   = valid.title.getOrElse(existing.title)
            newContent = valid.content.getOrElse(existing.content)
            updatedOpt  <- EitherT.right[TemplatesError](templateRepo.update(id, userId, newTitle, newContent))
            updated     <- EitherT.fromOption[ConnectionIO](updatedOpt, TemplatesError.NotFound)
          yield updated

          program.value.transact(xa)

    def delete(id: Long, userId: Long): F[Either[TemplatesError, Unit]] =
      val program: EitherT[ConnectionIO, TemplatesError, Unit] = for
        affected <- EitherT.right[TemplatesError](templateRepo.delete(id, userId))
        _        <- EitherT.cond[ConnectionIO](affected > 0, (), TemplatesError.NotFound)
      yield ()

      program.value.transact(xa)
