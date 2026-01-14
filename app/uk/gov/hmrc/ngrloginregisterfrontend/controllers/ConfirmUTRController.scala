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

package uk.gov.hmrc.ngrloginregisterfrontend.controllers

import play.api.data.Form
import play.api.i18n.{I18nSupport, Messages}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.govukfrontend.views.viewmodels.radios.Radios
import uk.gov.hmrc.govukfrontend.views.viewmodels.summarylist.SummaryList
import uk.gov.hmrc.ngrloginregisterfrontend.actions.{AuthRetrievals, HasMandotoryDetailsAction, RegistrationAction}
import uk.gov.hmrc.ngrloginregisterfrontend.config.AppConfig
import uk.gov.hmrc.ngrloginregisterfrontend.connectors.CitizenDetailsConnector
import uk.gov.hmrc.ngrloginregisterfrontend.models.audit.AuditModel
import uk.gov.hmrc.ngrloginregisterfrontend.models.forms.ConfirmUTR.{NoLater, NoNI, Yes, form}
import uk.gov.hmrc.ngrloginregisterfrontend.models.forms.{ConfirmUTR, Nino}
import uk.gov.hmrc.ngrloginregisterfrontend.models.registration.ReferenceType.SAUTR
import uk.gov.hmrc.ngrloginregisterfrontend.models.registration.TRNReferenceNumber
import uk.gov.hmrc.ngrloginregisterfrontend.models.{NGRRadio, NGRRadioButtons, NGRRadioName, NGRSummaryListRow}
import uk.gov.hmrc.ngrloginregisterfrontend.repo.RatepayerRegistrationRepo
import uk.gov.hmrc.ngrloginregisterfrontend.services.AuditingService
import uk.gov.hmrc.ngrloginregisterfrontend.utils.StringHelper
import uk.gov.hmrc.ngrloginregisterfrontend.views.html.ConfirmUTRView
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfirmUTRController @Inject()(view: ConfirmUTRView,
                                     isRegisteredCheck: RegistrationAction,
                                     hasMandotoryDetailsAction: HasMandotoryDetailsAction,
                                     authenticate: AuthRetrievals,
                                     citizenDetailsConnector: CitizenDetailsConnector,
                                     mongo: RatepayerRegistrationRepo,
                                     mcc: MessagesControllerComponents,
                                     auditService: AuditingService)(implicit appConfig: AppConfig, ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport with StringHelper {

  private var savedUtr: String = ""

  def show(): Action[AnyContent] =
    (authenticate andThen isRegisteredCheck andThen hasMandotoryDetailsAction).async { implicit request =>
      println(Console.MAGENTA + request.credId + Console.RESET)
      request.ratepayerRegistration.map{ ratePayer =>
        ratePayer.nino match {
          case Some(nino) =>
            citizenDetailsConnector.getMatchingResponse(Nino(nino.value)).flatMap {
              case Left(error) => Future.failed(new RuntimeException(s"call to citizen details failed: ${error.code} ${error.message}"))
              case Right(details) =>
                details.saUtr
                  .map(utr => {
                    savedUtr = utr.value
                    Future.successful(Ok(view(form(), summaryList(maskString(savedUtr, 3)), radios(form()))))
                  })
                  .getOrElse(Future.failed(new RuntimeException("No SAUTR found")))
            }
          case None => Future.failed(new RuntimeException("No NINO found in request"))
        }
      }.getOrElse(Future.failed(new RuntimeException("No ratePayer found in database")))
    }

  private def summaryList(utr: String)(implicit messages: Messages): SummaryList = {
    SummaryList(Seq(
      NGRSummaryListRow.summarise(
        NGRSummaryListRow(
          titleMessageKey = Messages("confirmUtr.sautr"),
          captionKey = None,
          value = Seq(utr),
          changeLink = None
        )
      )
    ))
  }

  private def radios(form: Form[ConfirmUTR])(implicit  messages: Messages): Radios = {
    NGRRadio.buildRadios(form = form, NGRRadios = NGRRadio(
      radioGroupName = NGRRadioName(ConfirmUTR.formName),
      NGRRadioButtons = Seq(
        NGRRadioButtons(radioContent = messages("confirmUtr.yesProvide"), radioValue = Yes(savedUtr)),
        NGRRadioButtons(radioContent = messages("confirmUtr.noNI"), radioValue = NoNI),
        NGRRadioButtons(radioContent = messages("confirmUtr.noLater"), radioValue = NoLater)
      ),
      ngrTitle = None
    ))
  }

  def submit(): Action[AnyContent] =
    (authenticate andThen isRegisteredCheck andThen hasMandotoryDetailsAction).async { implicit request =>
      ConfirmUTR.form()
        .bindFromRequest()
        .fold(
          formWithErrors => Future.successful(BadRequest(view(formWithErrors, summaryList(maskSAUTR(savedUtr)), radios(formWithErrors)))),
          {
            case ConfirmUTR.Yes(utr) =>
              mongo.updateTRN(request.credId, TRNReferenceNumber(SAUTR, utr))
              auditService.extendedAudit(
                AuditModel(request.credId.value,"check-answers"),
                uk.gov.hmrc.ngrloginregisterfrontend.controllers.routes.ConfirmUTRController.show.url
              )
              Future.successful(Redirect(routes.CheckYourAnswersController.show))
            case ConfirmUTR.NoNI =>
              auditService.extendedAudit(
                AuditModel(request.credId.value,"provide-national-insurance-number"),
                uk.gov.hmrc.ngrloginregisterfrontend.controllers.routes.ConfirmUTRController.show.url
              )
              Future.successful(Redirect(routes.NinoController.show))
            case ConfirmUTR.NoLater =>
              mongo.updateTRN(request.credId, TRNReferenceNumber(SAUTR, ""))
              auditService.extendedAudit(
                AuditModel(request.credId.value,"check-answers"),
                uk.gov.hmrc.ngrloginregisterfrontend.controllers.routes.ConfirmUTRController.show.url
              )
              Future.successful(Redirect(routes.CheckYourAnswersController.show))
          }
        )
    }
}
