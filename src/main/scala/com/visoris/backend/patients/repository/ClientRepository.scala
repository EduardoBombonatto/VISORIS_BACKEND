package com.visoris.backend.patients.repository

import com.visoris.backend.patients.domain.Client
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait ClientRepository[F[_]]:
  def insert(
    userId: Long,
    fullName: String,
    documentCpf: Option[String],
    email: Option[String],
    phone: Option[String]
  ): ConnectionIO[Client]

  def findById(id: Long): ConnectionIO[Option[Client]]

  def findByUserId(userId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]]

  def findByUserIdAndCpf(userId: Long, documentCpf: String): ConnectionIO[Option[Client]]

object ClientRepository:
  def make[F[_]](transactor: Transactor[F]): ClientRepository[F] = new ClientRepository[F]:

    def insert(
      userId: Long,
      fullName: String,
      documentCpf: Option[String],
      email: Option[String],
      phone: Option[String]
    ): ConnectionIO[Client] =
      sql"""INSERT INTO clients (user_id, full_name, document_cpf, email, phone)
            VALUES ($userId, $fullName, $documentCpf, $email, $phone)
            RETURNING id, user_id, full_name, document_cpf, email, phone, created_at, updated_at"""
        .query[Client]
        .unique

    def findById(id: Long): ConnectionIO[Option[Client]] =
      sql"""SELECT id, user_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients WHERE id = $id"""
        .query[Client]
        .option

    def findByUserId(userId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]] =
      sql"""SELECT id, user_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients
            WHERE user_id = $userId
            ORDER BY full_name
            LIMIT $limit OFFSET $offset"""
        .query[Client]
        .to[List]

    def findByUserIdAndCpf(userId: Long, documentCpf: String): ConnectionIO[Option[Client]] =
      sql"""SELECT id, user_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients
            WHERE user_id = $userId AND document_cpf = $documentCpf"""
        .query[Client]
        .option
