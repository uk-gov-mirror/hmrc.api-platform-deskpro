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

package uk.gov.hmrc.apiplatformdeskpro.service

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.apiplatformdeskpro.config.AppConfig
import uk.gov.hmrc.apiplatformdeskpro.connector.DeskproConnector
import uk.gov.hmrc.apiplatformdeskpro.domain.models._
import uk.gov.hmrc.apiplatformdeskpro.domain.models.connector._
import uk.gov.hmrc.apiplatformdeskpro.domain.models.controller.CreateTicketResponseRequest
import uk.gov.hmrc.apiplatformdeskpro.domain.models.mongo.UploadStatus.UploadedSuccessfully
import uk.gov.hmrc.apiplatformdeskpro.domain.models.mongo.{BlobDetails, DeskproMessageFileAttachment, UploadedFile}
import uk.gov.hmrc.apiplatformdeskpro.repository.{DeskproMessageFileAttachmentRepository, UploadedFileRepository}
import uk.gov.hmrc.apiplatformdeskpro.utils.ApplicationLogger
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.{ClockNow, EitherTHelper}

@Singleton
class TicketService @Inject() (
    deskproConnector: DeskproConnector,
    personService: PersonService,
    deskproMessageFileAttachmentRepository: DeskproMessageFileAttachmentRepository,
    uploadedFileRepository: UploadedFileRepository,
    config: AppConfig,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ClockNow {

  private val ET = EitherTHelper.make[DeskproTicketCreationFailed]

  def submitTicket(request: CreateTicketRequest)(implicit hc: HeaderCarrier): Future[Either[DeskproTicketCreationFailed, DeskproTicketCreated]] = {
    (
      for {
        uploadedFiles      <- ET.liftF(getUploadedFileDetails(request.attachments))
        messageWithWarnings = FileAttachmentWarnings.addMessageFileUploadWarnings(request.message, request.attachments, uploadedFiles)
        createDeskproTicket = createDeskproTicketRequest(request, messageWithWarnings, uploadedFiles)
        ticket             <- ET.fromEitherF(deskproConnector.createTicket(createDeskproTicket))
        ticketId            = ticket.data.id
        ticketMessages     <- ET.liftF(deskproConnector.getTicketMessages(ticketId))
        messageId           = ticketMessages.data.head.id
        maybeMessage       <- ET.liftF(saveMessageFileDetails(ticketId, messageId, request.attachments))
        _                  <- ET.liftF(recheckUploadedFiles(maybeMessage, uploadedFiles))
      } yield ticket
    ).value
  }

  private def createDeskproTicketRequest(request: CreateTicketRequest, message: String, uploadedFiles: List[UploadedFile]): CreateDeskproTicket = {
    val maybeOrganisation             = request.organisation.fold(Map.empty[String, String])(v => Map(config.deskproOrganisation -> v))
    val maybeApiName                  = request.apiName.fold(Map.empty[String, String])(v => Map(config.deskproApiName -> v))
    val maybeApplicationId            = request.applicationId.fold(Map.empty[String, String])(v => Map(config.deskproApplicationId -> v))
    val maybeSupportReason            = request.supportReason.fold(Map.empty[String, String])(v => Map(config.deskproSupportReason -> v))
    val maybeReasonKey                = request.reasonKey.fold(Map.empty[String, String])(v => Map(config.deskproReasonKey -> v))
    val maybeService                  = request.service.fold(Map.empty[String, String])(v => Map(config.deskproService -> v))
    val maybeReferrer                 = request.referrer.fold(Map.empty[String, String])(v => Map(config.deskproReferrer -> v))
    val maybeSessionId                = request.sessionId.fold(Map.empty[String, String])(v => Map(config.deskproSessionId -> v))
    val maybeUserAgent                = request.userAgent.fold(Map.empty[String, String])(v => Map(config.deskproUserAgent -> v))
    val maybeOrganisationSubmissionId = request.organisationSubmissionId.fold(Map.empty[String, String])(v => Map(config.deskproOrganisationSubmissionId -> v))

    val fields =
      maybeOrganisation ++ maybeApiName ++ maybeApplicationId ++ maybeSupportReason ++ maybeReasonKey ++ maybeService ++ maybeReferrer ++ maybeSessionId ++ maybeUserAgent ++ maybeOrganisationSubmissionId
    val person = DeskproPerson(request.fullName, request.email)

    CreateDeskproTicket(
      person,
      request.subject,
      DeskproTicketMessage.fromRaw(message, person, getBlobDetails(uploadedFiles)),
      config.deskproBrand,
      fields,
      request.teamMemberEmail.map(List(_)).getOrElse(List.empty)
    )
  }

  def getTicketsForPerson(personEmail: LaxEmailAddress, status: Option[String])(implicit hc: HeaderCarrier): Future[List[DeskproTicket]] = {
    for {
      personId       <- personService.getPersonIdForEmail(personEmail)
      ticketResponse <- deskproConnector.getTicketsForPersonId(personId, status)
    } yield ticketResponse.data.map(response => DeskproTicket.build(response, List.empty, List.empty))
  }

  def batchFetchTicket(ticketId: Int)(implicit hc: HeaderCarrier): Future[Option[DeskproTicket]] = {
    deskproConnector.batchFetchTicket(ticketId) map { response => DeskproTicket.build(response.responses.ticket, response.responses.messages, response.responses.attachments) }
  }

  def createMessage(ticketId: Int, request: CreateTicketResponseRequest)(implicit hc: HeaderCarrier): Future[DeskproCreateMessageResponse] = {
    for {
      uploadedFiles       <- getUploadedFileDetails(request.attachments)
      messageWithWarnings  = FileAttachmentWarnings.addMessageFileUploadWarnings(request.message, request.attachments, uploadedFiles)
      createMessageResult <- deskproConnector.createMessageWithAttachments(ticketId, request.userEmail, messageWithWarnings, getBlobDetails(uploadedFiles))
      maybeMessage        <- saveMessageFileDetails(ticketId, createMessageResult.data.id, request.attachments)
      _                   <- deskproConnector.updateTicketStatus(ticketId, request.status)
      _                   <- recheckUploadedFiles(maybeMessage, uploadedFiles)
    } yield createMessageResult.data
  }

  private def saveMessageFileDetails(ticketId: Int, messageId: Int, attachments: List[FileAttachment]): Future[Option[DeskproMessageFileAttachment]] = {
    if (!attachments.isEmpty) {
      deskproMessageFileAttachmentRepository.create(DeskproMessageFileAttachment(ticketId, messageId, attachments, instant)) map { resp => Some(resp) }
    } else {
      Future.successful(None)
    }
  }

  private def getBlobDetails(uploadedFiles: List[UploadedFile]): List[BlobDetails] = {
    uploadedFiles.map(uploadedFile =>
      getBlobDetails(uploadedFile)
    ).flatten
  }

  private def getBlobDetails(uploadedFile: UploadedFile): Option[BlobDetails] = {
    uploadedFile.uploadStatus match {
      case success: UploadedSuccessfully => Some(success.blobDetails)
      case _                             => None
    }
  }

  private def getUploadedFileDetails(attachments: List[FileAttachment]): Future[List[UploadedFile]] = {
    for {
      uploadFiles <- Future.sequence(attachments.map(attachment => uploadedFileRepository.fetchByFileReference(attachment.fileReference)))
    } yield uploadFiles.flatten
  }

  private def recheckUploadedFiles(maybeMessage: Option[DeskproMessageFileAttachment], previousUploadedFiles: List[UploadedFile])(implicit hc: HeaderCarrier)
      : Future[Option[List[UploadedFile]]] = {
    maybeMessage match {
      case Some(message) => {
        for {
          uploadedFiles   <- getUploadedFileDetails(message.attachments)
          newUploadedFiles = uploadedFiles.filterNot(file => previousUploadedFiles.contains(file))
          _               <- Future.sequence(newUploadedFiles.map(uploadedFile => updateMessage(uploadedFile.fileReference, message, uploadedFiles, getBlobDetails(uploadedFile))))
        } yield Some(newUploadedFiles)
      }
      case _             => Future.successful(None)
    }
  }

  def updateMessageAttachmentsIfRequired(fileReference: String, maybeBlobDetails: Option[BlobDetails])(implicit hc: HeaderCarrier): Future[DeskproTicketMessageResult] = {
    for {
      maybeMessage  <- deskproMessageFileAttachmentRepository.fetchByFileReference(fileReference)
      uploadedFiles <- maybeMessage match {
                         case Some(message) => getUploadedFileDetails(message.attachments)
                         case _             => Future.successful(List.empty)
                       }
      result        <- updateMessageIfAlreadyCreated(fileReference, maybeMessage, uploadedFiles, maybeBlobDetails)
    } yield result
  }

  private def updateMessageIfAlreadyCreated(
      fileReference: String,
      maybeMessage: Option[DeskproMessageFileAttachment],
      uploadedFiles: List[UploadedFile],
      maybeBlobDetails: Option[BlobDetails]
    )(implicit hc: HeaderCarrier
    ): Future[DeskproTicketMessageResult] = {
    maybeMessage match {
      case Some(messageFileAttachment) => updateMessage(fileReference, messageFileAttachment, uploadedFiles, maybeBlobDetails)
      case _                           => Future.successful(DeskproTicketMessageSuccess)
    }
  }

  private def updateMessage(
      fileReference: String,
      messageFileAttachment: DeskproMessageFileAttachment,
      uploadedFiles: List[UploadedFile],
      maybeBlobDetails: Option[BlobDetails]
    )(implicit hc: HeaderCarrier
    ): Future[DeskproTicketMessageResult] = {
    logger.info(s"Updating message for ticketId: ${messageFileAttachment.ticketId}, messageId: ${messageFileAttachment.messageId}")

    for {
      attachments   <- deskproConnector.getMessageAttachments(messageFileAttachment.ticketId, messageFileAttachment.messageId)
      message       <- deskproConnector.getMessage(messageFileAttachment.ticketId, messageFileAttachment.messageId)
      amendedMessage = FileAttachmentWarnings.amendMessageFileAttachmentWarnings(message.data.message, messageFileAttachment.attachments, uploadedFiles)
      result        <- deskproConnector.updateMessage(
                         messageFileAttachment.ticketId,
                         messageFileAttachment.messageId,
                         amendedMessage,
                         attachments.data,
                         maybeBlobDetails
                       )
    } yield result
  }

  def deleteTicket(ticketId: Int)(implicit hc: HeaderCarrier): Future[DeskproTicketUpdateResult] = {
    deskproConnector.deleteTicket(ticketId)
  }
}
