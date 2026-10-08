package io.github.melin.paimonrest.spark.support

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters._

/**
 * 一条被桩服务端记录下来的请求。
 *
 * @param authorization 原样记录的 `Authorization` 头；未发送时为 `null`
 */
final case class RecordedRequest(method: String, path: String, body: String, authorization: String)

/**
 * 进程内的管理 API 桩服务端。
 *
 * <p>用 JDK 自带的 `com.sun.net.httpserver.HttpServer`：本模块为了不污染 Spark 的
 * classpath 刻意不引入额外 HTTP 库，测试也不破这个规矩。
 *
 * <p>用法：{@link StubManagementServer#start()} 起服务，{@link #responder} 设定响应策略，
 * {@link #requests} 读回收到的请求。端口由系统分配（0 号端口），因此可以并发跑多个桩。
 */
final class StubManagementServer private (server: HttpServer) {

  private val recorded = new ConcurrentLinkedQueue[RecordedRequest]()

  /**
   * 响应策略：给一条请求，返回 `(状态码, 响应体)`。
   *
   * @volatile 让测试线程写入后，HTTP 工作线程能立即看到
   */
  @volatile var responder: RecordedRequest => (Int, String) = (_: RecordedRequest) => (200, "{}")

  /** 管理 API 基址，已包含 `/api/management/v1` 前缀。 */
  def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/api/management/v1"

  /** 按到达顺序返回收到的全部请求。 */
  def requests: Seq[RecordedRequest] = recorded.asScala.toVector

  /** 最后一个请求。 */
  def last: RecordedRequest = {
    val all = requests
    if (all.isEmpty) {
      throw new AssertionError("桩服务端没有收到任何请求")
    }
    all.last
  }

  /** 按动词过滤请求。 */
  def requestsOf(method: String): Seq[RecordedRequest] =
    requests.filter(_.method == method.toUpperCase(java.util.Locale.ROOT))

  /** 清空记录并复位响应策略。 */
  def reset(): Unit = {
    recorded.clear()
    responder = (_: RecordedRequest) => (200, "{}")
  }

  def stop(): Unit = server.stop(0)

  /** 由 {@link StubManagementServer.start()} 注册为上下文处理器。 */
  private[support] def handle(exchange: HttpExchange): Unit = {
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    val current = RecordedRequest(
      exchange.getRequestMethod,
      exchange.getRequestURI.getRawPath,
      body,
      exchange.getRequestHeaders.getFirst("Authorization"))
    recorded.add(current)
    val (status, payload) = responder(current)
    val bytes = payload.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    // 204 之类的无响应体状态必须传 -1，否则 HttpServer 会等待响应体而挂住
    exchange.sendResponseHeaders(status, if (bytes.isEmpty) -1L else bytes.length.toLong)
    if (bytes.nonEmpty) {
      exchange.getResponseBody.write(bytes)
    }
    exchange.close()
  }
}

object StubManagementServer {

  /** 起一个监听随机端口（仅回环地址）的桩服务端。 */
  def start(): StubManagementServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val stub = new StubManagementServer(server)
    server.createContext("/", (exchange: HttpExchange) => stub.handle(exchange))
    server.start()
    stub
  }
}
