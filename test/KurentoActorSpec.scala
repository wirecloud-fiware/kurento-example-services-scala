import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit._
import com.typesafe.config.ConfigFactory
import handlers.commonCases.{ROIConf, roisFromLists}
import jsonEncoders.CrowdDetectorJson.{Position, CrowdOutJson}
import kurento.actors._
import kurento.helpers._
import org.kurento.client._
import org.kurento.module.crowddetector._
import org.scalatest._
import play.api.Application
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.Success

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
    //   1 should be(1))))
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
