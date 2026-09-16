/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatformdeskpro.connector

import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status._
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.mvc.MultipartFormData
import uk.gov.hmrc.apiplatformdeskpro.config.AppConfig
import uk.gov.hmrc.apiplatformdeskpro.domain.models._
import uk.gov.hmrc.apiplatformdeskpro.domain.models.connector._
import uk.gov.hmrc.apiplatformdeskpro.domain.models.mongo.BlobDetails
import uk.gov.hmrc.apiplatformdeskpro.utils.ApplicationLogger
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.http.metrics.common.API

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow

class DeskproConnector @Inject() (http: HttpClientV2, config: AppConfig, metrics: ConnectorMetrics, val clock: Clock)(implicit val ec: ExecutionContext)
    extends ApplicationLogger with ClockNow {

  val DESKPRO_QUERY_HEADER = "X-Deskpro-Api-Get-Query-Body"

  // query header value required for the body of the POST request to be treated as a query
  val DESKPRO_QUERY_MODE_ON = "1"

  lazy val serviceBaseUrl: String = config.deskproUrl
  val api: API                    = API("deskpro")

  def createTicket(deskproTicket: CreateDeskproTicket)(implicit hc: HeaderCarrier): Future[Either[DeskproTicketCreationFailed, DeskproTicketCreated]] = metrics.record(api) {
    http.post(url"${requestUrl("/api/v2/tickets")}")
      .withProxy
      .withBody(Json.toJson(deskproTicket))
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case CREATED      =>
            logger.info(s"Deskpro ticket '${deskproTicket.subject}' created successfully")
            Right(response.json.as[DeskproTicketCreated])
          case BAD_REQUEST  =>
            logger.warn(s"Deskpro ticket '${deskproTicket.subject}' failed due to bad request")
            val isDuplicate = (Json.parse(response.body) \\ "code").contains(JsString("dupe_ticket"))
            if (isDuplicate) Left(DeskproTicketCreatedDuplicate())
            else Left(DeskproTicketCreationError("Bad request"))
          case UNAUTHORIZED =>
            logger.error(s"Deskpro ticket creation failed as unauthorized for: ${deskproTicket.subject}")
            Left(DeskproTicketCreationError("Missing authorization"))
          case _            =>
            val errorMessage = (Json.parse(response.body) \\ "message").mkString(",")
            logger.error(s"Deskpro ticket creation failed with message $errorMessage for: ${deskproTicket.subject}")
            Left(DeskproTicketCreationError("Unknown reason"))
        }
      )
  }

  def createPerson(userId: UserId, name: String, email: String)(implicit hc: HeaderCarrier): Future[DeskproPersonCreationResult] = metrics.record(api) {
    http
      .post(url"${requestUrl("/api/v2/people")}")
      .withProxy
      .withBody(Json.toJson(DeskproPerson(name, email)))
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case CREATED                                             =>
            logger.info(s"Deskpro person creation '$userId' success")
            DeskproPersonCreationSuccess
          case BAD_REQUEST if response.body.contains("dupe_email") =>
            logger.info(s"Deskpro person creation '$userId' duplicate email warning")
            DeskproPersonExistsInDeskpro
          case BAD_REQUEST                                         =>
            logger.error(s"Deskpro person creation '$userId' failed Bad request other errorCode")
            DeskproPersonCreationFailure
          case _                                                   =>
            logger.error(s"Deskpro person creation '$userId' failed status: ${response.status}")
            DeskproPersonCreationFailure
        }
      )
  }

  def updatePerson(personId: Int, name: String)(implicit hc: HeaderCarrier): Future[DeskproPersonUpdateResult] = metrics.record(api) {
    http
      .put(url"${requestUrl(s"/api/v2/people/${personId}")}")
      .withProxy
      .withBody(Json.toJson(DeskproPersonUpdate(name)))
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case NO_CONTENT  =>
            logger.info(s"Deskpro person update '$personId' success")
            DeskproPersonUpdateSuccess
          case BAD_REQUEST =>
            logger.error(s"Deskpro person update '$personId' failed Bad request")
            DeskproPersonUpdateFailure
          case _           =>
            logger.error(s"Deskpro person update '$personId' failed status: ${response.status}")
            DeskproPersonUpdateFailure
        }
      )
  }

  def getPeopleByOrganisationId(organisationId: OrganisationId, pageWanted: Int = 1)(implicit hc: HeaderCarrier): Future[DeskproPeopleResponse] = metrics.record(api) {
    val queryParams = Seq("organization" -> organisationId, "count" -> 200, "page" -> pageWanted)
    http
      .get(url"${requestUrl(s"/api/v2/people")}?$queryParams")
      .withProxy
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[DeskproPeopleResponse]
  }

  def markPersonInactive(personId: Int)(implicit hc: HeaderCarrier): Future[DeskproPersonUpdateResult] = metrics.record(api) {
    http
      .put(url"${requestUrl(s"/api/v2/people/$personId")}")
      .withProxy
      .withBody(Json.toJson(createDeskproInactivePerson))
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case NO_CONTENT  =>
            logger.info(s"Deskpro mark person inactive '$personId' success")
            DeskproPersonUpdateSuccess
          case BAD_REQUEST =>
            logger.error(s"Deskpro mark person inactive '$personId' failed Bad request")
            DeskproPersonUpdateFailure
          case _           =>
            logger.error(s"Deskpro mark person inactive '$personId' failed status: ${response.status}")
            DeskproPersonUpdateFailure
        }
      )
  }

  private def createDeskproInactivePerson: DeskproInactivePerson =
    DeskproInactivePerson(
      Map(
        config.deskproInactive        -> "1",
        config.deskproInactivatedDate -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(now)
      )
    )

  def getOrganisationById(organisationId: OrganisationId)(implicit hc: HeaderCarrier): Future[DeskproOrganisationWrapperResponse] = metrics.record(api) {
    http
      .get(url"${requestUrl(s"/api/v2/organizations/$organisationId")}")
      .withProxy
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[DeskproOrganisationWrapperResponse]
  }

  def getOrganisationsForPersonEmail(email: LaxEmailAddress)(implicit hc: HeaderCarrier): Future[DeskproLinkedOrganisationWrapper] = {
    queryPersonForEmail(Json.toJson(GetOrganisationByPersonEmailRequest(email.text)))
  }

  def getPersonForEmail(email: LaxEmailAddress)(implicit hc: HeaderCarrier): Future[DeskproLinkedOrganisationWrapper] = {
    queryPersonForEmail(Json.toJson(GetPersonByEmailRequest(email.text)))
  }

  private def queryPersonForEmail(requestBody: JsValue)(implicit hc: HeaderCarrier): Future[DeskproLinkedOrganisationWrapper] = {
    metrics.record(api) {
      http
        .post(url"${requestUrl(s"/api/v2/people")}")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .setHeader(DESKPRO_QUERY_HEADER -> DESKPRO_QUERY_MODE_ON)
        .withBody(requestBody)
        .execute[DeskproLinkedOrganisationWrapper]
    }
  }

  def getTicketsForPersonId(personId: Int, maybeStatus: Option[String], orderBy: String = "date_status", orderDir: String = "desc", pageWanted: Int = 1)(implicit hc: HeaderCarrier)
      : Future[DeskproTicketsWrapperResponse] =
    metrics.record(api) {
      val queryParams = maybeStatus match {
        case Some(status) => Seq("person" -> personId, "status" -> status, "count" -> 200, "page" -> pageWanted, "order_by" -> orderBy, "group_sort" -> orderDir)
        case _            => Seq("person" -> personId, "count" -> 200, "page" -> pageWanted, "order_by" -> orderBy, "group_sort" -> orderDir)
      }
      http
        .get(url"${requestUrl("/api/v2/tickets")}?$queryParams")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .execute[DeskproTicketsWrapperResponse]
    }

  def fetchTicket(ticketId: Int)(implicit hc: HeaderCarrier): Future[Option[DeskproTicketWrapperResponse]] = metrics.record(api) {
    http
      .get(url"${requestUrl(s"/api/v2/tickets/$ticketId")}")
      .withProxy
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[Option[DeskproTicketWrapperResponse]]

  }

  def updateTicketStatus(ticketId: Int, status: TicketStatus)(implicit hc: HeaderCarrier): Future[DeskproTicketUpdateResult] = metrics.record(api) {
    http
      .put(url"${requestUrl(s"/api/v2/tickets/$ticketId")}")
      .withProxy
      .withBody(Json.toJson(UpdateTicketStatusRequest(status.value)))
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case NO_CONTENT =>
            logger.info(s"Deskpro update status for ticket '$ticketId' success")
            DeskproTicketUpdateSuccess
          case NOT_FOUND  =>
            logger.warn(s"Deskpro update status for ticket '$ticketId' failed Not found")
            DeskproTicketUpdateNotFound
          case _          =>
            logger.error(s"Deskpro update status for ticket '$ticketId' response status: ${response.status}")
            DeskproTicketUpdateFailure
        }
      )

  }

  def deleteTicket(ticketId: Int)(implicit hc: HeaderCarrier): Future[DeskproTicketUpdateResult] = metrics.record(api) {
    http
      .delete(url"${requestUrl(s"/api/v2/tickets/$ticketId")}")
      .withProxy
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case OK        =>
            logger.info(s"Deleted Deskpro ticket '$ticketId' successfully")
            DeskproTicketUpdateSuccess
          case NOT_FOUND =>
            logger.warn(s"Failed to delete Deskpro ticket '$ticketId. Ticket not found")
            DeskproTicketUpdateNotFound
          case _         =>
            logger.error(s"Failed to delete Deskpro ticket '$ticketId. Status: ${response.status}")
            DeskproTicketUpdateFailure
        }
      )

  }

  def getTicketMessages(ticketId: Int, orderBy: String = "date_created", orderDir: String = "desc", pageWanted: Int = 1)(implicit hc: HeaderCarrier)
      : Future[DeskproMessagesWrapperResponse] = metrics.record(api) {
    val queryParams = Seq("count" -> 200, "page" -> pageWanted, "order_by" -> orderBy, "order_dir" -> orderDir)
    metrics.record(api) {
      http
        .get(url"${requestUrl(s"/api/v2/tickets/$ticketId/messages")}?$queryParams")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .execute[DeskproMessagesWrapperResponse]
    }
  }

  def batchFetchTicket(ticketId: Int, orderBy: String = "date_created", orderDir: String = "desc", pageWanted: Int = 1)(implicit hc: HeaderCarrier): Future[BatchResponse] =
    metrics.record(api) {
      val batchRequest = BatchRequest(
        BatchTicketRequest(
          BatchRequestDetails(s"/api/v2/tickets/$ticketId"),
          BatchRequestDetails(s"/api/v2/tickets/$ticketId/messages?order_by=$orderBy&order_dir=$orderDir&count=200&page=$pageWanted"),
          BatchRequestDetails(s"/api/v2/tickets/$ticketId/attachments?count=200")
        )
      )
      http
        .post(url"${requestUrl("/api/v2/batch")}")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .withBody(Json.toJson(batchRequest))
        .execute[BatchResponse]
    }

  def createBlob(fileName: String, fileType: String, fileSize: Long, fileReference: String, src: Source[ByteString, _])(implicit hc: HeaderCarrier)
      : Future[DeskproBlobCreationResult] = {

    val filePart: MultipartFormData.Part[Source[ByteString, _]] = MultipartFormData.FilePart(
      "file",
      fileName,
      Some(fileType),
      src,
      fileSize
    )

    // Note - we're setting the content-length here because for some reason it's not always being
    // set correctly by the framework (especially for larger files) and then Deskpro rejects the request.
    val contentLength = fileName.length() + fileType.length() + fileSize + 122

    metrics.record(api) {
      http
        .post(url"${requestUrl("/api/v2/blobs/temp")}")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .setHeader("content-length" -> contentLength.toString())
        .withBody(Source(Seq(filePart)))
        .execute[HttpResponse]
        .map(response =>
          response.status match {
            case CREATED      =>
              logger.info(s"Deskpro blob for file '${fileReference}' created successfully")
              val blobResponse = response.json.as[DeskproCreateBlobWrapperResponse]
              DeskproBlobCreationSuccess(blobResponse.data)
            case BAD_REQUEST  =>
              logger.error(s"Deskpro blob for file '${fileReference}' failed due to bad request")
              DeskproBlobCreationFailure("Failed to upload file")
            case UNAUTHORIZED =>
              logger.error(s"Deskpro blob for file '${fileReference}' failed as unauthorized")
              DeskproBlobCreationFailure("Failed to upload file")
            case _            =>
              logger.error(s"Deskpro blob for file '${fileReference}' failed with status code ${response.status}")
              DeskproBlobCreationFailure("Failed to upload file")
          }
        )
    }
  }

  def createMessage(ticketId: Int, userEmail: String, message: String)(implicit hc: HeaderCarrier): Future[DeskproCreateMessageWrapperResponse] = metrics.record(api) {
    http
      .post(url"${requestUrl(s"/api/v2/tickets/$ticketId/messages")}")
      .withProxy
      .withBody(Json.toJson(CreateResponseRequest.fromRaw(userEmail, message)))
      .setHeader(AUTHORIZATION -> config.deskproApiKey)
      .execute[DeskproCreateMessageWrapperResponse]

  }

  def createMessageWithAttachments(ticketId: Int, userEmail: LaxEmailAddress, message: String, attachmentDetails: List[BlobDetails])(implicit hc: HeaderCarrier)
      : Future[DeskproCreateMessageWrapperResponse] =
    metrics.record(api) {
      val attachments    = attachmentDetails.map(details => (details.blobId.toString() -> AttachmentRequest(details.blobAuth))).toMap
      val messageRequest = CreateMessageRequest.fromRaw(message, userEmail.text, attachments)

      http
        .post(url"${requestUrl(s"/api/v2/tickets/$ticketId/messages")}")
        .withProxy
        .withBody(Json.toJson(messageRequest))
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .execute[DeskproCreateMessageWrapperResponse]
    }

  def getMessageAttachments(ticketId: Int, messageId: Int)(implicit hc: HeaderCarrier): Future[DeskproAttachmentsWrapperResponse] = {
    val queryParams = Seq("count" -> 200)
    metrics.record(api) {
      http
        .get(url"${requestUrl(s"/api/v2/tickets/$ticketId/messages/$messageId/attachments")}?$queryParams")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .execute[DeskproAttachmentsWrapperResponse]
    }
  }

  def getMessage(ticketId: Int, messageId: Int)(implicit hc: HeaderCarrier): Future[DeskproMessageWrapperResponse] = {
    metrics.record(api) {
      http
        .get(url"${requestUrl(s"/api/v2/tickets/$ticketId/messages/$messageId")}")
        .withProxy
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .execute[DeskproMessageWrapperResponse]
    }
  }

  def updateMessage(
      ticketId: Int,
      messageId: Int,
      message: String,
      existingAttachments: List[DeskproAttachmentResponse],
      blobDetails: Option[BlobDetails]
    )(implicit hc: HeaderCarrier
    ): Future[DeskproTicketMessageResult] = {
    val existingAttachmentsMap                    = existingAttachments.map(attachment => (attachment.blob.blob_id.toString() -> AttachmentRequest(attachment.blob.blob_auth))).toMap
    def getMessageRequest(): UpdateMessageRequest = {
      blobDetails match {
        case Some(blobDetails) => UpdateMessageRequest(message, existingAttachmentsMap ++ Map(blobDetails.blobId.toString() -> AttachmentRequest(blobDetails.blobAuth)))
        case _                 => UpdateMessageRequest(message, existingAttachmentsMap)
      }
    }

    metrics.record(api) {
      http
        .put(url"${requestUrl(s"/api/v2/tickets/$ticketId/messages/$messageId")}")
        .withProxy
        .withBody(Json.toJson((getMessageRequest())))
        .setHeader(AUTHORIZATION -> config.deskproApiKey)
        .execute[HttpResponse]
        .map(response =>
          response.status match {
            case NO_CONTENT =>
              logger.info(s"Updated message for ticket '$ticketId', message '$messageId' successfully")
              DeskproTicketMessageSuccess
            case NOT_FOUND  =>
              logger.warn(s"Failed to update message for ticket '$ticketId, message '$messageId'. Ticket not found")
              DeskproTicketMessageNotFound
            case _          =>
              logger.error(s"Failed to update message for ticket '$ticketId, message '$messageId'. Status: ${response.status}")
              DeskproTicketMessageFailure
          }
        )
    }
  }

  private def requestUrl[B, A](uri: String): String = s"$serviceBaseUrl$uri"
}
