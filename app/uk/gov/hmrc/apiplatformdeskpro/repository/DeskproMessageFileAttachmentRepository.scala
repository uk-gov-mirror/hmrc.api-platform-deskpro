/*
 * Copyright 2025 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apiplatformdeskpro.repository

import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}

import uk.gov.hmrc.apiplatformdeskpro.domain.models.mongo.DeskproMessageFileAttachment
import uk.gov.hmrc.apiplatformdeskpro.utils.ApplicationLogger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow

@Singleton
class DeskproMessageFileAttachmentRepository @Inject() (mongo: MongoComponent, val clock: Clock)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[DeskproMessageFileAttachment](
      collectionName = "deskproMessageFileAttachment",
      mongoComponent = mongo,
      domainFormat = DeskproMessageFileAttachment.format,
      indexes = Seq(
        IndexModel(
          ascending("ticketId", "messageId"),
          IndexOptions()
            .name("ticketId_messageId_Index")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("attachments.fileReference"),
          IndexOptions()
            .name("fileReferencesIndex")
            .unique(false)
            .background(true)
        ),
        IndexModel(
          ascending("createdAt"),
          IndexOptions().name("createdAt_ttl_idx")
            .background(true)
            .expireAfter(72, TimeUnit.HOURS)
        )
      ),
      replaceIndexes = true
    )
    with ClockNow
    with ApplicationLogger
    with MongoJavatimeFormats.Implicits {

  override lazy val requiresTtlIndex: Boolean = true

  def create(response: DeskproMessageFileAttachment): Future[DeskproMessageFileAttachment] = {
    collection.insertOne(response).toFuture().map(_ => response)
  }

  def fetchByFileReference(fileReference: String): Future[Option[DeskproMessageFileAttachment]] = {
    collection.find(equal("attachments.fileReference", fileReference)).headOption()
  }
}
