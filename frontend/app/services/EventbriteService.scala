package services

import com.gu.membership.util.WebServiceHelper
import configuration.Config
import model.EventGroup
import model.Eventbrite._
import model.EventbriteDeserializer._
import model.RichEvent._
import monitoring.EventbriteMetrics
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.Reads
import play.api.libs.ws._
import utils.ScheduledTask

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

trait EventbriteService extends WebServiceHelper[EBObject, EBError] {
  val apiToken: String
  val maxDiscountQuantityAvailable: Int

  val wsUrl = Config.eventbriteApiUrl
  def wsPreExecute(req: WSRequestHolder): WSRequestHolder = req.withQueryString("token" -> apiToken)

  val refreshTimeAllEvents = new FiniteDuration(Config.eventbriteRefreshTime, SECONDS)
  lazy val eventsTask = ScheduledTask[Seq[RichEvent]]("Eventbrite events", Nil, 1.second, refreshTimeAllEvents) {
    for {
      events <- getAll[EBEvent]("users/me/owned_events?status=live")
      richEvents <- Future.sequence(events.map(mkRichEvent))
    } yield richEvents
  }

  val refreshTimeDraftEvents = new FiniteDuration(Config.eventbriteRefreshTime, SECONDS)
  lazy val draftEventsTask = ScheduledTask[Seq[RichEvent]]("Eventbrite draft events", Nil, 1.second, refreshTimeDraftEvents) {
    for {
      eventsDraft <- getAll[EBEvent]("users/me/owned_events?status=draft")
      richDraftEvents <- Future.sequence(eventsDraft.map(mkRichEvent))
    } yield richDraftEvents
  }

  val refreshTimeArchivedEvents = new FiniteDuration(Config.eventbriteRefreshTime, SECONDS)
  lazy val archivedEventsTask = ScheduledTask[Seq[RichEvent]]("Eventbrite archived events", Nil, 1.second, refreshTimeArchivedEvents) {
    for {
      eventsArchive <- get[EBResponse[EBEvent]]("users/me/owned_events?status=ended&order_by=start_desc")
      richEventsArchive <- Future.sequence(eventsArchive.data.map(mkRichEvent))
    } yield richEventsArchive
  }

  def start() {
    Logger.info("Starting EventbriteService background tasks")
    eventsTask.start()
    draftEventsTask.start()
    archivedEventsTask.start()
  }

  def events: Seq[RichEvent] = eventsTask.get()
  def eventsDraft: Seq[RichEvent] = draftEventsTask.get()
  def eventsArchive: Seq[RichEvent] = archivedEventsTask.get()

  def mkRichEvent(event: EBEvent): Future[RichEvent]
  def getFeaturedEvents: Seq[RichEvent]
  def getEvents: Seq[RichEvent]
  def getTaggedEvents(tag: String): Seq[RichEvent]
  def getPartnerEvents: Option[EventGroup]
  def getEventsArchive: Option[Seq[RichEvent]] = Some(eventsArchive)

  private def getAll[T](url: String)(implicit reads: Reads[EBResponse[T]]): Future[Seq[T]] = {
    def getPage(page: Int) = get[EBResponse[T]](url, "page" -> page.toString)

    for {
      initialResponse <- getPage(1)
      followingResponses: Seq[EBResponse[T]] <- Future.traverse(2 to initialResponse.pagination.page_count)(getPage)
    } yield (initialResponse +: followingResponses).flatMap(_.data)
  }

  def getPreviewEvent(id: String): Future[RichEvent] = for {
    event <- get[EBEvent](s"events/$id")
    richEvent <- mkRichEvent(event)
  } yield richEvent

  def getBookableEvent(id: String): Option[RichEvent] = events.find(_.id == id)
  def getEvent(id: String): Option[RichEvent] = (events ++ eventsArchive).find(_.id == id)

  def createOrGetAccessCode(event: RichEvent, code: String, ticketClasses: Seq[EBTicketClass]): Future[EBAccessCode] = {
    val uri = s"events/${event.id}/access_codes"

    for {
      discounts <- getAll[EBAccessCode](uri)
      discount <- discounts.find(_.code == code).fold {
        post[EBAccessCode](uri, Map(
          "access_code.code" -> Seq(code),
          "access_code.quantity_available" -> Seq(maxDiscountQuantityAvailable.toString),
          "access_code.ticket_ids" -> Seq(ticketClasses.map(_.id).mkString(","))
        ))
      }(Future.successful)
    } yield discount
  }

  def getOrder(id: String): Future[EBOrder] = get[EBOrder](s"orders/$id")
}

abstract class LiveService extends EventbriteService {
  val gridService = GridService(Config.gridConfig.url)
  val contentApiService = GuardianContentService
}

object GuardianLiveEventService extends LiveService {
  val apiToken = Config.eventbriteApiToken
  val maxDiscountQuantityAvailable = 2
  val wsMetrics = new EventbriteMetrics("Guardian Live")

  val refreshTimePriorityEvents = new FiniteDuration(Config.eventbriteRefreshTimeForPriorityEvents, SECONDS)
  lazy val eventsOrderingTask = ScheduledTask[Seq[String]]("Event ordering", Nil, 1.second, refreshTimePriorityEvents) {
    for {
      ordering <- WS.url(Config.eventOrderingJsonUrl).get()
    } yield (ordering.json \ "order").as[Seq[String]]
  }

