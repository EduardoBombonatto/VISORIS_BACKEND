package com.visoris.backend.iam.repository

import com.visoris.backend.iam.domain.User
import doobie.ConnectionIO

trait UserRepository:
  def create(user: User): ConnectionIO[Unit]
  def findByEmail(email: String): ConnectionIO[Option[User]]
  def findByProfessionalDocument(doc: String): ConnectionIO[Option[User]]
