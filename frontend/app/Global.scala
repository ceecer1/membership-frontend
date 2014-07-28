import scala.concurrent.Future

import play.api.mvc.{Result, RequestHeader, WithFilters}
import play.api.mvc.Results.NotFound
import play.api.mvc.WithFilters
import play.api.Application

import controllers.Cached
import filters.CheckCacheHeadersFilter
import services.{SubscriptionService, MemberRepository, EventbriteService}


object Global extends WithFilters(CheckCacheHeadersFilter) {
  override def onStart(app: Application) {
    EventbriteService.start()
    MemberRepository.start()
    SubscriptionService.start()
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = {
    Future.successful(Cached(NotFound(views.html.error404())))
  }
}
