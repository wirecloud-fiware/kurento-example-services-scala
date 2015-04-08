package handlers

import akka.actor.{ Actor, ActorRef, Props, actorRef2Scala }
import akka.pattern.ask
import scala.util.{ Failure, Success, Try }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{ async, await }
import scala.language.postfixOps
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.json.{ JsError, JsSuccess, JsValue, Json }
import play.api.Logger
import kurento.actors._
import kurento.helpers._
import org.kurento.client.{ EndOfStreamEvent, EventListener, MediaPipeline, PlayerEndpoint, WebRtcEndpoint }
import handlers.commonCases._
import jsonEncoders.One2OneJson._

class One2OneActor(out: ActorRef, kurento: KurentoHelp) extends Actor {
  var uuid: Option[String] = None
  var mut_pipe: Option[AsyncCallMediaPipeline] = None

  val kurentoActor = Akka.system.actorOf(KurentoActor.props(kurento.client), name = s"kurentoactor-one2one-${out.hashCode()}")
  val logger: Logger = Logger("one2one")

  def safeToJsonOut(js: JsValue) =
    Json.fromJson[One2OneOutJson](js).getOrElse(One2OneOutJson())

  def sendTo(act: ActorRef, js: Option[JsValue]) = js foreach {
    act ! safeToJsonOut(_)
  }

  def sendSender = (sendTo _).curried(out)

  def asyncAcceptCall(callee: UserSession, caller: UserSession, calleeSdp: String) = async {
    val pipeline = await { AsyncCallMediaPipeline._create(kurentoActor) }
    mut_pipe = Some(pipeline)
    val calleeAnswer = await { pipeline.generateSdpAnswerForCallee(calleeSdp) }.get
    val callerAnswer = await { pipeline.generateSdpAnswerForCaller(caller.sdpOffer.get) }.get

    pipes.add(callee.id -> pipeline)
    pipes.add(caller.id -> pipeline)

    sendTo(caller.ws, Some(Json.obj("id" -> "startCommunication", "response" -> "accepted", "sdpAnswer" -> callerAnswer)))
    sendSender(Some(Json.obj("id" -> "startCommunication", "sdpAnswer" -> calleeAnswer)))
  }

