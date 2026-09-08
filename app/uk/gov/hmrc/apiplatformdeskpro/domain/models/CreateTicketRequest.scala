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

package uk.gov.hmrc.apiplatformdeskpro.domain.models

import play.api.libs.json._

case class CreateTicketRequest(
    fullName: String,
    email: String,
    subject: String,
    message: String,
    apiName: Option[String],
    applicationId: Option[String],
    organisation: Option[String],
    supportReason: Option[String],
    reasonKey: Option[String],
    teamMemberEmail: Option[String],
    service: Option[String],
    referrer: Option[String],
    sessionId: Option[String],
    userAgent: Option[String],
    organisationSubmissionId: Option[String],
    attachments: List[FileAttachment] = List.empty
  )

object CreateTicketRequest {
  implicit val createTicketFormat: OFormat[CreateTicketRequest] = Json.format[CreateTicketRequest]
}
