package com.visoris.backend.reports.service

import com.visoris.backend.reports.dto.{CreateReportRequest, ReportImageRequest, UpdateReportRequest}
import munit.FunSuite

class ReportServiceSpec extends FunSuite:

  test("validateCreateRequest succeeds when appointmentId > 0 and content is defined") {
    val req = CreateReportRequest(Some(42L), Some("<p>Conteúdo</p>"))
    assertEquals(ReportService.validateCreateRequest(req).map(_.appointmentId), Right(42L))
  }

  test("validateCreateRequest fails when appointmentId is missing or <= 0") {
    val req = CreateReportRequest(None, Some("<p>Conteúdo</p>"))
    assert(ReportService.validateCreateRequest(req).isLeft)
  }

  test("validateCreateRequest fails when content is missing") {
    val req = CreateReportRequest(Some(42L), None)
    assert(ReportService.validateCreateRequest(req).isLeft)
  }

  test("validateUpdateRequest fails when content is None") {
    val req = UpdateReportRequest(None)
    assert(ReportService.validateUpdateRequest(req).isLeft)
  }

  test("validateImageRequest trims imageUrl and validates lengths and displayOrder") {
    val valid = ReportImageRequest("  https://storage.visoris.com/img.jpg  ", Some("  Legenda  "), 0)
    assertEquals(
      ReportService.validateImageRequest(valid),
      Right(ReportImageRequest("https://storage.visoris.com/img.jpg", Some("Legenda"), 0))
    )

    val emptyUrl = ReportImageRequest("   ", None, 0)
    assert(ReportService.validateImageRequest(emptyUrl).isLeft)

    val negativeOrder = ReportImageRequest("https://storage.visoris.com/img.jpg", None, -1)
    assert(ReportService.validateImageRequest(negativeOrder).isLeft)

    val longCaption = ReportImageRequest("https://storage.visoris.com/img.jpg", Some("a" * 501), 0)
    assert(ReportService.validateImageRequest(longCaption).isLeft)
  }

  test("validateImageRequest rejects unsafe schemes and loopback/private/metadata hosts") {
    val unsafeUrls = List(
      "javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "file:///etc/passwd",
      "http://localhost/img.png",
      "http://127.0.0.1/img.png",
      "http://[::1]/img.png",
      "http://10.0.0.1/img.png",
      "http://192.168.1.1/img.png",
      "http://metadata.google.internal/computeMetadata/v1/"
    )
    unsafeUrls.foreach { url =>
      assert(ReportService.validateImageRequest(ReportImageRequest(url, None, 0)).isLeft, s"Expected $url to be rejected")
    }
  }
