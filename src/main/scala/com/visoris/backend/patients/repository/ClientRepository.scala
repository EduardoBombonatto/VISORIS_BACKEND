package com.visoris.backend.patients.repository

import com.visoris.backend.patients.domain.Client
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait ClientRepository[F[_]]:
  def insert(
    clinicId: Long,
    fullName: String,
    documentCpf: Option[String],
    email: Option[String],
    phone: Option[String]
  ): ConnectionIO[Client]

  def findById(id: Long): ConnectionIO[Option[Client]]

  def findByClinicId(clinicId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]]

  def findByClinicIdAndCpf(clinicId: Long, documentCpf: String): ConnectionIO[Option[Client]]

object ClientRepository:
  def make[F[_]](transactor: Transactor[F]): ClientRepository[F] = new ClientRepository[F]:

    def insert(
      clinicId: Long,
      fullName: String,
      documentCpf: Option[String],
      email: Option[String],
      phone: Option[String]
    ): ConnectionIO[Client] =
      sql"""INSERT INTO clients (clinic_id, full_name, document_cpf, email, phone)
            VALUES ($clinicId, $fullName, $documentCpf, $email, $phone)
            RETURNING id, clinic_id, full_name, document_cpf, email, phone, created_at, updated_at"""
        .query[Client]
        .unique

    def findById(id: Long): ConnectionIO[Option[Client]] =
      sql"""SELECT id, clinic_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients WHERE id = $id"""
        .query[Client]
        .option

    def findByClinicId(clinicId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]] =
      sql"""SELECT id, clinic_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients
            WHERE clinic_id = $clinicId
            ORDER BY full_name
            LIMIT $limit OFFSET $offset"""
        .query[Client]
        .to[List]

    def findByClinicIdAndCpf(clinicId: Long, documentCpf: String): ConnectionIO[Option[Client]] =
      sql"""SELECT id, clinic_id, full_name, document_cpf, email, phone, created_at, updated_at
            FROM clients
            WHERE clinic_id = $clinicId AND document_cpf = $documentCpf"""
        .query[Client]
        .option
