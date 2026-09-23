package com.visoris.backend.templates.repository

import com.visoris.backend.templates.domain.{Template, TemplateSummary}
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait TemplateRepository[F[_]]:
  def insert(
    userId: Long,
    title: String,
    content: String
  ): ConnectionIO[Template]

  def findAllByUserId(userId: Long): ConnectionIO[List[TemplateSummary]]

  def findByIdAndUserId(id: Long, userId: Long): ConnectionIO[Option[Template]]

  def update(
    id: Long,
    userId: Long,
    title: String,
    content: String
  ): ConnectionIO[Option[Template]]

  def delete(id: Long, userId: Long): ConnectionIO[Int]

object TemplateRepository:
  def make[F[_]](transactor: Transactor[F]): TemplateRepository[F] = new TemplateRepository[F]:

    def insert(
      userId: Long,
      title: String,
      content: String
    ): ConnectionIO[Template] =
      sql"""INSERT INTO templates (user_id, title, content)
            VALUES ($userId, $title, $content)
            RETURNING id, user_id, title, content, created_at, updated_at"""
        .query[Template]
        .unique

    def findAllByUserId(userId: Long): ConnectionIO[List[TemplateSummary]] =
      sql"""SELECT id, user_id, title, created_at
            FROM templates
            WHERE user_id = $userId
            ORDER BY created_at DESC"""
        .query[TemplateSummary]
        .to[List]

    def findByIdAndUserId(id: Long, userId: Long): ConnectionIO[Option[Template]] =
      sql"""SELECT id, user_id, title, content, created_at, updated_at
            FROM templates
            WHERE id = $id AND user_id = $userId"""
        .query[Template]
        .option

    def update(
      id: Long,
      userId: Long,
      title: String,
      content: String
    ): ConnectionIO[Option[Template]] =
      sql"""UPDATE templates
            SET title = $title, content = $content
            WHERE id = $id AND user_id = $userId
            RETURNING id, user_id, title, content, created_at, updated_at"""
        .query[Template]
        .option

    def delete(id: Long, userId: Long): ConnectionIO[Int] =
      sql"""DELETE FROM templates
            WHERE id = $id AND user_id = $userId"""
        .update
        .run
