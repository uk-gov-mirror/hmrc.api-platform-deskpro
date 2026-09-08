/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatformdeskpro.controller

import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatestplus.play.guice.GuiceOneServerPerSuite

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.WsTestClient
import uk.gov.hmrc.apiplatformdeskpro.domain.models._
import uk.gov.hmrc.apiplatformdeskpro.domain.models.connector.{CreateDeskproTicket, DeskproTicketMessage}
import uk.gov.hmrc.apiplatformdeskpro.stubs.{DeskproStub, InternalAuthStub}
import uk.gov.hmrc.apiplatformdeskpro.utils.{AsyncHmrcSpec, ConfigBuilder}
import uk.gov.hmrc.http.test.WireMockSupport

class CreateTicketControllerISpec extends AsyncHmrcSpec with WireMockSupport with ConfigBuilder with GuiceOneServerPerSuite with WsTestClient {

  override def fakeApplication() = GuiceApplicationBuilder()
    .configure(stubConfig(wireMockPort))
    .build()

  trait Setup extends DeskproStub with InternalAuthStub {

    val token                 = "123456"
    val underTest             = app.injector.instanceOf[CreateTicketController]
    implicit val appPort: Int = port
  }

  "createTicket" should {
    "return 201 on the agreed route" in new Setup {
      Authenticate.returns(token)
      val createTicketRequest = CreateTicketRequest(
        "Dave",
        "dave@example.com",
        "subject",
        "message",
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None
      )

      val deskproPerson = DeskproPerson("Dave", "dave@example.com")
      val deskproTicket = CreateDeskproTicket(deskproPerson, "subject", DeskproTicketMessage("message", deskproPerson), 1)
      CreateTicket.stubSuccess(deskproTicket)
      GetTicketMessages.stubSuccess(12345)

      val response = await(wsUrl(s"/ticket")
        .addHttpHeaders("Authorization" -> token)
        .post(Json.toJson(createTicketRequest)))

      response.status mustBe CREATED
      response.json.as[CreateTicketResponse] mustBe CreateTicketResponse(Some("SDST-1234"))
    }
  }
}
