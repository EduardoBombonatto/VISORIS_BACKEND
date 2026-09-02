package com.visoris.backend.clinics.domain

object Cnpj:

  def normalize(raw: String): String = raw.filter(_.isDigit)

  def validate(raw: String): Either[String, String] =
    val digits = normalize(raw)
    if digits.length != 14 then Left("CNPJ deve conter exatamente 14 dígitos.")
    else if digits.distinct.length == 1 then Left("CNPJ inválido.")
    else if !validCheckDigits(digits) then Left("CNPJ inválido.")
    else Right(digits)

  private def validCheckDigits(digits: String): Boolean =
    val d = digits.map(_ - '0')
    val firstWeights = Array(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    val secondWeights = Array(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)

    def checkDigitFor(weights: Array[Int], count: Int): Int =
      val sum = (0 until count).map(i => d(i) * weights(i)).sum
      val rest = sum % 11
      if rest < 2 then 0 else 11 - rest

    d(12) == checkDigitFor(firstWeights, 12) && d(13) == checkDigitFor(secondWeights, 13)
