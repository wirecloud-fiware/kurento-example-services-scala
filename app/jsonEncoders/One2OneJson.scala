package jsonEncoders

import play.api.libs.functional.syntax.{ functionalCanBuildApplicative, toFunctionalBuilderOps, unlift }
import play.api.libs.json._
import play.api.mvc.WebSocket.FrameFormatter
import scala.util.Try
import jsonEncoders.CrowdDetectorJson.CrowdInJson

object One2OneJson {

  case class One2OneInJson(
    id: String,
    name: Option[String],
    to: Option[String],
    from: Option[String],
    sdpOffer: Option[String],
    callResponse: Option[String],
    message: Option[String]
  )

  case class One2OneOutJson(
    id: String,
    sdpAnswer: Option[String],
    response: Option[String],
    message: Option[String],
    from: Option[String]
  )

  object One2OneOutJson {
    def apply() = new One2OneOutJson("error", Some("I've made some mistake, contact me please."), None, None, None)
  }

  implicit val one2oneInFormat = (
    (__ \ "id").format[String] and
    (__ \ "name").formatNullable[String] and
    (__ \ "to").formatNullable[String] and
    (__ \ "from").formatNullable[String] and
    (__ \ "sdpOffer").formatNullable[String] and
    (__ \ "callResponse").formatNullable[String] and
    (__ \ "message").formatNullable[String]
  )(One2OneInJson.apply, unlift(One2OneInJson.unapply))

  implicit val one2oneOutFormat = (
    (__ \ "id").format[String] and
    (__ \ "sdpAnswer").formatNullable[String] and
    (__ \ "response").formatNullable[String] and
    (__ \ "message").formatNullable[String] and
    (__ \ "from").formatNullable[String]
  )(One2OneOutJson.apply, unlift(One2OneOutJson.unapply))

  implicit val safeinone2onejson: FrameFormatter[Try[One2OneInJson]] = implicitly[FrameFormatter[String]].transform({
    text =>
      {
        text.toString
      }
  }, { text =>
    Try(Json.fromJson[One2OneInJson](Json.parse(text)).get)
  })

  implicit val outone2onjson: FrameFormatter[One2OneOutJson] = FrameFormatter.jsonFrame[One2OneOutJson]
}
