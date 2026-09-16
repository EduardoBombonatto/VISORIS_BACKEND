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
    conflicts: List[Client] = Nil,
    insertedClient: Client = sampleClient,
    listResult: List[Client] = List(sampleClient),
    hasPatientsResult: Boolean = false,
    findByIdResult: Option[Client] = Some(sampleClient)
  ): ClientRepository[IO] =
    new ClientRepository[IO]:
      def insert(userId: Long, fullName: String, documentCpf: Option[String], email: Option[String], phone: Option[String]): ConnectionIO[Client] =
        insertedClient.pure[ConnectionIO]
      def findById(id: Long): ConnectionIO[Option[Client]] = findByIdResult.pure[ConnectionIO]
      def findByUserId(userId: Long, limit: Long, offset: Long): ConnectionIO[List[Client]] = listResult.pure[ConnectionIO]
      def findByUserIdAndCpf(userId: Long, documentCpf: String): ConnectionIO[Option[Client]] = existingCpf.pure[ConnectionIO]
      def findConflicts(userId: Long, documentCpf: String, email: String, phone: String): ConnectionIO[List[Client]] =
        (existingCpf.toList ++ conflicts).pure[ConnectionIO]
      def findConflictsExcluding(id: Long, userId: Long, documentCpf: String, email: String, phone: String): ConnectionIO[List[Client]] =
        conflicts.pure[ConnectionIO]
      def update(id: Long, userId: Long, fullName: String, documentCpf: Option[String], email: Option[String], phone: Option[String]): ConnectionIO[Option[Client]] =
        Some(insertedClient.copy(fullName = fullName)).pure[ConnectionIO]
      def delete(id: Long, userId: Long): ConnectionIO[Int] = 1.pure[ConnectionIO]
      def hasPatients(clientId: Long): ConnectionIO[Boolean] = hasPatientsResult.pure[ConnectionIO]

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

  test("update returns NotFound when client does not exist or not owned") {
    val service = ClientService.make[IO](mockClientRepo(findByIdResult = None), xa)
    val req = ClientRequest("Maria Souza", "12345678909", "maria@example.com", "11987654321")

    service.update(clientId = 999L, req, userId = 100L).map { res =>
      assertEquals(res, Left(ClientsError.NotFound))
    }
  }

  test("update returns ConflictCpf when new CPF collides with another client") {
    val colliding = sampleClient.copy(id = 2L, documentCpf = Some("12345678909"))
    val service = ClientService.make[IO](mockClientRepo(conflicts = List(colliding)), xa)
    val req = ClientRequest("Maria Souza", "12345678909", "maria@example.com", "11987654321")

    service.update(clientId = 1L, req, userId = 100L).map { res =>
      assertEquals(res, Left(ClientsError.ConflictCpf))
    }
  }

  test("update returns Client on success") {
    val service = ClientService.make[IO](mockClientRepo(), xa)
    val req = ClientRequest("Maria Atualizada", "12345678909", "maria@example.com", "11987654321")

    service.update(clientId = 1L, req, userId = 100L).map { res =>
      assertEquals(res, Right(sampleClient.copy(fullName = "Maria Atualizada")))
    }
  }

  test("delete succeeds and deletes client even when client has linked patients (cascade)") {
    val service = ClientService.make[IO](mockClientRepo(hasPatientsResult = true), xa)

    service.delete(clientId = 1L, userId = 100L).map { res =>
      assertEquals(res, Right(()))
    }
  }

  test("delete returns NotFound (404) when client does not exist") {
    val service = ClientService.make[IO](mockClientRepo(findByIdResult = None), xa)

    service.delete(clientId = 999L, userId = 100L).map { res =>
      assertEquals(res, Left(ClientsError.NotFound))
    }
  }

  test("delete returns Unit on success") {
    val service = ClientService.make[IO](mockClientRepo(hasPatientsResult = false), xa)

    service.delete(clientId = 1L, userId = 100L).map { res =>
      assertEquals(res, Right(()))
    }
  }
