package handlers

import org.kurento.client.KurentoClient
import org.kurento.client.IceCandidate
import scala.collection.mutable.HashMap
import akka.actor.{ ActorRef }
import akka.pattern.ask
import org.kurento.client.{ MediaPipeline, WebRtcEndpoint }
import org.kurento.module.crowddetector.{RegionOfInterestConfig, RegionOfInterest, RelativePoint}
import jsonEncoders.CrowdDetectorJson.Position
import scala.collection.JavaConversions.seqAsJavaList
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{ async, await }
import scala.util.Try
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import play.api.libs.concurrent.Akka
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json.Json
import scala.language.postfixOps

import kurento.actors._
import kurento.helpers._

object commonCases {
  def buildJsError(msg: String) = Json.obj("id" -> "error", "message" -> msg)

  // Create the rois with maps&zips, functional style <3
  def roisFromLists(dots: List[List[Position]]) (implicit roisconf: RegionOfInterestConfig) = dots.zipWithIndex.map {
    case (dot, i) =>
      new RegionOfInterest(dot.map(roip =>
        new RelativePoint(roip.x, roip.y)), roisconf, "roi" + (i + 1))
  }

  implicit val ROIConf = {
    val roisconf: RegionOfInterestConfig = new RegionOfInterestConfig()
    roisconf.setFluidityLevelMin(10)
    roisconf.setFluidityLevelMed(35)
    roisconf.setFluidityLevelMax(65)
    roisconf.setFluidityNumFramesToEvent(2)
    roisconf.setOccupancyLevelMin(10)
    roisconf.setOccupancyLevelMed(35)
    roisconf.setOccupancyLevelMax(65)
    roisconf.setOccupancyNumFramesToEvent(2)
    roisconf.setSendOpticalFlowEvent(false)
    roisconf.setOpticalFlowNumFramesToEvent(3)
    roisconf.setOpticalFlowNumFramesToReset(3)
    roisconf.setOpticalFlowAngleOffset(0)
    roisconf
  }

  // case class AddPipeline(pipeline: AsyncCallMediaPipeline)
  // case object RemovePipeline
  case class UserSession(
    id: String,
    name: String,
    ws: ActorRef,
    peer: Option[String],
    sdpOffer: Option[String],
    actor: ActorRef
  ) {

    private val candidates = scala.collection.mutable.ArrayBuffer.empty[IceCandidate]

    def addCandidate(candidate: IceCandidate) {
        candidates += candidate
    }

    def setWebRtcEndpoint(webRtcEndpoint: WebRtcEndpoint) {
        for (candidate <- candidates) {
            webRtcEndpoint.addIceCandidate(candidate)
        }
        candidates.clear()
    }

  }

  case class AsyncCallMediaPipeline(
    val pipeline: MediaPipeline,
    val callerWebRtcEndpoint: WebRtcEndpoint,
    val calleeWebRtcEndpoint: WebRtcEndpoint,
    private val kurentoActor: ActorRef
  ) {

    private var released = false

    def generateSdpAnswerForCaller(sdpOffer: String) = ask(kurentoActor, ProcessOffer(callerWebRtcEndpoint, sdpOffer)).mapTo[Try[String]]

    def generateSdpAnswerForCallee(sdpOffer: String) = ask(kurentoActor, ProcessOffer(calleeWebRtcEndpoint, sdpOffer)).mapTo[Try[String]]

    def release() = {
      if (!released) {
        kurentoActor ! Release(pipeline)
        released = true
      }
    }
  }

  object AsyncCallMediaPipeline { // Companion object
    def apply(kA: ActorRef) = {
      _createObject(kA)
    }

    private def _createObject(kurentoActor: ActorRef) = {
      val f = _create(kurentoActor)
      Await.result(f, 60 seconds)
    }

    def _create(kurentoActor: ActorRef) = async {
      val pipeline = await { ask(kurentoActor, CreateMediaPipeline).mapTo[Try[MediaPipeline]] }.get
      val caller = await { ask(kurentoActor, CreateWebRtcEndp(pipeline)).mapTo[Try[WebRtcEndpoint]] }.get
      val callee = await { ask(kurentoActor, CreateWebRtcEndp(pipeline)).mapTo[Try[WebRtcEndpoint]] }.get

      kurentoActor ! List(Connect(caller, callee), Connect(callee, caller))

      new AsyncCallMediaPipeline(pipeline, caller, callee, kurentoActor)
    }
  }

  object pipes {
    val pipelines = new TrieMap[String, AsyncCallMediaPipeline]

    def add(value: (String, AsyncCallMediaPipeline)) =
      pipelines.put(value._1,value._2)  // This return Option[oldvalue]
      // pipelines += value  // This return the new value

    def delete(i: String) =
      pipelines.remove(i)

    // def get(i: String) = pipelines.get(i)  // Usual way to write it
    def get = (pipelines.get _)  // Cool way to write it :-)
  }

  object userRegistry {
    //    var ids = new ConcurrentHashMap[String, UserSession] asScala
    //    var names = new ConcurrentHashMap[String, UserSession] asScala

    val ids = new TrieMap[String, UserSession]()
    val names = new TrieMap[String, UserSession]()

    //    var ids = HashMap.empty[String, UserSession]
    //    var names = HashMap.empty[String, UserSession]

    def register(user: UserSession) = {
      ids += (user.id -> user)
      names += (user.name -> user)
    }
    def unregister(id: String) = {
      ids.get(id) match {
        case Some(user) => {
          ids -= id
          names -= user.name
          user.name
        }
        case None => "There was no name"
      }
    }
    def getById(id: String) = ids.get(id)
    def getByName(name: String) = names.get(name)
  }

}
/*
 https://class.coursera.org/reactive-001/wiki/assignment_3_faq

 https://github.com/greenTara/reactive-examples/tree/master/src/main/scala/future

 https://class.coursera.org/reactive-001/wiki/Week_4_FAQ

 https://class.coursera.org/reactive-001/wiki/Week_5_FAQ
 */
