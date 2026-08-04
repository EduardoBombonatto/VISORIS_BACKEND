package com.visoris.backend.iam.repository

import cats.syntax.all.*
import com.visoris.backend.iam.domain.{User, Workspace}
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

enum RepoError:
  case DuplicateEmail(email: String)
  case DuplicateProfessionalDocument(doc: String)
  case Unexpected(message: String)

trait UserRepository[F[_]]:
  def create(user: User): ConnectionIO[Either[RepoError, Unit]]
  def findById(id: Long): ConnectionIO[Option[User]]
  def findByEmail(email: String): ConnectionIO[Option[User]]
  def findByProfessionalDocument(doc: String): ConnectionIO[Option[User]]
  def findWorkspacesByUserId(userId: Long): ConnectionIO[List[Workspace]]

object UserRepository:
  def make[F[_]](transactor: Transactor[F]): UserRepository[F] = new UserRepository[F]:
    def create(user: User): ConnectionIO[Either[RepoError, Unit]] =
      sql"""
        INSERT INTO users (id, email, password_hash, full_name, professional_document, created_at)
        VALUES (${user.id}, ${user.email.toLowerCase}, ${user.passwordHash}, ${user.fullName}, ${user.professionalDocument}, ${user.createdAt})
      """.update.run.void.attempt.map {
        case Right(()) => Right(())
        case Left(e) =>
          val sqle = e match
            case s: java.sql.SQLException => s
            case _ => e.getCause match
              case s: java.sql.SQLException => s
              case _ => null
          if sqle != null && sqle.getSQLState == "23505" then
            val msg = sqle.getMessage
            if msg != null && msg.contains("professional_document") then
              Left(RepoError.DuplicateProfessionalDocument(user.professionalDocument.getOrElse("")))
            else
              Left(RepoError.DuplicateEmail(user.email))
          else
            Left(RepoError.Unexpected(e.getMessage))
      }

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

    def findWorkspacesByUserId(userId: Long): ConnectionIO[List[Workspace]] =
      sql"""
        SELECT c.id, c.name, dc.role
        FROM doctor_clinics dc
        JOIN clinics c ON c.id = dc.clinic_id
        WHERE dc.user_id = $userId
        ORDER BY c.name
      """.query[Workspace].to[List]
