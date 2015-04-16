package kurento

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import com.netaporter.uri.Uri
import com.netaporter.uri.Uri.parse
import jsonEncoders.CrowdDetectorJson.CrowdOutJson
import org.kurento.client._
import org.kurento.module.crowddetector.{CrowdDetectorDirectionEvent, CrowdDetectorFilter, CrowdDetectorFluidityEvent, CrowdDetectorOccupancyEvent, RegionOfInterest}
import play.api.Play
import play.api.libs.json.{JsObject, Json}
import scala.annotation.tailrec
import scala.collection.JavaConversions.seqAsJavaList
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try


object helpers {
  trait KurentoHelp {
    def client: KurentoClient
  }
  def safeGetWs(default: String, setting: String) = {
    val c_url = Play.current.configuration.getString(setting).getOrElse(default)
    parse(c_url).scheme match {
      case Some("ws") | Some("wss") => c_url
      case _ => default
    }
  }
  // Kurento Client
  case class wskurento (val client: KurentoClient)

  object wskurento extends KurentoHelp {
    // private val default_url = "ws://130.206.81.33:8888/kurento"
    // private val config_url = Play.current.configuration.getString("kurento.ws").getOrElse(default_url)
    // private val ws_url = parse(config_url).scheme match {
    //   case Some("ws") | Some("wss") => config_url
    //   case _ => default_url
    // }
    private val ws_url = safeGetWs("ws://130.206.81.33:8888/kurento", "kurento.ws")
    val client = KurentoClient.create(ws_url)
  }
}

object actors {
  // Kurento Actor (In testing now!)

  implicit val defaulttimeout = Timeout(60 seconds) // Long timeout because have to contact

  trait KurentoActorAction
  case object CreateMediaPipeline extends KurentoActorAction
  case class CreateWebRtcEndp(pipeline: MediaPipeline) extends KurentoActorAction
  case class CreatePlayerEndpoint(pipeline: MediaPipeline, url: String) extends KurentoActorAction
  case class CreateCrowdDetectorFilter(pipeline: MediaPipeline, rois: List[RegionOfInterest]) extends KurentoActorAction
  case class ProcessOffer(webrtc: WebRtcEndpoint, sdp: String) extends KurentoActorAction
  case class Release(pipeline: MediaPipeline) extends KurentoActorAction
  case class AddFilter(crowdfilter: CrowdDetectorFilter, f: JsObject => CrowdOutJson, out: ActorRef) extends KurentoActorAction
  case class Connect(from: MediaElement, to: MediaElement) extends KurentoActorAction
  case class ListenerEndOfStream(player: PlayerEndpoint, listener: EventListener[EndOfStreamEvent]) extends KurentoActorAction
  case class Play(player: PlayerEndpoint) extends KurentoActorAction
  case class Stop(player: PlayerEndpoint) extends KurentoActorAction
  case class SetMaxVideoRecvB(webrtc: WebRtcEndpoint, v: Int) extends KurentoActorAction
  case class SetMaxVideoSendB(webrtc: WebRtcEndpoint, v: Int) extends KurentoActorAction
  case class SetMinVideoSendB(webrtc: WebRtcEndpoint, v: Int) extends KurentoActorAction

  class KurentoActor(client: KurentoClient) extends Actor {
    def receive = {
      case v: KurentoActorAction => {
        // val f = Future { handleAction(v) }
        // f.map {
        // _ match {
        handleAction(v) match {
            case Some(s) => {
              // println(s)
              sender ! s
            }
            case None =>
          }
        }
      case l: List[_] => {
        handleList(l)
      }
      case _ => println("wut?")
    }

    @tailrec
    private def handleList[T] (l: List[T]): Any = l match {
      case (hd: KurentoActorAction) :: tl  => {
        handleAction(hd)
        handleList(tl)
      }
      case _ =>
    }

    def handleAction(action: KurentoActorAction): Option[Try[Any]] = action match {
      case CreateMediaPipeline => Some(Try(client.createMediaPipeline()))
      case CreateWebRtcEndp(pipeline) => Some(Try(new WebRtcEndpoint.Builder(pipeline).build()))
      case CreatePlayerEndpoint(pipeline, url) => Some(Try(new PlayerEndpoint.Builder(pipeline, url).build()))
      case CreateCrowdDetectorFilter(pipeline, rois) => Some(Try(new CrowdDetectorFilter.Builder(pipeline, rois).build()))
      case ProcessOffer(webrtc, sdp) => Some(Try(webrtc.processOffer(sdp)))
      case ListenerEndOfStream(p, l) => p.addEndOfStreamListener(l); None
      case Play(p) => p.play(); None
      case Stop(p) => p.stop(); None
      case SetMaxVideoRecvB(w, v) => w.setMaxVideoRecvBandwidth(v); None
      case SetMaxVideoSendB(w, v) => w.setMaxVideoSendBandwidth(v); None
      case SetMinVideoSendB(w, v) => w.setMinVideoSendBandwidth(v); None
      case Release(pipeline) => Try(pipeline.release()); None
      case AddFilter(crowdfilter, f, out) => Try(addFilters(crowdfilter, f, out)); None
      case Connect(from, to) => Try(from.connect(to)); None
    }

    def addFilters(crowdfilter: CrowdDetectorFilter, f: JsObject => CrowdOutJson, out: ActorRef) = {
      crowdfilter.addCrowdDetectorOccupancyListener(new EventListener[CrowdDetectorOccupancyEvent]() {
        override def onEvent(ev: CrowdDetectorOccupancyEvent) {
          val data = Json.obj("roiID" -> ev.getRoiID, "occupancyPercentage" -> ev.getOccupancyPercentage, "occupancyLevel" -> ev.getOccupancyLevel)
          val msg = Json.obj(
            "id" -> "crowdDetectorOccupancy",
            "event_data" -> data.toString
          )
          out ! f(msg)
        }
      })
      crowdfilter.addCrowdDetectorFluidityListener(new EventListener[CrowdDetectorFluidityEvent]() {
        override def onEvent(ev: CrowdDetectorFluidityEvent) {
          val data = Json.obj("roiID" -> ev.getRoiID, "fluidityPercentage" -> ev.getFluidityPercentage, "fluidityLevel" -> ev.getFluidityLevel)
          val msg = Json.obj(
            "id" -> "crowdDetectorFluidity",
            "event_data" -> data.toString
          )
          out ! f(msg)

        }
      })
      crowdfilter.addCrowdDetectorDirectionListener(new EventListener[CrowdDetectorDirectionEvent]() {
        override def onEvent(ev: CrowdDetectorDirectionEvent) {
          val data = Json.obj("roiID" -> ev.getRoiID, "directionAngle" -> ev.getDirectionAngle)
          val msg = Json.obj(
            "id" -> "crowdDetectorDirection",
            "event_data" -> data.toString
          )
          out ! f(msg)
        }
      })
    }

  }

  object KurentoActor {
    def props(client: KurentoClient) = Props(new KurentoActor(client))
  }
}
