package kurento

import org.kurento.client.{KurentoClient, KurentoConnectionListener}
import org.kurento.jsonrpc.client.{JsonRpcClient, JsonRpcClientWebSocket}

trait KurentoFactory {
  def createKurentoForTest(): KurentoClient
  def createKurentoForTest(listener: KurentoConnectionListener): KurentoClient
  def createOwn(l: JsonRpcClient): KurentoClient
  def createJsonRpcClient(prefix: String): JsonRpcClient
  def createJsonRpcClient(prefix: String, listener: KurentoConnectionListener): JsonRpcClient
  def createWithJsonRpcClient(client: JsonRpcClient): KurentoClient
}

object KurentoClientTestFactory extends KurentoFactory {
  def createKurentoForTest(): KurentoClient = createKurentoForTest(null)

  def createKurentoForTest(listener: KurentoConnectionListener): KurentoClient = KurentoClient.createFromJsonRpcClient(createJsonRpcClient("client", listener))

  def createOwn(l: JsonRpcClient): KurentoClient = KurentoClient.createFromJsonRpcClient(l)

  def createJsonRpcClient(prefix: String): JsonRpcClient = createJsonRpcClient(prefix, null)

  def createJsonRpcClient(prefix: String, listener: KurentoConnectionListener): JsonRpcClient =
    new JsonRpcClientWebSocket("ws://localhost:8889/kurento")
  // new JsonRpcClientWebSocket("ws://130.206.81.33:8888/kurento") //("ws://localhost:8889/kurento")

  def createWithJsonRpcClient(client: JsonRpcClient): KurentoClient = KurentoClient.createFromJsonRpcClient(client)
}
