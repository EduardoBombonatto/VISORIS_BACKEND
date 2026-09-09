package com.visoris.backend.patients.repository

import com.visoris.backend.patients.domain.{Patient, PatientType}
import com.visoris.backend.patients.repository.DoobieInstances.given
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor
import io.circe.Json
import java.time.LocalDate

trait PatientRepository[F[_]]:
  def insert(
    clientId: Long,
    name: String,
    patientType: PatientType,
    birthDate: Option[LocalDate],
    biologicalDetails: Json
  ): ConnectionIO[Patient]

  def findById(id: Long): ConnectionIO[Option[Patient]]

  def findByClientId(clientId: Long, limit: Long, offset: Long): ConnectionIO[List[Patient]]

object PatientRepository:
  def make[F[_]](transactor: Transactor[F]): PatientRepository[F] = new PatientRepository[F]:

    def insert(
      clientId: Long,
      name: String,
      patientType: PatientType,
      birthDate: Option[LocalDate],
      biologicalDetails: Json
    ): ConnectionIO[Patient] =
      sql"""INSERT INTO patients (client_id, name, patient_type, birth_date, biological_details)
            VALUES ($clientId, $name, $patientType, $birthDate, $biologicalDetails)
            RETURNING id, client_id, name, patient_type, birth_date, biological_details, created_at"""
        .query[Patient]
        .unique

    def findById(id: Long): ConnectionIO[Option[Patient]] =
      sql"""SELECT id, client_id, name, patient_type, birth_date, biological_details, created_at
            FROM patients WHERE id = $id"""
        .query[Patient]
        .option

    def findByClientId(clientId: Long, limit: Long, offset: Long): ConnectionIO[List[Patient]] =
      sql"""SELECT id, client_id, name, patient_type, birth_date, biological_details, created_at
            FROM patients
            WHERE client_id = $clientId
            ORDER BY name
            LIMIT $limit OFFSET $offset"""
        .query[Patient]
        .to[List]
