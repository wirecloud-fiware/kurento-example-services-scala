package jsonEncoders

import play.api.libs.functional.syntax.{functionalCanBuildApplicative, toFunctionalBuilderOps, unlift}
import play.api.libs.json._
import play.api.mvc.WebSocket.FrameFormatter
import scala.util.Try

object CrowdDetectorJson {

  case class Position(
    x: Float,
    y: Float
  )

  trait CrowdJson {}

  case class CrowdInJson(
    id: String,
    sdpOffer: Option[String],
    url: Option[String],
    filter: Option[Boolean],
    dots: Option[List[List[Position]]]
  ) extends CrowdJson

  case class CrowdOutJson(
    id: String,
    message: Option[String],
    data: Option[String],
    sdpAnswer: Option[String],
    event_data: Option[String], // This will probably be some JSon or like that
    accepted: Option[Boolean],
    filter: Option[Boolean]
  ) extends CrowdJson

  object CrowdOutJson {
    def apply() = new CrowdOutJson("error", Some("I've made some mistake, contact me please."), None, None, None, None, None)
  }

  implicit val positionFormat = (
    (__ \ "x").format[Float] and
    (__ \ "y").format[Float]
  )(Position.apply, unlift(Position.unapply))

  implicit val crowdInFormat = (
    (__ \ "id").format[String] and
    (__ \ "sdpOffer").formatNullable[String] and
    (__ \ "url").formatNullable[String] and
    (__ \ "filter").formatNullable[Boolean] and
    (__ \ "dots").formatNullable[List[List[Position]]]
  )(CrowdInJson.apply, unlift(CrowdInJson.unapply))

  implicit val crowdOutFormat = (
    (__ \ "id").format[String] and
    (__ \ "message").formatNullable[String] and
    (__ \ "data").formatNullable[String] and
    (__ \ "sdpAnswer").formatNullable[String] and
    (__ \ "event_data").formatNullable[String] and
    (__ \ "accepted").formatNullable[Boolean] and
    (__ \ "filter").formatNullable[Boolean]
  )(CrowdOutJson.apply, unlift(CrowdOutJson.unapply))

  implicit val safeincrowdjson: FrameFormatter[Try[CrowdInJson]] = implicitly[FrameFormatter[String]].transform({
    text =>
      { // This method is not really important for our app because we are going to use the String -> Try[T] method in the websocket entry
        text.getOrElse(Json.obj("id" -> "error", "message" -> "Can't parse your message")).toString
      }
  }, { text =>
    Try(Json.fromJson[CrowdInJson](Json.parse(text)).get)
  })

  implicit val outcrowdjson: FrameFormatter[CrowdOutJson] = FrameFormatter.jsonFrame[CrowdOutJson]

}
