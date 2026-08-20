package com.visoris.backend.iam.repository

import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait UserRepository[F[_]]:
  def create(user: User): ConnectionIO[Unit]
  def findById(id: Long): ConnectionIO[Option[User]]
  def findByEmail(email: String): ConnectionIO[Option[User]]
  def findByProfessionalDocument(doc: String): ConnectionIO[Option[User]]

object UserRepository:
  def make[F[_]](transactor: Transactor[F]): UserRepository[F] = new UserRepository[F]:
    def create(user: User): ConnectionIO[Unit] =
      sql"""
        INSERT INTO users (id, email, password_hash, full_name, professional_document, created_at)
        VALUES (${user.id}, ${user.email.toLowerCase}, ${user.passwordHash}, ${user.fullName}, ${user.professionalDocument}, ${user.createdAt})
      """.update.run.void

    def findById(id: Long): ConnectionIO[Option[User]] =
      sql"""
        SELECT id, email, password_hash, full_name, professional_document, created_at
        FROM users WHERE id = $id
      """.query[User].option

    def findByEmail(email: String): ConnectionIO[Option[User]] =
      sql"""
        SELECT id, email, password_hash, full_name, professional_document, created_at
        FROM users WHERE LOWER(email) = ${email.toLowerCase}
      """.query[User].option

    def findByProfessionalDocument(doc: String): ConnectionIO[Option[User]] =
      sql"""
        SELECT id, email, password_hash, full_name, professional_document, created_at
        FROM users WHERE professional_document = ${doc}
      """.query[User].option
