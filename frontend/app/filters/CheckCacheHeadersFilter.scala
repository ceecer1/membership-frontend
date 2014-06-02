package filters

import play.api.mvc._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object CheckCacheHeadersFilter extends Filter {

  def apply(nextFilter: RequestHeader => Future[SimpleResult])(requestHeader: RequestHeader): Future[SimpleResult] = {
    nextFilter(requestHeader).map { result =>
      val hasCacheControl = result.header.headers.contains("Cache-Control")
      assert(hasCacheControl, s"Cache-Control not set. Ensure controller response has Cache-Control header set for ${requestHeader.path}. Throwing exception... ")
      result
    }
  }
}