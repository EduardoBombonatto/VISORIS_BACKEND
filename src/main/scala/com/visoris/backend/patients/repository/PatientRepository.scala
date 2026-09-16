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

  def update(
    id: Long,
    name: String,
    patientType: PatientType,
    birthDate: Option[LocalDate],
    biologicalDetails: Json
  ): ConnectionIO[Option[Patient]]

  def delete(id: Long): ConnectionIO[Int]

  def hasAppointments(patientId: Long): ConnectionIO[Boolean]

  def findOwnerUserId(patientId: Long): ConnectionIO[Option[Long]]

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

    def update(
      id: Long,
      name: String,
      patientType: PatientType,
      birthDate: Option[LocalDate],
      biologicalDetails: Json
    ): ConnectionIO[Option[Patient]] =
      sql"""UPDATE patients
            SET name = $name,
                patient_type = $patientType,
                birth_date = $birthDate,
                biological_details = $biologicalDetails
            WHERE id = $id
            RETURNING id, client_id, name, patient_type, birth_date, biological_details, created_at"""
        .query[Patient]
        .option

    def delete(id: Long): ConnectionIO[Int] =
      sql"""DELETE FROM patients WHERE id = $id""".update.run

    def hasAppointments(patientId: Long): ConnectionIO[Boolean] =
      sql"""SELECT EXISTS (SELECT 1 FROM appointments WHERE patient_id = $patientId)"""
        .query[Boolean]
        .unique

    def findOwnerUserId(patientId: Long): ConnectionIO[Option[Long]] =
      sql"""SELECT c.user_id
            FROM patients p
            JOIN clients c ON c.id = p.client_id
            WHERE p.id = $patientId"""
        .query[Long]
        .option
