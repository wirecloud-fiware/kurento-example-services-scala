import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit._
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import handlers.{CrowdDetectorActor, One2OneActor}
import handlers.commonCases.{pipes, userRegistry, roisFromLists, ROIConf}
import jsonEncoders.CrowdDetectorJson._
import jsonEncoders.One2OneJson._
import kurento.helpers._
import kurento.actors._
import org.kurento.client._
import org.kurento.module.crowddetector._
import org.scalatest._
import play.api.Application
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Success, Try}
import scala.sys.process._
// class TT extends JsonRpcClient {
//   override def connect = { println("connected") }
//   override def close = {}
// }

case class DummyKurento(val client: KurentoClient)

object DummyKurento extends KurentoHelp {
  private val ws_u = safeGetWs("ws://localhost:8889", "kurento.test.ws")
  val client = KurentoClient.create(ws_u)
}

class KurentoActorTest extends TestKit(ActorSystem("testKurentoActor", ConfigFactory.parseString(KurentoActorTest.config)))  with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {
  override def beforeAll {
  }
  override def afterAll {
    shutdown()
  }

  def create_actor()(implicit system: ActorSystem) = {
    TestActorRef(new KurentoActor(DummyKurento.client))
  }

  val defaulttimeout = "overriding <3"
  var mut_pipe: Option[MediaPipeline] = None

  "With response" should {

    "CreateMediaPipeline" in new WithApplication {
      val kurentoactor = create_actor()
      kurentoactor ! CreateMediaPipeline
      val Success(pipe) = expectMsgType[Success[MediaPipeline]]

      mut_pipe = Some(pipe)
    }

    "CreateWebRtcEndp" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get
      kurentoactor ! CreateWebRtcEndp(pipeline)

      val Success(_) = expectMsgType[Success[WebRtcEndpoint]]
    }

    "CreatePlayerEndpoint" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get

      kurentoactor ! CreatePlayerEndpoint(pipeline, "http://someurl.com")

      val Success(_) = expectMsgType[Success[PlayerEndpoint]]
    }

    "CreateCrowdDetectorFilter" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get

      val rois: List[RegionOfInterest] = roisFromLists(
        List(List(Position(6,6), Position(4,4), Position(1,1))))

      kurentoactor ! CreateCrowdDetectorFilter(pipeline, rois)

      val Success(_) = expectMsgType[Success[CrowdDetectorFilter]]
    }

    "ProcessOffer" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get
      kurentoactor ! CreateWebRtcEndp(pipeline)

      val Success(webrtc) = expectMsgType[Success[WebRtcEndpoint]]

      kurentoactor ! ProcessOffer(webrtc, "test")

      val Success(sdp) = expectMsgType[Success[String]]
      sdp should be("v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n")
    }
  }

  "Without answer" should {
    "ListenerEndOfStream" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get

      kurentoactor ! CreatePlayerEndpoint(pipeline, "http://someurl.com")

      val Success(player) = expectMsgType[Success[PlayerEndpoint]]

      kurentoactor ! ListenerEndOfStream(player, new EventListener[EndOfStreamEvent]() {
        override def onEvent(e: EndOfStreamEvent) = println("Finished!")
      })

      expectNoMsg(100 millis)
    }

    "Play" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get

      kurentoactor ! CreatePlayerEndpoint(pipeline, "http://someurl.com")

      val Success(player) = expectMsgType[Success[PlayerEndpoint]]

      kurentoactor ! Play(player)

      expectNoMsg(100 millis)
    }

    "SetMaxVideoRecvB" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get
      kurentoactor ! CreateWebRtcEndp(pipeline)
      val Success(webrtc) = expectMsgType[Success[WebRtcEndpoint]]

      kurentoactor ! SetMaxVideoRecvB(webrtc, 0)

      expectNoMsg(100 millis)
    }

    "SetMaxVideoSendB" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get
      kurentoactor ! CreateWebRtcEndp(pipeline)
      val Success(webrtc) = expectMsgType[Success[WebRtcEndpoint]]

      kurentoactor ! SetMaxVideoSendB(webrtc, 9999999)

      expectNoMsg(100 millis)
    }

    "SetMinVideoSendB" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get
      kurentoactor ! CreateWebRtcEndp(pipeline)
      val Success(webrtc) = expectMsgType[Success[WebRtcEndpoint]]

      kurentoactor ! SetMinVideoSendB(webrtc, 9999999)

      expectNoMsg(100 millis)
    }

    "Release" in new WithApplication {
      val kurentoactor = create_actor()
      kurentoactor ! CreateMediaPipeline
      val Success(pipe) = expectMsgType[Success[MediaPipeline]]

      kurentoactor ! Release(pipe)

      expectNoMsg(100 millis)
    }

    "AddFilter" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get

      val rois: List[RegionOfInterest] = roisFromLists(
        List(List(Position(6,6), Position(4,4), Position(1,1))))

      kurentoactor ! CreateCrowdDetectorFilter(pipeline, rois)

      val Success(crowdfilter) = expectMsgType[Success[CrowdDetectorFilter]]

      kurentoactor ! AddFilter(crowdfilter,{ e:JsValue => Json.fromJson[CrowdOutJson](e).get } ,kurentoactor)

      expectNoMsg(100 millis)
    }

    "Connect" in new WithApplication {
      val kurentoactor = create_actor()
      val pipeline = mut_pipe.get

      kurentoactor ! CreateWebRtcEndp(pipeline)
      val Success(webrtc) = expectMsgType[Success[WebRtcEndpoint]]
      kurentoactor ! CreateWebRtcEndp(pipeline)
      val Success(webrtc2) = expectMsgType[Success[WebRtcEndpoint]]

      kurentoactor ! Connect(webrtc, webrtc2)
    }
  }

  "Lists" should {
    // "test" in new WithApplication {
    //   1 should be(1)
    // }
  }
}

