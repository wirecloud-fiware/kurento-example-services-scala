import kurento.helpers.{KurentoHelp, safeGetWs}
import org.kurento.client.KurentoClient

// This file have common classes and functions for the tests

case class DummyKurento(val client: KurentoClient)

object DummyKurento extends KurentoHelp {
  private val ws_u = safeGetWs("ws://localhost:8889", "kurento.test.ws")
  val client = KurentoClient.create(ws_u)
}
