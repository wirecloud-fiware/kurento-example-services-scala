package controllers

import scala.util.Try

import handlers.CrowdDetectorActor
import handlers.One2OneActor

import play.api.Logger
import play.api.Play.current
import play.api.mvc.{ Action, Controller }
import play.api.mvc.WebSocket

import jsonEncoders.CrowdDetectorJson._ // { CrowdInJson, CrowdOutJson, safeincrowdjson, outcrowdjson }
import jsonEncoders.One2OneJson._

import play.api.libs.json.JsValue

object WebsocketApplication extends Controller {

  def index = Action {
    Logger.info("/")
    Ok("I'm alive!\n")
  }

  def crowddetector = WebSocket.acceptWithActor[Try[CrowdInJson], CrowdOutJson] {
    request => out => CrowdDetectorActor.props(out)
  }

  // To implement
  def call = WebSocket.acceptWithActor[Try[One2OneInJson], One2OneOutJson] {
    request => out => One2OneActor.props(out)
  }
}