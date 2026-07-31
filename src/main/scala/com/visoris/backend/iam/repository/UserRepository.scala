package com.visoris.backend.iam.repository

import cats.effect.Async
import cats.syntax.all.*
import com.visoris.backend.iam.domain.User
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

enum RepoError:
  case DuplicateEmail(email: String)
  case DuplicateProfessionalDocument(doc: String)
  case Unexpected(message: String)

trait UserRepository[F[_]]:
  def create(user: User): F[Either[RepoError, Unit]]
  def findByEmail(email: String): F[Option[User]]
  def findByProfessionalDocument(doc: String): F[Option[User]]

object UserRepository:
  def make[F[_]: Async](transactor: Transactor[F]): UserRepository[F] = new UserRepository[F]:
    def create(user: User): F[Either[RepoError, Unit]] =
      sql"""
        INSERT INTO users (id, email, password_hash, full_name, professional_document, created_at)
        VALUES (${user.id}, ${user.email.toLowerCase}, ${user.passwordHash}, ${user.fullName}, ${user.professionalDocument}, ${user.createdAt})
      """.update.run.void.transact(transactor).attempt.flatMap {
        case Left(e) =>
          val sqle = e match
            case s: java.sql.SQLException => s
            case _ => e.getCause match
              case s: java.sql.SQLException => s
              case _ => null
          if sqle != null && sqle.getSQLState == "23505" then
            val msg = sqle.getMessage
            if msg != null && msg.contains("professional_document") then
              Async[F].pure(Left(RepoError.DuplicateProfessionalDocument(user.professionalDocument.getOrElse(""))))
            else
              Async[F].pure(Left(RepoError.DuplicateEmail(user.email)))
          else
            Async[F].pure(Left(RepoError.Unexpected(e.getMessage)))
        case Right(()) => Async[F].pure(Right(()))
      }

    def findByEmail(email: String): F[Option[User]] =
      sql"""
        SELECT id, email, password_hash, full_name, professional_document, created_at
        FROM users WHERE LOWER(email) = ${email.toLowerCase}
      """.query[User].option.transact(transactor)

    def findByProfessionalDocument(doc: String): F[Option[User]] =
      sql"""
        SELECT id, email, password_hash, full_name, professional_document, created_at
        FROM users WHERE professional_document = ${doc}
      """.query[User].option.transact(transactor)
