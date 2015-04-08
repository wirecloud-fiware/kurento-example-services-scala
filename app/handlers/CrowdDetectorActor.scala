package handlers

import akka.actor.{Actor, ActorRef, PoisonPill, Props, actorRef2Scala}
import akka.pattern.ask
import com.netaporter.uri.Uri.parse
import handlers.commonCases.{ROIConf, buildJsError, roisFromLists}
import java.io.File
import jsonEncoders.CrowdDetectorJson._
import kurento.actors._
import kurento.helpers._
import org.kurento.client.{EndOfStreamEvent, EventListener, MediaPipeline, PlayerEndpoint, WebRtcEndpoint}
import org.kurento.module.crowddetector._
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import scala.async.Async.{async, await}
import scala.collection.JavaConversions.seqAsJavaList
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class CrowdDetectorActor(out: ActorRef, kurento: KurentoHelp) extends Actor {

  var mut_pipe: Option[MediaPipeline] = None

  val kurentoActor = Akka.system.actorOf(KurentoActor.props(kurento.client), name = s"kurentoactor-crowd-${out.hashCode()}")

  val logger: Logger = Logger("crowddetector")

  def sendSender(js: Option[JsObject]) = js foreach { out ! safeToJsonOut(_) }

  def release(): Unit = {
    mut_pipe foreach { kurentoActor ! Release(_) }
    mut_pipe = None
  }

  def not_pipe_exec(b: => Option[JsObject]) = {
    if (mut_pipe.nonEmpty)
      Some(buildJsError("Close current session before starting new one."))
    else b
  }

  def safeToJsonOut(js: JsValue) = {
    Json.fromJson[CrowdOutJson](js).getOrElse(CrowdOutJson())
  }

  def generalFailure = PartialFunction({ e: Throwable =>
    release()
    sendSender(Some(buildJsError(e.getMessage())))
  })

  def videoFailure = PartialFunction({ e: Throwable =>
    release()
    sendSender(Some(Json.obj("id" -> "getVideo", "accepted" -> false, "response" -> e.getMessage, "message" -> e.getMessage)))
  })

  def path_inside(c: String, p: String): Boolean = {
    val child = c.stripSuffix("/")
    val parent = p.stripSuffix("/")
    parent.lastIndexOf(child, 0) == 0 && (
      parent.length == child.length ||
      parent(child.length) == '/'
    )
  }

  def parseFile(url: String): Option[String] = {
    val base_path = "/home/miguel"
    //val base_path = "/home/conwet/kurento-tutorial-node/kurento-crowddetector"
    val nurl = if (url.startsWith("/")) new File(url).toURI().normalize().getPath
    else new File(base_path, url).toURI().normalize().getPath
    if (path_inside(base_path, nurl) && new File(nurl).exists)
      Some(s"file://${nurl}")
    else None
  }

  def parseUrl(url: String): Option[String] = parse(url).scheme match {
    case Some("http") | Some("https") => Some(url)
    case Some(_) => None
    case _ => parseFile(url)
  }

  def addFilters(crowdfilter: CrowdDetectorFilter) = AddFilter(crowdfilter, safeToJsonOut, out)

  def asyncConnectFilterWebRtc(pipeline: MediaPipeline, webrtc: WebRtcEndpoint, dots: List[List[Position]], player: Option[PlayerEndpoint]) = async {
    val rois: List[RegionOfInterest] = roisFromLists(dots)
    val crowddetector = await { ask(kurentoActor, CreateCrowdDetectorFilter(pipeline, rois)).mapTo[Try[CrowdDetectorFilter]] }.get

    val l = collection.mutable.ListBuffer[KurentoActorAction]()
    player match {
      case Some(p) => l += Connect(p, crowddetector)
      case None => l += Connect(webrtc, crowddetector)
    }
    l += Connect(crowddetector, webrtc)
    l += addFilters(crowddetector)
  }

  def fullasync(sdp: String, dots: List[List[Position]]) = async {
    val pipeline = await { ask(kurentoActor, CreateMediaPipeline).mapTo[Try[MediaPipeline]] }.get
    mut_pipe = Some(pipeline)

    val webrtc = await { ask(kurentoActor, CreateWebRtcEndp(pipeline)).mapTo[Try[WebRtcEndpoint]] }.get
    val connectl = await { asyncConnectFilterWebRtc(pipeline, webrtc, dots, None) }

    kurentoActor ! connectl.toList

    val result = await { ask(kurentoActor, ProcessOffer(webrtc, sdp)).mapTo[Try[String]] }.get
    sendSender(Some(Json.obj("id" -> "startResponse", "sdpAnswer" -> result)))
  }

  def doubleWebRtc(sdp: String, dots: List[List[Position]]) = {
    fullasync(sdp, dots) onFailure generalFailure
    None
  }

  def fullAsyncKurentoFullVideo(sdp: String, url: String, dots: Option[List[List[Position]]]) = async {
    val pipeline = await { ask(kurentoActor, CreateMediaPipeline).mapTo[Try[MediaPipeline]] }.get
    mut_pipe = Some(pipeline)
    val webrtc = await { ask(kurentoActor, CreateWebRtcEndp(pipeline)).mapTo[Try[WebRtcEndpoint]] }.get
    val player = await { ask(kurentoActor, CreatePlayerEndpoint(pipeline, url)).mapTo[Try[PlayerEndpoint]] }.get

    val actions = collection.mutable.ListBuffer[KurentoActorAction]()
    actions ++= List(
      Connect(player, webrtc),
      SetMaxVideoRecvB(webrtc, 0),
      SetMaxVideoSendB(webrtc, 9999999),
      SetMinVideoSendB(webrtc, 9999999)
    )

    if (dots.nonEmpty)
      actions ++= await { asyncConnectFilterWebRtc(pipeline, webrtc, dots.get, Some(player)) }

    kurentoActor ! actions.toList

    val result = await { ask(kurentoActor, ProcessOffer(webrtc, sdp)).mapTo[Try[String]] }.get
    val listener = ListenerEndOfStream(player, new EventListener[EndOfStreamEvent]() {
      override def onEvent(e: EndOfStreamEvent) = player.play()
    })

    kurentoActor ! List(listener, Play(player))
    sendSender(Some(Json.obj("id" -> "getVideo", "accepted" -> true, "response" -> "accepted", "filter" -> dots.nonEmpty, "sdpAnswer" -> result)))
  }

  def getFullVideo(sdp: String, url: String, dots: Option[List[List[Position]]]) = parseUrl(url) match {
    case Some(uri) => {
      fullAsyncKurentoFullVideo(sdp, uri, dots) onFailure videoFailure
      None
    }
    case None => Some(Json.obj("id" -> "getVideo", "accepted" -> false, "response" -> "Can't send it", "message" -> ("Path " + url + " can't be retrieved")))
  }

  def handleOnlyId(id: String) = id match {
    case "stop" => {
      release()
      None
    }
    case _ => Some(buildJsError(s"Your id '${id}' with the other parameters is nothing to me!"))
  }

  def handleCrowdIn(jsval: CrowdInJson): Option[JsObject] = jsval match {
    case CrowdInJson("start", Some(sdp), None, None, Some(dots)) => {
      logger.info("Petition to build filter from WebRTC (Camera)")
      not_pipe_exec { doubleWebRtc(sdp, dots) }
    }
    case CrowdInJson("getVideo", Some(sdp), Some(url), Some(false), Some(List())) => {
      logger.info("Petition to send video to WebRTC")
      not_pipe_exec { getFullVideo(sdp, url, None) }
    }
    case CrowdInJson("getVideo", Some(sdp), Some(url), Some(true), dots) => {
      logger.info("Petition to send video with filter to WebRTC")
      not_pipe_exec { getFullVideo(sdp, url, dots) }
    }
    case CrowdInJson(id, None, None, None, None) => {
      logger.info(s"Petition with only the id ${id}")
      handleOnlyId(id)
    }
    case _ => {
      logger.error(s"The options of ${Json.toJson(jsval)} don't match")
      Some(buildJsError(s"Your message ${Json.toJson(jsval)} don't match with my options. Try again."))
    }
  }

  override def receive = {
    case msg: Try[_] => {
      msg match {
        case Success(js: CrowdInJson) => {
          logger.info(s"Message received!: $js")
          sendSender(handleCrowdIn(js))
        }
        case Success(v) => {
          logger.error(s"Error in a petition: ${v.toString}")
          sendSender(Some(buildJsError("I don't know how I've finished in this error... but I'm here :S")))
        }
        case Failure(e) => {
          logger.error(s"Error in a petition: ${e.getMessage}")
          sendSender(Some(buildJsError("Or you are not using JSON or you are not using our scheme, check both! :-)")))
        }
      }
    }
  }

  override def postStop() = {
    logger.info("Closing websocket & actor")
    release()
    kurentoActor ! PoisonPill
  }
}

object CrowdDetectorActor {
  def props(out: ActorRef) = Props(new CrowdDetectorActor(out, wskurento))
}
