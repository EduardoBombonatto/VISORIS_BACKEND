package com.visoris.backend.patients.service

import cats.effect.IO
import cats.syntax.all.*

import com.visoris.backend.patients.domain.Client
import com.visoris.backend.patients.dto.ClientRequest
import com.visoris.backend.patients.repository.ClientRepository
import doobie.*
import doobie.implicits.*
import java.time.Instant
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class ClientServiceSpec extends CatsEffectSuite:

  private given Logger[IO] = NoOpLogger[IO]
  private val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:test_clients_svc;DB_CLOSE_DELAY=-1",
    "sa",
    "",
    None
  )

  private val now = Instant.now()
  private val sampleClient = Client(
    id = 1L,
    userId = 100L,
    fullName = "Maria Souza",
    documentCpf = Some("12345678909"),
    email = Some("maria@example.com"),
    phone = Some("11987654321"),
    createdAt = now,
    updatedAt = now
  )

  private def mockClientRepo(
    existingCpf: Option[Client] = None,
    insertedClient: Client = sampleClient,
    listResult: List[Client] = List(sampleClient)
  ): ClientRepository[IO] =
    new ClientRepository[IO]:
      def insert(userId: Long, fullName: String, documentCpf: Option[String], email: Option[String], phone: Option[String]): ConnectionIO[Client] =
        insertedClient.pure[ConnectionIO]
      def findById(id: Long): ConnectionIO[Option[Client]] = Some(insertedClient).pure[ConnectionIO]
      def findByUserId(userId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]] = listResult.pure[ConnectionIO]
      def findByUserIdAndCpf(userId: Long, documentCpf: String): ConnectionIO[Option[Client]] = existingCpf.pure[ConnectionIO]

  test("create returns Validation when required fields are missing or invalid") {
    val service = ClientService.make[IO](mockClientRepo(), xa)
    val req = ClientRequest(fullName = "", documentCpf = "123", email = "bad", phone = "")

    service.create(req, userId = 100L).map {
      case Left(ClientsError.Validation(errors)) =>
        assert(errors.exists(_.field == "full_name"))
        assert(errors.exists(_.field == "document_cpf"))
        assert(errors.exists(_.field == "email"))
        assert(errors.exists(_.field == "phone"))
      case other => fail(s"Expected validation error, got $other")
    }
  }

  test("create returns ConflictCpf (409) when CPF is already registered for user") {
    val service = ClientService.make[IO](mockClientRepo(existingCpf = Some(sampleClient)), xa)
    val req = ClientRequest("Maria Souza", "12345678909", "maria@example.com", "11987654321")

    service.create(req, userId = 100L).map { res =>
      assertEquals(res, Left(ClientsError.ConflictCpf))
    }
  }

  test("create returns Client on success") {
    val service = ClientService.make[IO](mockClientRepo(), xa)
    val req = ClientRequest("Maria Souza", "12345678909", "maria@example.com", "11987654321")

    service.create(req, userId = 100L).map { res =>
      assertEquals(res, Right(sampleClient))
    }
  }

  test("listByUser returns client list on success") {
    val service = ClientService.make[IO](mockClientRepo(), xa)
    service.listByUser(userId = 100L, limit = 50L, offset = 0L).map { res =>
      assertEquals(res, Right(List(sampleClient)))
    }
  }
