import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit._
import com.typesafe.config.ConfigFactory
import handlers.One2OneActor
import handlers.commonCases.{ROIConf, pipes, roisFromLists, userRegistry}
import jsonEncoders.CrowdDetectorJson._
import jsonEncoders.One2OneJson._
import kurento.actors._
import kurento.helpers._
import org.scalatest._
import play.api.Application
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try

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


  "MultiUser" should {
    "with domain works receive" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1@firefox")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1@firefox", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis)
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1@firefox")

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }

    "the call needs the domain if was setted" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1@firefox")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe2.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "The name you said rock1 not match with yours.")
      probe2.expectNoMsg(100 millis)

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
    }

    "all domains receive call" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1@firefox")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1@firefox", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis)
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1@firefox")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1@firefox")

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
      unregister(oneactor3, probe3)
    }

    "when unregister while waiting to accept, ALL receive stopCommunication" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveOne(10 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor, probe, "rock1")
      probe2 ?=? Json.obj("id" -> "stopCommunication")  // When unregister, he receives the "stop" call message
      probe3 ?=? Json.obj("id" -> "stopCommunication")  // When unregister, he receives the "stop" call message

      pipes.pipelines.size should be(0)

      unregister(oneactor2, probe2)
      unregister(oneactor3, probe3)
    }

    "let reject and the other domains receive to stop" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "rejected")

      pipes.pipelines.size should be(0)

      probe2.expectNoMsg(100 millis)
      probe ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "user declined")
      probe3 ?=? Json.obj("id" -> "callResponse", "response" -> "rejected", "message" -> "rejected in other domain")

      pipes.pipelines.size should be(0)

      unregister(oneactor, probe)
      unregister(oneactor2, probe2)
      unregister(oneactor3, probe3)
    }

    "not let accept if unregistered the caller (any domain)" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor, probe, "rock1")
      probe2 ?=? Json.obj("id" -> "stopCommunication")  // When unregister, he receives the "stop" call message
      probe3 ?=? Json.obj("id" -> "stopCommunication")  // When unregister, he receives the "stop" call message

      pipes.pipelines.size should be(0)

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")
      pipes.pipelines.size should be(0)
      probe2 ?=? Json.obj("id" -> "stopCommunication", "message" -> "unknown from = rock1")
      pipes.pipelines.size should be(0)

      oneactor3 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")
      pipes.pipelines.size should be(0)
      probe3 ?=? Json.obj("id" -> "stopCommunication", "message" -> "unknown from = rock1")
      pipes.pipelines.size should be(0)

      unregister(oneactor2, probe2)
      unregister(oneactor3, probe3)
    }

    "not notify caller if more than one domain" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor2, probe2)
      probe.expectNoMsg(100 millis)
      probe2.expectNoMsg(100 millis)
      probe3 ?=? Json.obj("id" -> "info", "message" -> "You have been unregistered in rock2@firefox")

      unregister(oneactor, probe)
      unregister(oneactor3, probe3)
    }

    "notify caller when the last domain go out" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor2, probe2)
      probe.expectNoMsg(100 millis)
      probe2.expectNoMsg(100 millis)
      probe3 ?=? Json.obj("id" -> "info", "message" -> "You have been unregistered in rock2@firefox")

      unregister(oneactor3, probe3)

      probe ?=? Json.obj("id" -> "stopCommunication")
      probe2.expectNoMsg(100 millis)
      probe3.expectNoMsg(100 millis)

      unregister(oneactor, probe)
    }

    "let accept call after other domain left" in new WithApplication {
      val (probe, oneactor) = probe_actor()
      val (probe2, oneactor2) = probe_actor()
      val (probe3, oneactor3) = probe_actor()
      oneactor <=< Json.obj("id" -> "register", "name" -> "rock1")
      probe.receiveOne(100 millis)
      oneactor2 <=< Json.obj("id" -> "register", "name" -> "rock2@firefox")
      probe2.receiveOne(100 millis)
      oneactor3 <=< Json.obj("id" -> "register", "name" -> "rock2@chrome")
      probe3.receiveOne(100 millis)

      oneactor <=< Json.obj("id" -> "call", "to" -> "rock2", "from" -> "rock1", "sdpOffer" -> "test")

      probe.expectNoMsg(100 millis) // The sender don't have any message
      probe2 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")
      probe3 ?=? Json.obj("id" -> "incomingCall", "from" -> "rock1")

      unregister(oneactor2, probe2)
      probe.expectNoMsg(100 millis)
      probe2.expectNoMsg(100 millis)
      probe3 ?=? Json.obj("id" -> "info", "message" -> "You have been unregistered in rock2@firefox")

      oneactor2 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")
      probe.expectNoMsg(100 millis)
      probe2 ?=? Json.obj("id" -> "stopCommunication", "message" -> "You have to register before")
      probe3.expectNoMsg(100 millis)

      oneactor3 <=< Json.obj("id" -> "incomingCallResponse", "from" -> "rock1", "sdpOffer" -> "test", "callResponse" -> "accept")

      probe ?=? Json.obj("id" -> "startCommunication", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n", "response" -> "accepted")
      probe3 ?=? Json.obj("id" -> "startCommunication", "sdpAnswer" -> "v=0\r\ns=Kurento Media Server\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n")

      unregister(oneactor, probe)
      unregister(oneactor3, probe3)
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