object KurentoActorTest {
  val config = """
akka.loggers = ["akka.testkit.TestEventListener"]
akka.stdout-loglevel = "OFF"
akka.loglevel = "OFF"
"""
}



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



class One2OneTest extends TestKit(ActorSystem("testOne2OneActor", ConfigFactory.parseString(One2OneTest.config))) with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    shutdown()
  }
  // IMPLICIT CONVERSIONS
  implicit def fromJson(js: JsObject) = {
    Try(Json.fromJson[One2OneInJson](js).get)
  }
  implicit def expected(js: JsValue) = {
    Json.fromJson[One2OneOutJson](js).get
  }
  // IMPLICIT FUNCTIONS
  implicit class ActorRefOne(val a: ActorRef) {
    def <=<(m: Try[One2OneInJson]) = a ! m
  }
  implicit class TestProbeOne(val probe: TestProbe) {
    def ?=?(msg: One2OneOutJson) = {
      val l = probe.expectMsgType[One2OneOutJson]
      l should be(msg)
    }

    def ?=!() = {
      val l = probe.expectMsgType[One2OneOutJson]
      println(l)
    }

    def expectN(n: Int, t: One2OneOutJson*) = {
      val l = collection.mutable.ListBuffer[One2OneOutJson]()
      (0 until n) map { n =>
        l += probe.expectMsgType[One2OneOutJson]
      }
      t map { v =>
        l.contains(v) should be(true)
        l -= v
      }
      l should be(collection.mutable.ListBuffer[One2OneOutJson]())
    }
  }

  def unregister(a: ActorRef, p: TestProbe, name: String = "") = {
    if (name != "")
      checkName(name)
    a <=< Json.obj("id" -> "unregister")
    p.receiveN(1)
    if (name != "")
      checkName(name, false)
  }

  def checkName(name: String, value: Boolean = true) = {
    userRegistry.getByName(name).isDefined should be(value)
  }

  def probe_actor()(implicit system: ActorSystem) = {
    val probe = TestProbe()
    (probe, system.actorOf(Props(new One2OneActor(probe.ref, DummyKurento))))
  }

  "The name registry" should {
    "let register one name" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "alwaysregister")
      probe ?=? Json.obj("id" -> "registerResponse", "response" -> "accepted")
      checkName("alwaysregister")
    }

    "not have any name at start" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      oneactor <=< Json.obj("id" -> "whoami")
      probe ?=? Json.obj("id" -> "message", "message" -> "You don't have any name")
    }

    "let unregister" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "tounregister")
      probe.receiveN(1)
      checkName("tounregister")
      oneactor <=< Json.obj("id" -> "unregister")
      probe ?=? Json.obj("id" -> "message", "message" -> "Succesfully unregistered: tounregister")
      checkName("tounregister", false)
    }

    "let register the name unregistered" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "tounregister")
      probe ?=? Json.obj("id" -> "registerResponse", "response" -> "accepted")

      unregister(oneactor, probe, "tounregister")
      oneactor <=< Json.obj("id" -> "register", "name" -> "tounregister")
      probe ?=? Json.obj("id" -> "registerResponse", "response" -> "accepted")

      unregister(oneactor, probe, "tounregister")
    }

    "not let register twice" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "tounregister")
      probe ?=? Json.obj("id" -> "registerResponse", "response" -> "accepted")

      oneactor <=< Json.obj("id" -> "register", "name" -> "tounregister2")
      probe ?=? Json.obj("id" -> "registerResponse", "response" -> "rejected", "message" -> "You are already registered with the name tounregister")

      checkName("tounregister2", false)

      unregister(oneactor, probe, "tounregister")
    }

    "let have two nawes (in two actors)" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()


      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveN(1)

      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe2 ?=? Json.obj("id" -> "registerResponse", "response" -> "accepted")

      unregister(oneactor, probe, "rock1")
      unregister(oneactor2, probe2, "rock2")
    }

    "not let register same name in other actor" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveN(1)

      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe2 ?=? Json.obj("id" -> "registerResponse", "response" -> "rejected", "message" -> "User rock1 already registered")

      unregister(oneactor, probe, "rock1")
    }

    "can register a name unregistered" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveN(1)

      unregister(oneactor, probe, "rock1")

      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe2 ?=? Json.obj("id" -> "registerResponse", "response" -> "accepted")
      unregister(oneactor2, probe2, "rock1")
    }
  }

  "Call actions" should {
    "receive the incoming call" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }

    "not let call an unregistered name" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock3", "from" -> "rock1", "sdpOffer" -> "test")

      probe ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "user rock3 is not registered")

      probe2.expectNoMsg(100 millis)

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }

    "not let call if not registered" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()

      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "You have to register before")
      probe2.expectNoMsg(100 millis)

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }

    "not let call if not 'from' and name registered are not the same" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock3", "sdpOffer" -> "test")

      probe ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "The name you said rock3 not match with yours.")
      probe2.expectNoMsg(100 millis)

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }
  }

  "IncomingCallResponse actions" should {
    "when unregister while waiting to accept, receive stopCommunication" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor, probe, "rock1")
      probe2 ?=? Json.obj("id" -> "stopCommunication")  // When unregister, he receives the "stop" call message

      pipes.pipelines.size should be(0)

      unregister(oneactor2, probe2, "rock2")
    }

    "let reject it" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "rejected")

      pipes.pipelines.size should be(0)

      probe2.expectNoMsg(100 millis)
      probe ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "user declined")

      pipes.pipelines.size should be(0)

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }

    "not let accept if unregister the caller" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor, probe)
      probe2.receiveOne(100 millis) // No communication msg

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")

      pipes.pipelines.size should be(0)

      probe2 ?=? Json.obj("id" -> "stopCommunication", "message" -> "unknown from = rock1")

      pipes.pipelines.size should be(0)

      unregister(oneactor2, probe2)
    }

    "not let accept if you unregister before" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor2, probe2)
      probe.receiveOne(100 millis)

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")

      pipes.pipelines.size should be(0)

      probe2 ?=? Json.obj("id" -> "stopCommunication", "message" -> "You have to register before")

      pipes.pipelines.size should be(0)

      unregister(oneactor, probe)
    }

    "let accept the call and both receive the sdpAnswer" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2")
      probe.receiveOne(100 millis)
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      pipes.pipelines.size should be(0)  // No communication

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")

      probe ?=? Json.obj("id" -> "startCommunication", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n", "response" -> "accepted")

      probe2 ?=? Json.obj("id" -> "startCommunication", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n")

      pipes.pipelines.size should be(2)  // Communication

      unregister(oneactor, probe)
      probe2.receiveOne(100 millis)
      pipes.pipelines.size should be(0)  // The pipe should be closed :-)
      unregister(oneactor2, probe2)
    }
  }
}

object One2OneTest {
  val config = """
akka.loggers = ["akka.testkit.TestEventListener"]
akka.stdout-loglevel = "OFF"
akka.loglevel = "OFF"
"""
}
