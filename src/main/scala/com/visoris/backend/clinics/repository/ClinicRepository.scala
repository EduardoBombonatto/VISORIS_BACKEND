package com.visoris.backend.clinics.repository

import cats.syntax.all.*
import com.visoris.backend.clinics.domain.Clinic
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait ClinicRepository[F[_]]:
  def findByCnpj(cnpj: String): ConnectionIO[Option[Clinic]]

  def findById(id: Long): ConnectionIO[Option[Clinic]]

  def insertClinicIfAbsent(
    name: String,
    cnpj: Option[String],
    phone: Option[String],
    address: Option[String]
  ): ConnectionIO[Option[Clinic]]

object ClinicRepository:
  def make[F[_]](transactor: Transactor[F]): ClinicRepository[F] = new ClinicRepository[F]:

    def findByCnpj(cnpj: String): ConnectionIO[Option[Clinic]] =
      sql"""SELECT id, name, cnpj, phone, address, created_at, updated_at
            FROM clinics WHERE cnpj = $cnpj"""
        .query[Clinic]
        .option

    def findById(id: Long): ConnectionIO[Option[Clinic]] =
      sql"""SELECT id, name, cnpj, phone, address, created_at, updated_at
            FROM clinics WHERE id = $id"""
        .query[Clinic]
        .option

    def insertClinicIfAbsent(
      name: String,
      cnpj: Option[String],
      phone: Option[String],
      address: Option[String]
    ): ConnectionIO[Option[Clinic]] =
      sql"""INSERT INTO clinics (name, cnpj, phone, address)
            VALUES ($name, $cnpj, $phone, $address)
            ON CONFLICT (cnpj) WHERE cnpj IS NOT NULL DO NOTHING
            RETURNING id, name, cnpj, phone, address, created_at, updated_at"""
        .query[Clinic]
        .option

