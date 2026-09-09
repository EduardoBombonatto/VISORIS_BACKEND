package com.visoris.backend.patients.repository

import com.visoris.backend.patients.domain.PatientType
import doobie.postgres.implicits.pgEnumString
import doobie.util.meta.Meta
import io.circe.Json
import io.circe.parser.parse
import org.postgresql.util.PGobject

object DoobieInstances:

  given jsonMeta: Meta[Json] =
    Meta.Advanced.other[PGobject]("jsonb").timap[Json] { o =>
      parse(Option(o.getValue).getOrElse("{}")).fold(throw _, identity)
    } { json =>
      val o = new PGobject
      o.setType("jsonb")
      o.setValue(json.noSpaces)
      o
    }

  given patientTypeMeta: Meta[PatientType] =
    pgEnumString[PatientType](
      "patient_type_enum",
      s => PatientType.fromString(s).fold(err => throw new IllegalArgumentException(err), identity),
      _.toString
    )
