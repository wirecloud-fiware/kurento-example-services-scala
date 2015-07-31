import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit._
import com.typesafe.config.ConfigFactory
import handlers.CrowdDetectorActor
import handlers.commonCases.{ROIConf, roisFromLists}
import jsonEncoders.CrowdDetectorJson._
import kurento.actors._
import kurento.helpers._
import org.kurento.client._
import org.scalatest._
import play.api.Application
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try

class CrowdDetectorTest extends TestKit(ActorSystem("testkit", ConfigFactory.parseString(CrowdDetectorTest.config))) with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    shutdown()
  }
  // IMPLICIT CONVERSIONS
  implicit def fromJson(js: JsObject) = {
    Try(Json.fromJson[CrowdInJson](js).get)
  }
  implicit def expected(js: JsValue) = {
    Json.fromJson[CrowdOutJson](js).get
  }
  // IMPLICIT FUNCTIONS
  implicit class ActorRefCrowd(val a: ActorRef) {
    def <=<(m: Try[CrowdInJson]) = a ! m
  }
  implicit class TestProbeCrowd(val probe: TestProbe) {
    def ?=?(msg: CrowdOutJson) = {
      val l = probe.expectMsgType[CrowdOutJson]
      l should be(msg)
    }

    def expectN(n: Int, t: CrowdOutJson*) = {
      val l = collection.mutable.ListBuffer[CrowdOutJson]()
      (0 until n) map { n =>
        l += probe.expectMsgType[CrowdOutJson]
      }
      t map { v =>
        l.contains(v) should be(true)
        l -= v
      }
      l should be(collection.mutable.ListBuffer[CrowdOutJson]())
    }

    def ?=!() = {
      val l = probe.expectMsgType[CrowdOutJson]
      println(l)
    }
  }

  def probe_actor()(implicit system: ActorSystem) = {
    val probe = TestProbe()
    (probe, system.actorOf(Props(new CrowdDetectorActor(probe.ref, DummyKurento))))
  }

  // "FakeTest" should {
  //   "always be true" in new WithApplication {
  //     1 should be (1)
  //   }
  // }

  "Single value messages" should {
    "answer id not exist" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "not exist")
      probe ?=? Json.obj("id" -> "error", "message" -> "Your id 'not exist' with the other parameters is nothing to me!")
    }

    "not send nothing when stop" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "stop")
      probe.expectNoMsg(500 millis)
    }
  }

  "start action messages" should {
    "answer an sdpAnswer when normal" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "start", "sdpOffer" -> "test", "dots" -> List(List(Position(1, 2))))
      probe ?=? Json.obj("id" -> "startResponse", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n")
    }

    "receive events after when normal" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "start", "sdpOffer" -> "test", "dots" -> List(List(Position(9, 7), Position(1, 1)))) // This positions is the "key" to send events
      probe.receiveN(1)  // Ignore the sdpAnswer

      probe.expectN(3,
        Json.obj("id" -> "crowdDetectorDirection", "event_data" -> "{\"roiID\":\"roi1\",\"directionAngle\":20.0}"),
        Json.obj("id" -> "crowdDetectorOccupancy", "event_data" -> "{\"roiID\":\"roi1\",\"occupancyPercentage\":80.0,\"occupancyLevel\":3}"),
        Json.obj("id" -> "crowdDetectorFluidity", "event_data" -> "{\"roiID\":\"roi1\",\"fluidityPercentage\":80.0,\"fluidityLevel\":3}"))
    }

    "not let two consecutive actions" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "start", "sdpOffer" -> "test", "dots" -> List(List(Position(1, 2))))
      probe ?=? Json.obj("id" -> "startResponse", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n")

      crowdactor <=< Json.obj("id" -> "start", "sdpOffer" -> "test", "dots" -> List(List(Position(1, 2))))
      probe ?=? Json.obj("id" -> "error", "message" -> "Close current session before starting new one.")
    }

    "let stop after start" in new WithApplication {
      val (probe, crowdactor) = probe_actor()

      crowdactor <=< Json.obj("id" -> "start", "sdpOffer" -> "test", "dots" -> List(List(Position(1, 2))))
      probe.receiveN(1)

      crowdactor <=< Json.obj("id" -> "stop")
      probe.expectNoMsg(500 millis)
    }
  }

  "getVideo without filter action messages" should {
    "not let a not existing path" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "getVideo", "sdpOffer" -> "test", "url" -> "notexists", "filter" -> false, "dots" -> List[List[Position]]())
      probe ?=? Json.obj("id" -> "getVideo", "message" -> "Path notexists can't be retrieved", "accepted" -> false)
    }

    "answer for an http link" in new WithApplication {
      val (probe, crowdactor) = probe_actor()
      crowdactor <=< Json.obj("id" -> "getVideo", "sdpOffer" -> "test", "url" -> "http://test.com/file.mp4", "filter" -> false, "dots" -> List[List[Position]]())
      probe ?=? Json.obj("id" -> "getVideo", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n", "accepted" -> true, "filter" -> false)
    }
  }

  "Websocket can't connect" should {
    val probe = TestProbe()
    object NotExist extends KurentoHelp {
      val client = KurentoClient.create("ws://blablablabla.notexist")
    }
    "return exception with start action" in new WithApplication {
      val crowdactor = system.actorOf(Props(new CrowdDetectorActor(probe.ref, NotExist)))
      crowdactor <=< Json.obj("id" -> "start", "sdpOffer" -> "test", "dots" -> List(List(Position(1, 2))))
      probe ?=? Json.obj("id" -> "error", "message" -> "Exception connecting to WebSocket server")
    }
  }
}

object CrowdDetectorTest {
  val config = """
akka.loggers = ["akka.testkit.TestEventListener"]
akka.stdout-loglevel = "OFF"
akka.loglevel = "OFF"
"""
}
