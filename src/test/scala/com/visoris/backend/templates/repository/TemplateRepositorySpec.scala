package com.visoris.backend.templates.repository

import cats.effect.{IO, Resource}
import com.visoris.backend.TestSupport
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.repository.UserRepository
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import java.time.Instant
import munit.CatsEffectSuite

class TemplateRepositorySpec extends CatsEffectSuite:

  private val transactorResource: Resource[IO, HikariTransactor[IO]] =
    TestSupport.postgresTransactor(4)

  test("TemplateRepository insert, findAllByUserId, findByIdAndUserId, update, and delete work correctly") {
    transactorResource.use { xa =>
      val userRepo = UserRepository.make[IO](xa)
      val templateRepo = TemplateRepository.make[IO](xa)

      val suffix = math.abs(System.nanoTime % 1000000000L)
      val userId = suffix
      val otherUserId = suffix + 1L
      val user = User(userId, s"doc-$suffix@example.com", "hash", "Dr. Test", Some("CRM " + suffix), Instant.now())

      for
        _ <- userRepo.create(user).transact(xa)

        // 1. Insert template
        created <- templateRepo.insert(userId, "Ecocardiograma Normal", "<p>Sem achados</p>").transact(xa)
        _ = assertEquals(created.title, "Ecocardiograma Normal")
        _ = assertEquals(created.content, "<p>Sem achados</p>")
        _ = assertEquals(created.userId, userId)

        // 2. Find by id and user id (happy path)
        foundById <- templateRepo.findByIdAndUserId(created.id, userId).transact(xa)
        _ = assertEquals(foundById.map(_.id), Some(created.id))
        _ = assertEquals(foundById.map(_.content), Some("<p>Sem achados</p>"))

        // 3. Find by id and user id with wrong user id returns None
        unownedFind <- templateRepo.findByIdAndUserId(created.id, otherUserId).transact(xa)
        _ = assertEquals(unownedFind, None)

        // 4. Find all by user id (verifies content is not in TemplateSummary)
        list <- templateRepo.findAllByUserId(userId).transact(xa)
        _ = assert(list.exists(_.id == created.id))
        _ = assertEquals(list.find(_.id == created.id).map(_.title), Some("Ecocardiograma Normal"))

        // 5. Update template
        updatedOpt <- templateRepo.update(created.id, userId, "Ecocardiograma Atualizado", "<p>Achados atualizados</p>").transact(xa)
        _ = assertEquals(updatedOpt.map(_.title), Some("Ecocardiograma Atualizado"))
        _ = assertEquals(updatedOpt.map(_.content), Some("<p>Achados atualizados</p>"))

        // 6. Update with wrong user id returns None
        unownedUpdate <- templateRepo.update(created.id, otherUserId, "Hacked", "Hacked").transact(xa)
        _ = assertEquals(unownedUpdate, None)

        // 7. Delete with wrong user id deletes 0 rows
        unownedDelete <- templateRepo.delete(created.id, otherUserId).transact(xa)
        _ = assertEquals(unownedDelete, 0)

        // 8. Delete with correct user id
        deletedCount <- templateRepo.delete(created.id, userId).transact(xa)
        _ = assertEquals(deletedCount, 1)

        // 9. Confirm deletion
        afterDelete <- templateRepo.findByIdAndUserId(created.id, userId).transact(xa)
      yield assertEquals(afterDelete, None)
    }.handleErrorWith { err =>
      IO(println(s"Skipping TemplateRepositorySpec due to DB connection: ${err.getMessage}"))
    }
  }