  def mkRichEvent(event: EBEvent): Future[RichEvent] = {
    val eventbriteContent = contentApiService.content(event.id)

    event.mainImageUrl.fold(Future.successful(GuLiveEvent(event, None, eventbriteContent))) { url =>
      gridService.getRequestedCrop(url).map(GuLiveEvent(event, _, eventbriteContent))
    }
  }

  override def getFeaturedEvents: Seq[RichEvent] = EventbriteServiceHelpers.getFeaturedEvents(eventsOrderingTask.get(), events)
  override def getEvents: Seq[RichEvent] = events.diff(getFeaturedEvents ++ getPartnerEvents.map(_.events).getOrElse(Nil))
  override def getTaggedEvents(tag: String): Seq[RichEvent] = events.filter(_.name.text.toLowerCase.contains(tag))
  override def getPartnerEvents: Option[EventGroup] = Some(EventGroup("Programming Partner Events", events.filter(_.providerOpt.isDefined)))
  override def start() {
    super.start()
    eventsOrderingTask.start()
  }
}

object DiscoverEventService extends LiveService {
  val apiToken = Config.eventbriteDiscoverApiToken
  val maxDiscountQuantityAvailable = 2 //TODO are these discounts correct for discovery?
  val wsMetrics = new EventbriteMetrics("Discover")

  def mkRichEvent(event: EBEvent): Future[RichEvent] = {
    val eventbriteContent = contentApiService.content(event.id)

    event.mainImageUrl.fold(Future.successful(DiscoverEvent(event, None, eventbriteContent))) { url =>
      gridService.getRequestedCrop(url).map(DiscoverEvent(event, _, eventbriteContent))
    }
  }

  override def getFeaturedEvents: Seq[RichEvent] = EventbriteServiceHelpers.getFeaturedEvents(Nil, events)
  override def getEvents: Seq[RichEvent] = events
  override def getTaggedEvents(tag: String): Seq[RichEvent] = events.filter(_.name.text.toLowerCase.contains(tag))
  override def getPartnerEvents: Option[EventGroup] = None
  override def start() {
    super.start()
  }
}

case class MasterclassEventServiceError(s: String) extends Throwable {
  override def getMessage: String = s
}

object MasterclassEventsProvider {
  val MasterclassesWithAvailableMemberDiscounts: (RichEvent) => Boolean =
    _.internalTicketing.exists(_.memberDiscountOpt.exists(!_.isSoldOut))
}

object MasterclassEventService extends EventbriteService {
  import MasterclassEventsProvider._

  val apiToken = Config.eventbriteMasterclassesApiToken
  val maxDiscountQuantityAvailable = 1

  val wsMetrics = new EventbriteMetrics("Masterclasses")

  val contentApiService = GuardianContentService

  override def events: Seq[RichEvent] = super.events.filter(MasterclassesWithAvailableMemberDiscounts)

  def mkRichEvent(event: EBEvent): Future[RichEvent] = {
    val masterclassData = contentApiService.masterclassContent(event.id)
    //todo change this to have link to weburl
    Future.successful(MasterclassEvent(event, masterclassData))
  }

  override def getFeaturedEvents: Seq[RichEvent] = Nil
  override def getEvents: Seq[RichEvent] = events
  override def getTaggedEvents(tag: String): Seq[RichEvent] = events.filter(_.tags.contains(tag.toLowerCase))
  override def getPartnerEvents: Option[EventGroup] = None
}

object EventbriteServiceHelpers {

  def getFeaturedEvents(orderedIds: Seq[String], events: Seq[RichEvent]): Seq[RichEvent] = {
    val (orderedEvents, normalEvents) = events.partition { event => orderedIds.contains(event.id) }
    orderedEvents.sortBy { event => orderedIds.indexOf(event.id) } ++ normalEvents.filter(!_.isSoldOut).take(4 - orderedEvents.length)
  }
}

object EventbriteService {
  val services = Seq(GuardianLiveEventService, DiscoverEventService, MasterclassEventService)

  implicit class RichEventProvider(event: RichEvent) {
    val service = event match {
      case _: GuLiveEvent => GuardianLiveEventService
      case _: DiscoverEvent => DiscoverEventService
      case _: MasterclassEvent => MasterclassEventService
    }
  }

  def getPreviewEvent(id: String): Future[RichEvent] = Cache.getOrElse[Future[RichEvent]](s"preview-event-$id", 2) {
    GuardianLiveEventService.getPreviewEvent(id)
  }

  def getPreviewDiscoverEvent(id: String): Future[RichEvent] = Cache.getOrElse[Future[RichEvent]](s"preview-event-$id", 2) {
    DiscoverEventService.getPreviewEvent(id)
  }

  def getPreviewMasterclass(id: String): Future[RichEvent] = Cache.getOrElse[Future[RichEvent]](s"preview-event-$id", 2) {
    MasterclassEventService.getPreviewEvent(id)
  }

  def searchServices(fn: EventbriteService => Option[RichEvent]): Option[RichEvent] =
    services.flatMap { service => fn(service) }.headOption

  def getBookableEvent(id: String) = searchServices(_.getBookableEvent(id))
  def getEvent(id: String) = searchServices(_.getEvent(id))
}
