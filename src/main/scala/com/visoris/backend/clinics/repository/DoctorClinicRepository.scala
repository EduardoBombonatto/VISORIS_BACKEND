package com.visoris.backend.clinics.repository

import cats.syntax.all.*
import com.visoris.backend.clinics.domain.Clinic
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait DoctorClinicRepository[F[_]]:
  def insert(userId: Long, clinicId: Long): ConnectionIO[Unit]
  def findByDoctor(userId: Long): ConnectionIO[List[Clinic]]

object DoctorClinicRepository:
  def make[F[_]](transactor: Transactor[F]): DoctorClinicRepository[F] = new DoctorClinicRepository[F]:

    def insert(userId: Long, clinicId: Long): ConnectionIO[Unit] =
      sql"""INSERT INTO doctor_clinics (user_id, clinic_id)
            VALUES ($userId, $clinicId)
            ON CONFLICT (user_id, clinic_id) DO NOTHING"""
        .update
        .run
        .void

    def findByDoctor(userId: Long): ConnectionIO[List[Clinic]] =
      sql"""SELECT c.id, c.name, c.cnpj, c.phone, c.address, c.created_at, c.updated_at
            FROM clinics c
            JOIN doctor_clinics dc ON dc.clinic_id = c.id
            WHERE dc.user_id = $userId
            ORDER BY c.name"""
        .query[Clinic]
        .to[List]
