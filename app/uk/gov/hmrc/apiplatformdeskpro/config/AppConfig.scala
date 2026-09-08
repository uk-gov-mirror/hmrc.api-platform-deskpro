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

package uk.gov.hmrc.apiplatformdeskpro.config

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration

import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

@Singleton
class AppConfig @Inject() (config: ServicesConfig) {

  val appName: String                         = config.getString("appName")
  val deskproUrl: String                      = config.getString("deskpro.uri")
  private val deskproKeyConfig                = config.getString("deskpro.api-key")
  val deskproApiKey: String                   = s"key $deskproKeyConfig"
  val deskproBrand: Int                       = config.getInt("deskpro.brand")
  val deskproOrganisation: String             = config.getString("deskpro.organisation")
  val deskproTeamMemberEmail: String          = config.getString("deskpro.team-member-email")
  val deskproSupportReason: String            = config.getString("deskpro.support-reason")
  val deskproReasonKey: String                = config.getString("deskpro.reason-key")
  val deskproApplicationId: String            = config.getString("deskpro.application-id")
  val deskproSessionId: String                = config.getString("deskpro.session-id")
  val deskproService: String                  = config.getString("deskpro.service")
  val deskproReferrer: String                 = config.getString("deskpro.referrer")
  val deskproUserAgent: String                = config.getString("deskpro.user-agent")
  val deskproOrganisationSubmissionId: String = config.getString("deskpro.organisation-submission-id")
  val deskproApiName: String                  = config.getString("deskpro.api-name")
  val deskproInactive: String                 = config.getString("deskpro.inactive")
  val deskproInactivatedDate: String          = config.getString("deskpro.inactivated-date")
  val thirdPartyDeveloperUrl: String          = config.baseUrl("third-party-developer")
  val deskproBatchSize: Int                   = config.getInt("importUser.deskpro-batch-size")
  val deskproBatchPause: Int                  = config.getInt("importUser.deskpro-batch-pause")
  val initialImport: Boolean                  = config.getBoolean("importUser.initial-import")
  val deskproPersonCacheTimeout: Duration     = config.getDuration("deskpro-person-cache.timeout")

}
