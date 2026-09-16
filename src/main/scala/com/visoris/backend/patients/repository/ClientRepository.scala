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
  
  def findConflicts(userId: Long, documentCpf: String, email: String, phone: String): ConnectionIO[List[Client]]

  def findConflictsExcluding(id: Long, userId: Long, documentCpf: String, email: String, phone: String): ConnectionIO[List[Client]]

  def findByUserIdAndCpf(userId: Long, documentCpf: String): ConnectionIO[Option[Client]]

  def update(
    id: Long,
    userId: Long,
    fullName: String,
    documentCpf: Option[String],
    email: Option[String],
    phone: Option[String]
  ): ConnectionIO[Option[Client]]

  def delete(id: Long, userId: Long): ConnectionIO[Int]

  def hasPatients(clientId: Long): ConnectionIO[Boolean]

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

    def findConflicts(userId: Long, documentCpf: String, email: String, phone: String): ConnectionIO[List[Client]] =
      sql"""SELECT id, user_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients
            WHERE user_id = $userId AND (document_cpf = $documentCpf OR email = $email OR phone = $phone)"""
        .query[Client]
        .to[List]

    def findConflictsExcluding(id: Long, userId: Long, documentCpf: String, email: String, phone: String): ConnectionIO[List[Client]] =
      sql"""SELECT id, user_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients
            WHERE user_id = $userId AND id != $id AND (document_cpf = $documentCpf OR email = $email OR phone = $phone)"""
        .query[Client]
        .to[List]

    def update(
      id: Long,
      userId: Long,
      fullName: String,
      documentCpf: Option[String],
      email: Option[String],
      phone: Option[String]
    ): ConnectionIO[Option[Client]] =
      sql"""UPDATE clients
            SET full_name = $fullName,
                document_cpf = $documentCpf,
                email = $email,
                phone = $phone,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = $id AND user_id = $userId
            RETURNING id, user_id, full_name, document_cpf, email, phone, created_at, updated_at"""
        .query[Client]
        .option

    def delete(id: Long, userId: Long): ConnectionIO[Int] =
      sql"""DELETE FROM clients WHERE id = $id AND user_id = $userId""".update.run

    def hasPatients(clientId: Long): ConnectionIO[Boolean] =
      sql"""SELECT EXISTS (SELECT 1 FROM patients WHERE client_id = $clientId)"""
        .query[Boolean]
        .unique