  def rejectCall(name: String, msg: String = "user declined", msg2: String = "") = {
    userRegistry.getByName(name) match {
      case Some(user) => {
        sendTo(user.ws, Some(Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> msg)))
      }
      case None => None
    }
    Some(Json.obj("id" -> "stopCommunication", "message" -> msg2))
  }

  def incomingCallResponse(id: String, from: String, calleeSdp: String) = {
    (userRegistry.getById(id), userRegistry.getByName(from)) match {
      case (Some(callee), Some(caller)) => {
        asyncAcceptCall(callee, caller, calleeSdp) onFailure {
          case e =>
            full_release()
        }
        None
      }
      case (None, _) => rejectCall(from, "", "You have to register before")
      case (_, None) => rejectCall(from, "", s"unknown from = ${from}")
    }
  }

  def call(id: String, to: String, from: String, sdpOffer: String) = {
    def createError(msg: String) = Some(Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> msg))
    (userRegistry.getById(id), userRegistry.getByName(to)) match {
      case (Some(caller), Some(callee)) => {
        if (caller.name != from) createError(s"The name you said ${from} not match with yours.")
        else {
          val ncallee = callee.copy(peer = Some(from))
          val ncaller = caller.copy(sdpOffer = Some(sdpOffer), peer = Some(to))
          userRegistry.register(ncallee)
          userRegistry.register(ncaller)
          sendTo(callee.ws, Some(Json.obj("id" -> "incomingCall", "from" -> from)))
          None // Do not send back to the user an answer :)
        }
      }
      case (Some(_), None) => createError(s"user ${to} is not registered")
      case (None, _) => createError("You have to register before")
    }
  }

  def register(id: String, name: String) = userRegistry.getByName(name) match {
    case Some(v) => Json.obj("id" -> "registerResponse", "response" -> "rejected", "message" -> s"User ${name} already registered")
    case None => {
      userRegistry.register(UserSession(id, name, out, None, None, this.self))
      uuid = Some(id)
      Json.obj("id" -> "registerResponse", "response" -> "accepted")
    }
  }

  def release_from_pipe() = uuid foreach {
    pipes.delete(_) foreach { _.release } // Delete from map & release if was something
  }

  def release() = {
    mut_pipe foreach { p =>
      p.release()
      uuid foreach { // Remove from pipes
        pipes.delete(_)
      }
    }
    mut_pipe = None
  }

  def full_release() = {
    release()
    release_from_pipe()
  }

  def stop() = {
    full_release()

    val stopper = uuid flatMap { userRegistry.getById(_) }
    val stopped = stopper flatMap { _.peer flatMap { userRegistry.getByName(_) } }
    stopper foreach { u => userRegistry.register(u.copy(peer = None)) }
    stopped foreach { u =>
      pipes.delete(u.id)
      sendTo(u.ws, Some(Json.obj("id" -> "stopCommunication")))
    }
    None
  }

  def handleOnlyId(id: String): Option[JsValue] = id match {
    case "stop" => stop()
    case "stopCommunication" => {
      full_release()
      None
    }
    case "whoami" => uuid match {
      case Some(v) => Some(Json.obj("id" -> "message", "message" -> userRegistry.getById(v).get.name))
      case None => Some(Json.obj("id" -> "message", "message" -> "You don't have any name"))
    }
    case "unregister" => uuid match {
      case Some(v) => {
        stop() // If we don't have a name anymore, we don't deserve any call
        val name = userRegistry.unregister(v)
        uuid = None
        Some(Json.obj("id" -> "message", "message" -> s"Succesfully unregistered: $name"))
      }
      case None => Some(buildJsError("You don't have any name"))
    }
    case _ => Some(buildJsError(s"Your id '${id}' with the other parameters is nothing to me!"))
  }

  def handleOutIn(js: One2OneInJson): Option[JsValue] = js match {
    case One2OneInJson("register", Some(name), None, None, None, None, None) => uuid match {
      case Some(v) => {
        logger.error(s"Register: Asked to register $name but already have a name")
        Some(Json.obj("id" -> "registerResponse", "response" -> "rejected", "message" -> s"You are already registered with the name ${userRegistry.getById(v).get.name}"))
      }
      case None => {
        logger.info(s"Register: Asked to register $name")
        Some(register(java.util.UUID.randomUUID.toString, name))
      }
    }
    case One2OneInJson("call", _, Some(to), Some(from), Some(sdpOffer), None, None) => {
      logger.info(s"Call: Asked to call from: $from to: $to")
      call(uuid.getOrElse(""), to, from, sdpOffer)
    }
    case One2OneInJson("incomingCallResponse", _, _, Some(from), Some(sdpOffer), Some("accept"), None) => {
      logger.info(s"CallResponse: Accept call from: $from")
      incomingCallResponse(uuid.getOrElse(""), from, sdpOffer)
    }
    case One2OneInJson("incomingCallResponse", _, _, Some(from), Some(sdpOffer), Some(_), None) => {
      logger.info(s"CallResponse: Reject call from: $from")
      rejectCall(from)
      None
    }
    case One2OneInJson("changeName", Some(name), None, None, None, None, None) => uuid match {
      case Some(u) => Some(register(u, name))  // Maybe stop the call if exist?
      case None => Some(buildJsError("You can't change your name if hadn't registered yet."))
    }
    case One2OneInJson(id, None, None, None, None, None, None) => {
      logger.info(s"Petition with only the id: ${id}")
      handleOnlyId(id)
    }
    case _ => {
      logger.error(s"The options of ${Json.toJson(js)} don't match")
      Some(buildJsError(s"Your message ${Json.toJson(js)} don't match with my options. Try again."))
    }
  }

  override def receive = {
    case msg: Try[_] => {
      msg match {
        case Success(js: One2OneInJson) => {
          logger.info(s"Message received!: $js")
          sendSender(handleOutIn(js))
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
    stop()
    uuid foreach { userRegistry.unregister(_) }

    logger.info(s"Closing ws&actor, uuid: ${uuid.getOrElse("no uuid")}")
  }
}

object One2OneActor {
  def props(out: ActorRef) = Props(new One2OneActor(out, wskurento))
}
