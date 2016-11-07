package kurento

import org.kurento.client.{KurentoClient}

trait KurentoFactory {
  def createOwn(): KurentoClient
}

object KurentoClientTestFactory extends KurentoFactory {
  def createOwn(): KurentoClient = KurentoClient.create()
}
