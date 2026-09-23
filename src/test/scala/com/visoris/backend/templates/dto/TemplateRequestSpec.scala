package com.visoris.backend.templates.dto

import munit.FunSuite

class TemplateRequestSpec extends FunSuite:

  test("CreateTemplateRequest sanitization trims title") {
    val req = CreateTemplateRequest("  Ecocardiograma Normal  ", "Conteúdo rico")
    assertEquals(req.sanitized.title, "Ecocardiograma Normal")
    assertEquals(req.sanitized.content, "Conteúdo rico")
  }

  test("UpdateTemplateRequest sanitization trims title if present") {
    val req = UpdateTemplateRequest(Some("  Novo Título  "), Some("<p>Novo HTML</p>"))
    assertEquals(req.sanitized.title, Some("Novo Título"))
    assertEquals(req.sanitized.content, Some("<p>Novo HTML</p>"))
  }

  test("UpdateTemplateRequest sanitization preserves None") {
    val req = UpdateTemplateRequest(None, None)
    assertEquals(req.sanitized.title, None)
    assertEquals(req.sanitized.content, None)
  }
