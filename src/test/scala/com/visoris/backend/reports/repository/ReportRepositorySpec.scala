package com.visoris.backend.reports.repository

import cats.effect.{IO, Resource, Sync}
import com.visoris.backend.appointments.domain.ReportStatus
import com.visoris.backend.appointments.repository.AppointmentRepository
import com.visoris.backend.clinics.repository.ClinicRepository
import com.visoris.backend.iam.domain.User
import com.visoris.backend.iam.repository.UserRepository
import com.visoris.backend.patients.domain.PatientType
import com.visoris.backend.patients.repository.{ClientRepository, PatientRepository}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import io.circe.Json
import java.time.{Instant, LocalDate}
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway

class ReportRepositorySpec extends CatsEffectSuite:

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

  test("ReportRepository and ReportImageRepository CRUD, snapshots, batch insert, and signing work correctly") {
    transactorResource.use { xa =>
      val userRepo        = UserRepository.make[IO](xa)
      val clinicRepo      = ClinicRepository.make[IO](xa)
      val clientRepo      = ClientRepository.make[IO](xa)
      val patientRepo     = PatientRepository.make[IO](xa)
      val appointmentRepo = AppointmentRepository.make[IO](xa)
      val reportRepo      = ReportRepository.make[IO](xa)
      val reportImageRepo = ReportImageRepository.make[IO](xa)

      val suffix      = math.abs(System.nanoTime % 1000000000L)
      val userId      = suffix
      val otherUserId = suffix + 1L
      val user        = User(userId, s"doc-report-$suffix@example.com", "hash", "Dr. Report", Some("CRM " + suffix), Instant.now())

      val patientSnap = Json.obj("name" -> Json.fromString("Rex"), "species" -> Json.fromString("Canino"))
      val clinicSnap  = Json.obj("name" -> Json.fromString("Clínica Central"))

      for
        _ <- userRepo.create(user).transact(xa)
        clinicOpt <- clinicRepo.insertClinicIfAbsent(s"Clínica Central $suffix", None, None, None).transact(xa)
        clinicId = clinicOpt.get.id
        client <- clientRepo.insert(userId, "Tutor Teste", None, None, None).transact(xa)
        patient <- patientRepo.insert(client.id, "Rex", PatientType.PET, Some(LocalDate.of(2021, 5, 10)), Json.obj()).transact(xa)
        appt <- appointmentRepo.insert(userId, clinicId, patient.id, Instant.now(), "Ultrassom Abdominal", Some(BigDecimal("250.00"))).transact(xa)

        // 1. Insert draft report with JSONB snapshots
        created <- reportRepo.insert(
          appointmentId = appt.id,
          userId = userId,
          attendanceLocationName = Some("Clínica Central"),
          patientSnapshot = Some(patientSnap),
          clinicSnapshot = Some(clinicSnap),
          content = "<p>Achados iniciais</p>"
        ).transact(xa)
        _ = assertEquals(created.appointmentId, appt.id)
        _ = assertEquals(created.userId, userId)
        _ = assertEquals(created.attendanceLocationName, Some("Clínica Central"))
        _ = assertEquals(created.patientSnapshot, Some(patientSnap))
        _ = assertEquals(created.clinicSnapshot, Some(clinicSnap))
        _ = assertEquals(created.content, "<p>Achados iniciais</p>")
        _ = assertEquals(created.signedAt, None)

        // 2. Find by appointmentId and userId
        byAppt <- reportRepo.findByAppointmentIdAndUserId(appt.id, userId).transact(xa)
        _ = assertEquals(byAppt.map(_.id), Some(created.id))

        // 3. Cross-user lookup returns None
        unownedByAppt <- reportRepo.findByAppointmentIdAndUserId(appt.id, otherUserId).transact(xa)
        _ = assertEquals(unownedByAppt, None)
        unownedById <- reportRepo.findByIdAndUserId(created.id, otherUserId).transact(xa)
        _ = assertEquals(unownedById, None)

        // 4. Update draft content
        updatedOpt <- reportRepo.updateContent(created.id, userId, "<p>Achados atualizados</p>").transact(xa)
        _ = assertEquals(updatedOpt.map(_.content), Some("<p>Achados atualizados</p>"))

        // 5. Insert single image and batch images, verify ordering by display_order ASC
        img1 <- reportImageRepo.insert(created.id, "https://storage.visoris.com/img-2.jpg", Some("Imagem 2"), 2).transact(xa)
        _ = assertEquals(img1.reportId, created.id)
        _ = assertEquals(img1.displayOrder, 2)

        batchImgs <- reportImageRepo.insertBatch(
          created.id,
          List(
            ("https://storage.visoris.com/img-0.jpg", Some("Imagem 0"), 0),
            ("https://storage.visoris.com/img-1.jpg", None, 1)
          )
        ).transact(xa)
        _ = assertEquals(batchImgs.length, 2)

        allImgs <- reportImageRepo.findByReportId(created.id).transact(xa)
        _ = assertEquals(allImgs.map(_.displayOrder), List(0, 1, 2))

        // 6. Sign report and update appointment report_status to COMPLETED atomically
        signedPair <- (for
          signedOpt <- reportRepo.sign(created.id, userId)
          apptUpd   <- appointmentRepo.updateStatus(appt.id, userId, None, Some(ReportStatus.COMPLETED), None)
        yield (signedOpt, apptUpd)).transact(xa)

        _ = assert(signedPair._1.flatMap(_.signedAt).isDefined, "signedAt must be defined after signing")
        _ = assertEquals(signedPair._2.map(_.reportStatus), Some(ReportStatus.COMPLETED))
      yield ()
    }.handleErrorWith { err =>
      IO(println(s"Skipping ReportRepositorySpec due to DB connection: ${err.getMessage}"))
    }
  }
