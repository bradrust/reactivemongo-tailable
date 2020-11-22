package controllers

import java.time.OffsetDateTime

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play._
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import play.modules.reactivemongo.ReactiveMongoApi

import reactivemongo.api._
import reactivemongo.api.bson._
import reactivemongo.akkastream.cursorProducer

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

class PostSpec extends PlaySpec with GuiceOneServerPerSuite with ScalaFutures with Results {

  val notificationCollectionName = "notifications"


  //
  // this collection Future is how I am creating a collection in my classes and flatMap-ing when using it
  //
  def collection: Future[reactivemongo.api.bson.collection.BSONCollection] = {
    val system: ActorSystem = app.injector.instanceOf[ActorSystem]
    implicit val executionContext = app.injector.instanceOf[ExecutionContext]
    implicit val mat: Materializer = app.injector.instanceOf[Materializer]
    val mongo = app.injector.instanceOf[ReactiveMongoApi]

    val rv = (for {
      db <- mongo.database
      collection = db.collection[reactivemongo.api.bson.collection.BSONCollection](notificationCollectionName)
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
        mongo.database.flatMap(db => {
          def collection = db.collection[reactivemongo.api.bson.collection.BSONCollection](notificationCollectionName)
          collection.createCapped(5 * 1024 * 1024, Option(5000)).map(_ => collection)
        })
    }
    rv
  }

  def dropCollection = {
    val system: ActorSystem = app.injector.instanceOf[ActorSystem]
    implicit val executionContext = app.injector.instanceOf[ExecutionContext]
    implicit val mat: Materializer = app.injector.instanceOf[Materializer]
    val mongo = app.injector.instanceOf[ReactiveMongoApi]

    //
    // possibly drop the capped collection (maybe it exists, maybe it doesn't)
    //
    val db = Await.result(mongo.database, 20.seconds)
    val drop = db.collection(notificationCollectionName).drop()
    Await.result(drop, 5.seconds)
  }


  "cursor-spin" should {

    /**
     * sequencediagram.org URL for some swimlane views of this...
     *
     * https://sequencediagram.org/index.html?presentationMode=readOnly#initialData=C4S2BsFMAIGEFcBOBnA9o6BlADiAdgFAECGAxsOtAJJ5gjHggBekiBpqewiq40A6ojCsiBACbFgxAEbFkMWLyjkQnImQoYASgFkiHLjz5bIxMSII06DZqwC0APkXhloTgC4xPbNA4vIKmpWoDYsiI7Orqp47qSIpsAwflFBtCGMYRFKAW4xkHjISEnE2NiQYr7ZgYREeKiJ0KgAbqzUafQZrAA0kTnR7r4lZRXJfZzQFNDxZq3EeBUA7kKJGAC2cyDY8OCSkEQmM+EOuu5SIDvSUF3QxAvEYAAiksQE4KioPvBc59AAZiAoYDQVaQZDIYgAcxg+HkiESYgIdQazVauh6VVypxAEIAFkC4gloo1fr4kGgUNAABTxNBIUjQrj5ZAgFp-SiFeIASgAOngvqA+MRoGJUKR4CCuNAQMg-vgGOAAJ5SgqseHQaRK4A4mCCYQYKTIADWBF0WX81QGAG1uGRIABdKYAOmIjtIjoA4vlWCBSABFeCsBUAIXg53MiAAJCadGaUjFoNbELaHYhna6PV6hH6A4hg6HwOGo6anBj+gmbfSU2m3Z68N7s4GQ2HWEWYyXzZjy0nK06XTXMz7-Y384WCPkEQQgA
     *
     * In general,
     *   1. start by dropping the collection and creating a new one for cleanliness.   The collection
     *      is created as a capped collection.
     *   2. start a read of the capped collection.
     *   3. notice that the RM (or something) system furiously creating cursors.
     */
    "show some tight cursor producing logic" in {

      val system: ActorSystem = app.injector.instanceOf[ActorSystem]
      implicit val executionContext = app.injector.instanceOf[ExecutionContext]
      implicit val mat: Materializer = app.injector.instanceOf[Materializer]
      val mongo = app.injector.instanceOf[ReactiveMongoApi]

      dropCollection
      Await.result(collection, 10.seconds)

      //
      // wait for capped collection data (The Reader)
      //
      val byDateSelector = BSONDocument("createdAt" -> BSONDocument("$gte" -> OffsetDateTime.now()))
      val allSelector = BSONDocument.empty
      val x = collection.flatMap(c => {
        c.find(allSelector)
          .tailable
          .awaitData
          .cursor[BSONDocument]()
          .documentSource()
          .runWith(akka.stream.scaladsl.Sink.foreach { doc =>
            println(s"- ${BSONDocument pretty doc}")
          })
      })
      Await.result(x, 60.seconds)
    }


    /**
     * sequencediagram.org URL for some swimlane views of this...
     *
     * https://sequencediagram.org/index.html?presentationMode=readOnly#initialData=C4S2BsFMAIGEFcBOBnA9o6BlADiAdgPSyKQCGwMAQpAGbowAiqAxvALaR7DIBQPpzYOmgBJPGBClwIAF6REPZqi6JU4aAHVEYeXx4ATcqQBGpZDFhqogkMr4ChGAEoBZPkpVroTsvt08xCSlZeQBaAD5LcGtQZQAufVVsaCVoyBs7QNBguUQIqJjbPDjmEnIYVMLM8WzpXPyrdNjizmQkCtJsbEh9FMaMvD08VApoVAA3eVEayTr5ABoCpqK4lM7u3srl5WghaDK-DFI8XoB3bQoMNmOQbHhwcr4fUkOI1zjgUhAH4yh56FIpy+wAYRh44FQqGS8C432gNBAKGA0A4yGQpAA5jB8OZEBR9DxhqMJlNXIt+s0PiAMQALZGlMjNMY0FJINAoaAAChIaCQzGxXFaIEm8OEbRIAEoADp4GGgdSkaD6FjsTjIkDIeH4KTgACe0Bx8nx0GM+uANJgWh0GE+yAA1jxXA00gNVgBtYCIASQAC6+wAdKR-cx-QBxTjyEDMACK8Hkuso8G+hwAJI6XM6qsVoB6vfy-YhA8GwxHtDG44gE0nwKn05nttnc96C0WQ+G8JHy-HE8n5GmnZEKSsc57mwGg23S1HY93q7XOASeFbLvXXQa8LjkcrWBwuISRjASRhlwtnoc4sp+QClSrd+rNYa8T1-pBJpXzfgMWNul6KJrjr05iQGwmrDIg1zgHWg4upS+y+DeO5qlBZ7yKsPLYMo5gIaqe4nnk0FZqsj5breSEDksa4HNhd7Ib4qFwcgGEbjA244cAfBAA
     *
     * In general,
     *   1. start by dropping the collection and creating a new one for cleanliness.   The collection
     *      is created as a capped collection.
     *   2. start a timer where after 10 seconds, start inserting documents into the capped collection
     *   3. start a read of the capped collection.
     *   4. notice that before a document is written to the collection, the RM (or something) system is
     *      furiously creating cursors.   Once a document is written to the collection, the reading operates
     *      as expected and no more furious cursor creation happens
     */
    "show some tight cursor producing logic prior to an documents inserted into collection" in {

      val system: ActorSystem = app.injector.instanceOf[ActorSystem]
      implicit val executionContext = app.injector.instanceOf[ExecutionContext]
      implicit val mat: Materializer = app.injector.instanceOf[Materializer]
      val mongo = app.injector.instanceOf[ReactiveMongoApi]

      dropCollection
      Await.result(collection, 10.seconds)

      //
      // a Task on a timer that inserts documents into the capped collection (The Writer)
      //
      val task = new Runnable {
        override def run(): Unit = {
          implicit val executionContext = app.injector.instanceOf[ExecutionContext]
          val i = collection
            .map(c => {
              c.insert(ordered = true).one(
                BSONDocument(
                  "id" -> s"prime",
                  "message" -> "whatever",
                  "createdAt" -> OffsetDateTime.now()
                ))
          })
          Await.result(i, 5.seconds)
        }
      }

      //
      // schedule some inserts but after 10 seconds so our "Reader" is actively reading/waiting for data
      //
      system.scheduler.scheduleWithFixedDelay(
        10.seconds,
        (10000 * Math.random()).milliseconds)(task)

      //
      // wait for capped collection data (The Reader)
      //
      // NOTE/PROBLEM: until a message is placed into the capped collection, there is a tight cursor
      // producing effort happening identified by this log... [trace] r.a.c.GenericQueryBuilder$
      //
      val x = collection.flatMap(c => {
        c.find(BSONDocument("createdAt" -> BSONDocument("$gte" -> OffsetDateTime.now())))
          .tailable
          .awaitData
          .cursor[BSONDocument]()
          .documentSource()
          .runWith(akka.stream.scaladsl.Sink.foreach { doc =>
            println(s"- ${BSONDocument pretty doc}")
          })
      })
      Await.result(x, 60.seconds)
    }

    /**
     * sequencediagram.org URL for some swimlane views of this...
     *
     * https://sequencediagram.org/index.html?presentationMode=readOnly#initialData=C4S2BsFMAIGEFcBOBnA9o6BlADiAdgPSyKQCGwMAQpAGbowAiqAxvALaR7DIBQPpzYOmgBJPGBClwIAF6REPACblSAI1LIYsVOCiCQqPP0HCASgFkezQ8EQ7oAQQDWT0lZt3w0U2UXz3XJ7QAOqIYP6qqPB4yogAntCUdgDumgqR0bEJAKIAHhSIeFLZAG6cwHxiElKy8gC0AHzaupD6hgBcinbY0NYtbUZVoDVyiI3NeqAdzCTkMH2TBoPiw9Kj4zqLHZzISPOk2NiQir2brVNGPHioFNCoZRhDkmvyADQT50vtvQdHJwufQx8JKoVL1Bo+Uh+RDtTCYbJ3DDgQwAczq0jKJ1mih4kOhjQs7WApBA4DUUFe0FIyRJwAYKj4KLs8B6yTCt1IMWg2KpXOw8FU0mQAAseHkCkVwKVyo1QuEYdAUZx5HNoJAylweHKCht+hdvvg0sBoIoWOxyrjzLqtnhvjzTawOJqLATfPI7ZBkNhDJoTWanRU8eCQWCFXCEQAiaVcCM8TgnPjXW73eTeN2IV7i+SS6PAb6qVqkeC+iNiCWQYAR6DJUleaRsMDQKB4FHAYUEUAcAA6eDuNCw8NOeDwgLwlJE1c5xqE0HwBUQLOAPc5WM9xMQ0+FMGSwp0MAjqhSaW5vnwKJ7HGQyFISuQTdQqGwEZ7PYAKqg-dA2+Rx9AnLWvy3XokDQDAd04WdjWxZBl2gCNvRANBe1wXQqwdc0uGrYUQGYYU1S4eQYK4ICAQGaAaDsNheUcFxSCwKJEGYSAe2QWwyCo8p4mgdRNBOQwvxADhoAACgANgABmgTRrBiZAAEo+B4EM0ldKF3XDRF7xbdEQExE8oVxdMCXMIkSTJQVIEpalaXpYkeGcVxrVHdpSBoApoAkqTWkMRRkEpNlwjghCkOgFDwFjF0mjOAYPShP1HXKETgv4sKFMioMFXtf0LQy1ToW+ZAaV0UFQtQRCUtrQy1LGBplPdaA8iY7ALiIZFNCUo9wQy9oR3ySCVQuAdsiq-EIRM4lSXJSyqRpMBbLcZFH2gaJQC8S9ryVWc8CNY4rhuGAUwwCx3mi-UBJRYVjRmMhBtQftWBQdAiOEkg0CQJitoobbdJgOgMF2Eg5J7FbSSpeKMONRCtp2nFIo+GLoAAbVsARIAAXW5AA6UhMeYTGAHFlTCZgAEV4HkOJKHgUloQAEktJyEeRxBUYxxBsdxgmiZwsmKapmn5HpuHTq+JGUaYtmObxwmR2J3n4n58A6YZqK9VF5nWaxnHpe50nyYV6mlcFuMYjFfJs2KDVgFldl6qVWXVXVC1tXBeGzsNeRjXQgM9uTB402qk61Y6aBrmSQDyDBkdw4dANZzvD2N2OKzdEcEwUB7fASh0PTHwGmBrkQNgpHAOJMcZZlWVt3lVzildQoFIVRSzQpLZlBoXYVe387VK2tVtmq3dFxOvey50rVVm1YpOb2con7qTy9H0YFn50jNqzqw0HKMrdjeNoB4IA
     *
     * In general,
     *   1. start by dropping the collection and creating a new one for cleanliness.   The collection
     *      is created as a capped collection.
     *   2. start a timer where after 10 seconds, start simulating an external system POST-ing events
     *      that are inserted into the capped collection
     *   3. start a read of the capped collection with a long lived GET (browser SSE in real life)
     *   4. notice operations are normal
     *   5. after a timeout (60 seconds or so usually), I write a "poison pill" document to the collection
     *      so that the Reader (tailable, awaitData) actor can throw an Exception to interrupt the
     *      process.  If I don't do that manually, the internet components (google load balancer) in between
     *      will kill the GET connection but the reactivemongo Cursor will continue reading forever (which
     *      is expected in general)
     *   6. Reader reads the "poison pill" and throws an Exception killing the cursor and interrupting that
     *      reading wait.
     *   7. now we start a new tailable, awaitData connection but RM system immediately goes into a furious loop
     *      of creating cursors... until a Document is inserted into the collection.
     */
    "should show right-loop-like cursor producing logic" in {

      val system: ActorSystem = app.injector.instanceOf[ActorSystem]
      implicit val executionContext = app.injector.instanceOf[ExecutionContext]
      implicit val mat: Materializer = app.injector.instanceOf[Materializer]
      val mongo = app.injector.instanceOf[ReactiveMongoApi]

      dropCollection

      // test through a controller (trying to get closer to my official setup which is browser based SSE)
      lazy val home = app.injector.instanceOf[HomeController]
      val controller = home

      //
      // a Task on a timer that simulates an external system POST which inserts a document
      // into the capped collection (The Writer)
      //
      val task = new Runnable {
        override def run(): Unit = {
          val postResult: Future[Result] = controller.generateEvent().apply(
            FakeRequest(POST, "/post")
              .withMethod("POST")
              .withJsonBody(Json.obj())
          )
          println(s"event-generator posted a message ... ${contentAsString(postResult)}")
        }
      }

      //
      // somewhat randomly, post a message to the capped collection within a minute (run the Writer task)
      //
      (1 to 5).foreach(_ => {
        system.scheduler.scheduleWithFixedDelay(
          // (1000L + 10000 * Math.random()).milliseconds,
          10.seconds,
          (60000 * Math.random() * .5).milliseconds)(task)
      })

      // this represents a browser/client listening on the capped collection for messages.
      // should reproduce some cursor related issues within the 30 minutes
      /*
        val result: Future[Result] = controller.index().apply(FakeRequest())
        val bodyText: String       = contentAsString(result)(Timeout(30.minutes), mat)
        bodyText mustBe "ok"
       */
      (1 to 10).foreach(count => {
        println(s"browser-side start SSE watching iteration ${count}")
        val result: Future[Result] = controller.sseReadEventsUntilDone().apply(FakeRequest())
        val shouldFailBecauseOfTimeout = Try {
          val bodyText: String = contentAsString(result)(Timeout(5.minutes), mat)
          println("SHOULD NOT GET HERE")
          bodyText mustBe "ok"
          true
        }
        println(s"browser-side expected timeout... ${shouldFailBecauseOfTimeout}")

        // pause... browser side does just a bit
        Await.ready(akka.pattern.after(1.second, system.scheduler)(Future.unit), 2.second)
        println("timeout - continue")

        // immediately send a new "Event"
        if (false) {
          system.scheduler.scheduleOnce(10.milliseconds) {
            val postResult: Future[Result] = controller.generateEvent().apply(
              FakeRequest(POST, "/post")
                .withMethod("POST")
                .withJsonBody(Json.obj())
            )
            println(s"event-generator posted a priming message ... ${contentAsString(postResult)}")
          }
        }

        shouldFailBecauseOfTimeout.isFailure mustBe true
      })

    }
  }

}
