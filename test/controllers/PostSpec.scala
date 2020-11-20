package controllers

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

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

class PostSpec extends PlaySpec with GuiceOneServerPerSuite with ScalaFutures with Results {

  "tailable cursor" should {
    "should show a ton of messages in mongodb in a minute or two" in {

      lazy val system: ActorSystem = app.injector.instanceOf[ActorSystem]
      implicit lazy val executionContext = app.injector.instanceOf[ExecutionContext]
      implicit lazy val mat: Materializer = app.injector.instanceOf[Materializer]

      lazy val mongo = app.injector.instanceOf[ReactiveMongoApi]
      lazy val home = app.injector.instanceOf[HomeController]

      val controller             = home

      val task = new Runnable {
        override def run(): Unit = {
          val postResult: Future[Result] = controller.post().apply(
            FakeRequest(POST, "/post")
              .withMethod("POST")
              .withJsonBody(Json.obj())
          )
          println(s"event-generator posted a message ... ${contentAsString(postResult)}")
        }
      }

      (1 to 5).foreach(_ => {
        // somewhat randomly, post a message to the capped collection within a minute
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
        val result: Future[Result] = controller.index().apply(FakeRequest())
        val shouldFailBecauseOfTimeout = Try {
          val bodyText: String = contentAsString(result)(Timeout(5.minutes), mat)
          println("SHOULD NOT GET HERE")
          bodyText mustBe "ok"
          true
        }
        println(s"browser-side expected timeout... ${shouldFailBecauseOfTimeout}")
        //Await.ready(akka.pattern.after(1.second, system.scheduler)(Future.unit), 2.second)
        //println("timeout - continue")

        // immediately send a new "Event"
        if (false) {
          system.scheduler.scheduleOnce(10.milliseconds) {
            val postResult: Future[Result] = controller.post().apply(
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
