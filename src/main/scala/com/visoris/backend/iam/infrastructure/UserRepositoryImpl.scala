package com.visoris.backend.iam.infrastructure

import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.repository.UserRepository
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import java.time.Instant

class UserRepositoryImpl extends UserRepository:

  def create(user: User): ConnectionIO[Unit] =
    sql"""
      INSERT INTO users (id, email, password_hash, full_name, professional_document, created_at)
      VALUES (${user.id}, ${user.email.toLowerCase}, ${user.passwordHash}, ${user.fullName}, ${user.professionalDocument}, ${user.createdAt})
    """.update.run.void

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
