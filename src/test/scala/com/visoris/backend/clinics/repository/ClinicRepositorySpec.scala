package com.visoris.backend.clinics.repository

import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.all.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway

class ClinicRepositorySpec extends CatsEffectSuite:

  private val dbUrl  = sys.env.getOrElse("DB_URL", "jdbc:postgresql://localhost:5432/visoris_db")
  private val dbUser = sys.env.getOrElse("DB_USER", "postgres")
  private val dbPass = sys.env.getOrElse("DB_PASSWORD", "Visoris@123.")

  private val transactorResource: Resource[IO, HikariTransactor[IO]] =
    import doobie.util.ExecutionContexts
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](4)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver", dbUrl, dbUser, dbPass, ce
      )
      _ <- Resource.eval(runMigrations(xa))
    yield xa

  private def runMigrations(xa: HikariTransactor[IO]): IO[Unit] =
    xa.configure { dataSource =>
      Sync[IO].delay { Flyway.configure().dataSource(dataSource).load().migrate(); () }
    }

  private def uniqueCnpj: String = f"${math.abs(System.nanoTime) % 100000000000000L}%014d"

  private def countByCnpj(xa: HikariTransactor[IO], cnpj: String): IO[Long] =
    sql"SELECT count(*) FROM clinics WHERE cnpj = $cnpj".query[Long].unique.transact(xa)

  test("insertClinicIfAbsent inserts a clinic and findByCnpj returns it") {
    transactorResource.use { xa =>
      val repo = ClinicRepository.make[IO](xa)
      val cnpj = uniqueCnpj
      for
        inserted <- repo.insertClinicIfAbsent("Clínica Teste", Some(cnpj), Some("11"), Some("Av")).transact(xa)
        found <- repo.findByCnpj(cnpj).transact(xa)
      yield
        assertEquals(inserted.map(_.name), Some("Clínica Teste"))
        assertEquals(inserted.flatMap(_.cnpj), Some(cnpj))
        assertEquals(found.map(_.id), inserted.map(_.id))
        assertEquals(found.flatMap(_.phone), Some("11"))
    }
  }

  test("insertClinicIfAbsent with NULL cnpj always inserts a new row") {
    transactorResource.use { xa =>
      val repo = ClinicRepository.make[IO](xa)
      for
        a <- repo.insertClinicIfAbsent("Sem CNPJ A", None, None, None).transact(xa)
        b <- repo.insertClinicIfAbsent("Sem CNPJ B", None, None, None).transact(xa)
      yield
        assert(a.isDefined)
        assert(b.isDefined)
        assert(a.map(_.id) != b.map(_.id))
    }
  }

  test("insertClinicIfAbsent with an existing CNPJ returns None and keeps exactly one row") {
    transactorResource.use { xa =>
      val repo = ClinicRepository.make[IO](xa)
      val cnpj = uniqueCnpj
      for
        first <- repo.insertClinicIfAbsent("Clínica Duplicada", Some(cnpj), None, None).transact(xa)
        second <- repo.insertClinicIfAbsent("Clínica Duplicada 2", Some(cnpj), None, None).transact(xa)
        count <- countByCnpj(xa, cnpj)
      yield
        assert(first.isDefined)
        assertEquals(second, None)
        assertEquals(count, 1L)
    }
  }

  test("two concurrent inserts for the same CNPJ yield exactly one clinic row") {
    transactorResource.use { xa =>
      val repo = ClinicRepository.make[IO](xa)
      val cnpj = uniqueCnpj
      for
        results <- List(
          repo.insertClinicIfAbsent("Concorrência A", Some(cnpj), None, None).transact(xa),
          repo.insertClinicIfAbsent("Concorrência B", Some(cnpj), None, None).transact(xa)
        ).parSequence
        count <- countByCnpj(xa, cnpj)
      yield
        assertEquals(results.flatten.length, 1)
        assertEquals(count, 1L)
    }
  }
