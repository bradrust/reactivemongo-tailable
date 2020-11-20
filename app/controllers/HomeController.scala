package controllers

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import reactivemongo.akkastream.cursorProducer
import reactivemongo.api.bson.{BSONDocument, BSONString, bsonOffsetDateTimeHandler}
import javax.inject._

import scala.concurrent.duration._
import akka.stream.scaladsl.Source
import play.api.http.ContentTypes
import play.api.libs.EventSource._
import play.api.libs.json.Json
import play.api.mvc._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.Cursor

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HomeController @Inject()(cc: ControllerComponents,
                               mongo: ReactiveMongoApi)(implicit ec: ExecutionContext, mat: Materializer) extends AbstractController(cc) {

  def collection: Future[reactivemongo.api.bson.collection.BSONCollection] = {
    val rv = (for {
      db <- mongo.database
      collection = db.collection[reactivemongo.api.bson.collection.BSONCollection]("notifications")
      rv <- collection.stats().flatMap(stats => {
        if (!stats.capped) {
          collection.convertToCapped(5 * 1024 * 1024, Option(5000))
            .map(_ => collection)
        } else {
          Future.successful(collection)
        }
      })
    } yield {
      rv
    }).recoverWith {
      case _ =>
        // collection probably doesn't exist so lets go make it.
        val rv2 = mongo.database.flatMap(db => {
          def collection = db.collection[reactivemongo.api.bson.collection.BSONCollection]("notifications")
          collection.createCapped(5 * 1024 * 1024, Option(5000)).map(_ => collection)
        })
        rv2
    }
    rv
  }

  def watchNotifications(lookFor: String): Source[Event, NotUsed] = {
    Source.future(collection.map(c => {
      val f = c
        .find(BSONDocument(
        "createdAt" -> BSONDocument("$gte" -> OffsetDateTime.now())
      ), Option.empty[BSONDocument])
      val cp = f
        .maxTimeMs(100L)
        .comment(s"lookFor ${lookFor}")
        .tailable
        .awaitData
        .cursor[BSONDocument]()
      cp
        .documentSource(err = Cursor.DoneOnError())
        .mapAsync(1)(x => {
          val someDbMessage = x.getAsOpt[BSONString]("message").orElse(Option(BSONString("whatever"))).map(_.toString)
          // println(s"mid-stream peek ${someDbMessage}/${lookFor}/${someDbMessage.map(_.contains(lookFor)).getOrElse(false)}")
          if (someDbMessage.map(_.contains(lookFor)).getOrElse(false)) {
            println(s"server-side swallowing ${lookFor}")
            // Future.successful(x)
            c.insert(ordered = true).one(
              BSONDocument(
                "id" -> s"prime ${lookFor}",
                "message" -> lookFor.reverse,
                "createdAt" -> OffsetDateTime.now()
              )).map(r => {
              println(s"immediate push result ${r}")
              throw new RuntimeException("poison pill swallowed")
            })
          } else {
            Future.successful(x)
          }
        })
    })/*.recover({
      case e => {
        println(s"exception!! ${e.getMessage}")
        Source.empty
      }
    })*/
    ).flatMapConcat(identity)
      .map(x => {
        val someDbId = x.getAsOpt[BSONString]("id").getOrElse(BSONString("whatever")).toString()
        val someDbMessage = x.getAsOpt[BSONString]("message").orElse(Option(BSONString("whatever"))).map(_.toString)
        println(s"server-side map document to Event (${someDbId})")
        Event(someDbId, Option(UUID.randomUUID().toString), someDbMessage)
      })
  }

  def chunkEvents(limit: Int = 8192): Flow[Event, String, _] =
    Flow[Event]
      .map(_.formatted)
      .flatMapConcat {
        case s if s.length < limit => Source.single(s)
        case s => Source.fromIterator(() => s.grouped(limit))
      }

  def Comment = Event("", Some(UUID.randomUUID().toString), Some("tick"))

  def comment = Source.single(Comment)

  val PoisonEvent = Event("poison", Some(""), Some("poison"))

  val ppCounter = new AtomicInteger(0)

  def index() = Action { implicit request: Request[AnyContent] =>

    println("server-side starting SSE documentSource watching")

    val poisonPillMessage = s"poison-pill-${UUID.randomUUID().toString}"
    val poisonPill = Source.single(PoisonEvent)
      .delay(20.seconds)
      .mapAsync(1)(x => {
        val nextid = ppCounter.incrementAndGet()
        val rv = collection.map(c => {
          for {
            d1 <- c.insert(ordered = true).one(
              BSONDocument(
                "id" -> s"msg ${nextid}",
                "message" -> poisonPillMessage,
                "createdAt" -> OffsetDateTime.now()
              ))
          } yield {
            println("server-side-source insert poison pill")
            ()
          }
        }).map(_ => x)
        rv
      })
      //.map(_ => {
      //  throw new RuntimeException("server-side-source abort SSE watching")
      //})

    var stream = watchNotifications(poisonPillMessage)
    stream = Source.single(Comment).concat(stream)
    stream = stream.flatMapConcat(e => Source(List(e, Comment)))
    stream = stream.concat(poisonPill)
    stream = stream.keepAlive(20.seconds, () => Comment)
    Ok.chunked(stream.via(chunkEvents())).as(ContentTypes.EVENT_STREAM)
  }

  val counter = new AtomicInteger(0)

  def post() = Action.async { implicit request: Request[AnyContent] =>
    val nextid = counter.incrementAndGet()
    collection.map(c => {
      c.insert(ordered = true).one(
        BSONDocument(
          "id" -> s"msg ${nextid}",
          // "message" -> s"pick a random number... ${Math.random() * 100}",
          "message" -> s"new event posted at ${OffsetDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)}",
          "createdAt" -> OffsetDateTime.now()
        ))
    }).map(_ => {
      println(s"server-side inserted message ${nextid}")
      Ok(Json.obj("msg" -> s"ok ${nextid}"))
    })
  }

}
