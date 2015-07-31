import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.kurento.client._
import org.kurento.module.crowddetector._
import org.scalatest._
import play.api.Application
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import handlers.commonCases._
import scala.util.Success

class NameDomainTest extends TestKit(ActorSystem("testKurentoActor", ConfigFactory.parseString(NameDomainTest.config)))  with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {
  override def beforeAll {
  }
  override def afterAll {
    shutdown()
  }

  "parser" should {
    "parse empty OK" in {
      parseNameDomain("") should be (None)
    }

    "parse no domain OK" in {
      parseNameDomain("rock") should be (Some("rock", ""))
    }

    "parse domain OK" in {
      parseNameDomain("rock@firefox") should be (Some("rock", "firefox"))
    }

    "parse not valid OK" in {
      parseNameDomain("this$") should be (None)
    }

    "parse not valid with domain OK" in {
      parseNameDomain("this@nope$") should be (None)
    }

    "parse with everything OK" in {
      parseNameDomain("this_9-123@firefox-123_") should be (Some("this_9-123","firefox-123_"))
    }
  }

  "works well without domain" should {
    "Add one" in {
      userRegistry.register(UserSession("id1", "rock", null, None, None, null))
      userRegistry.ids.size should be (1)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id1") should be (Some(("rock", "")))
      userRegistry.users.get("rock").get.size should be (1)
    }

    "Persist" in {
      userRegistry.ids.size should be (1)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id1") should be (Some(("rock", "")))
      userRegistry.users.get("rock").get.size should be (1)
    }

    "unregister" in {
      userRegistry.unregister("id1") should be ("rock")
      userRegistry.ids.size should be (0)
      userRegistry.users.size should be (0)
      userRegistry.ids.get("id1") should be (None)
      userRegistry.users.get("rock") should be (None)
    }
  }

  "works well with domain" should {
    "Add one" in {
      userRegistry.register(UserSession("id1", "rock@firefox", null, None, None, null))
      userRegistry.ids.size should be (1)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id1") should be (Some(("rock", "firefox")))
      userRegistry.users.get("rock").get.size should be (1)
    }

    "Persist" in {
      userRegistry.ids.size should be (1)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id1") should be (Some(("rock", "firefox")))
      userRegistry.users.get("rock").get.size should be (1)
    }

    "Add two" in {
      userRegistry.register(UserSession("id2", "rock@chrome", null, None, None, null))
      userRegistry.ids.size should be (2)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id2") should be (Some(("rock", "chrome")))
      userRegistry.users.get("rock").get.size should be (2)
    }

    "Persist two" in {
      userRegistry.ids.size should be (2)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id1") should be (Some(("rock", "firefox")))
      userRegistry.ids.get("id2") should be (Some(("rock", "chrome")))
      userRegistry.users.get("rock").get.size should be (2)
    }

    "unregister" in {
      userRegistry.unregister("id1") should be ("rock@firefox")
      userRegistry.ids.size should be (1)
      userRegistry.users.size should be (1)
      userRegistry.ids.get("id1") should be (None)
      userRegistry.users.get("rock").get.size should be (1)

      userRegistry.unregister("id2") should be ("rock@chrome")
      userRegistry.ids.size should be (0)
      userRegistry.users.size should be (0)
      userRegistry.ids.get("id1") should be (None)
      userRegistry.users.get("rock") should be (None)
    }
  }

  "getByName" should {
    "work for one" in {
      val usersess = UserSession("id1", "rock", null, None, None, null)
      userRegistry.register(usersess)
      userRegistry.getByName("rock") should be (Some(usersess))
      userRegistry.getByName("rock@") should be (None)
      userRegistry.unregister("id1") should be ("rock")
    }

    "with other get them right" in {
      val usersess1 = UserSession("id1", "rock@chrome", null, None, None, null)
      val usersess2 = UserSession("id2", "rock@firefox", null, None, None, null)
      userRegistry.register(usersess1)
      userRegistry.register(usersess2)
      userRegistry.getByName("rock") should be (None)
      userRegistry.getByName("rock@chrome") should be (Some(usersess1))
      userRegistry.getByName("rock@firefox") should be (Some(usersess2))
      userRegistry.unregister("rock") should be ("There was no name")
      userRegistry.unregister("id1")  should be ("rock@chrome")
      userRegistry.unregister("id2") should be ("rock@firefox")
    }
  }
}

object NameDomainTest {
  val config = """
akka.loggers = ["akka.testkit.TestEventListener"]
akka.stdout-loglevel = "OFF"
akka.loglevel = "OFF"
"""
}
