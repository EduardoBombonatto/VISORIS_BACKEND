package com.visoris.backend.reports.repository

import cats.syntax.all.*
import com.visoris.backend.reports.domain.ReportImage
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.given
import doobie.util.transactor.Transactor

trait ReportImageRepository[F[_]]:
  def insert(
    reportId: Long,
    imageUrl: String,
    caption: Option[String],
    displayOrder: Int
  ): ConnectionIO[ReportImage]

  def insertBatch(
    reportId: Long,
    images: List[(String, Option[String], Int)]
  ): ConnectionIO[List[ReportImage]]

  def findByReportId(reportId: Long): ConnectionIO[List[ReportImage]]

object ReportImageRepository:
  def make[F[_]](transactor: Transactor[F]): ReportImageRepository[F] = new ReportImageRepository[F]:

    def insert(
      reportId: Long,
      imageUrl: String,
      caption: Option[String],
      displayOrder: Int
    ): ConnectionIO[ReportImage] =
      sql"""INSERT INTO report_images (report_id, image_url, caption, display_order)
            VALUES ($reportId, $imageUrl, $caption, $displayOrder)
            RETURNING id, report_id, image_url, caption, display_order, created_at"""
        .query[ReportImage]
        .unique

    def insertBatch(
      reportId: Long,
      images: List[(String, Option[String], Int)]
    ): ConnectionIO[List[ReportImage]] =
      images.traverse { case (imageUrl, caption, displayOrder) =>
        insert(reportId, imageUrl, caption, displayOrder)
      }

    def findByReportId(reportId: Long): ConnectionIO[List[ReportImage]] =
      sql"""SELECT id, report_id, image_url, caption, display_order, created_at
            FROM report_images
            WHERE report_id = $reportId
            ORDER BY display_order ASC, id ASC"""
        .query[ReportImage]
        .to[List]
