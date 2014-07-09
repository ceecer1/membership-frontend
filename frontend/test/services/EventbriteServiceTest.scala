package services

import play.api.test.PlaySpecification
import model.Eventbrite.{EBError, EBObject}
import scala.concurrent.{Await, Future}
import play.api.libs.json.Reads
import utils.Resource
import scala.concurrent.duration._

class EventbriteServiceTest extends PlaySpecification {

  "EventbriteService" should {

    "reuses an existing discount code" in TestEventbriteService { service =>
      Await.ready(service.createOrGetDiscount("test", "5ZCYERL5"), 5.seconds)
      service.lastRequest mustEqual RequestInfo.empty
    }

    "creates a new discount code" in TestEventbriteService { service =>
      Await.ready(service.createOrGetDiscount("test", "NEW"), 5.seconds)

      service.lastRequest mustEqual RequestInfo(
        url = s"http://localhost:9999/v1/events/test/discounts",
        body = Map(
          "discount.code" -> Seq("NEW"),
          "discount.quantity_available" -> Seq("2"),
          "discount.percent_off" -> Seq("20")
        )
      )
    }
  }

  class TestEventbriteService extends EventbriteService {
    val apiURL = "http://localhost:9999/v1"
    val apiEventListUrl = "events"

    var lastRequest = RequestInfo.empty

    def get[A <: EBObject](endpoint: String, params: (String, String)*)(implicit reads: Reads[A]): Future[A] = {
      endpoint match {
        case "events/test/discounts" =>
          val resource = Resource.getJson(s"model/eventbrite/discounts.json")
          Future.successful(resource.as[A])
        case _ =>
          lastRequest = RequestInfo(s"$apiURL/$endpoint", Map.empty)
          Future.failed[A](EBError("internal", "Not implemented", 500)) // don't care
      }
    }

    def post[A <: EBObject](endpoint: String, data: Map[String, Seq[String]])(implicit reads: Reads[A]): Future[A] = {
      lastRequest = RequestInfo(s"$apiURL/$endpoint", data)
      Future.failed[A](EBError("internal", "Not implemented", 500)) // don't care
    }
  }

  object TestEventbriteService {
    def apply[T](block: TestEventbriteService => T) = block(new TestEventbriteService)
  }

}