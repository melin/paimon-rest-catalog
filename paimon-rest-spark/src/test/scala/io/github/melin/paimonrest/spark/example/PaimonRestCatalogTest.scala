package io.github.melin.paimonrest.spark.example

import io.github.melin.paimonrest.spark.LiveSparkSession
import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{AfterEach, Assumptions, BeforeEach, Test}

import java.util.UUID

/**
 * 对着一个**真实服务端**跑一遍「建表 → 读元数据」，并把 `INSERT` 停下来的位置钉住。
 *
 * <p><b>这个类解决什么问题。</b>仓库里其它 live 用例要么只验管理语句
 * （ManagementSqlLiveServerTests），要么只验建表（PaimonTableDdlTests）。
 * 这里走的是另一条链路：**catalog 的 warehouse 落在对象存储上时，
 * 客户端只凭服务端下发的凭据，能不能碰到那个仓库。**
 *
 * <p><b>为什么把「失败」也当成断言对象。</b>本服务端不做数据面写入
 * （见 `docs/spark-paimon-rest-e2e.md` 第 4 节），`INSERT` 必然失败。
 * 但失败**停在哪儿**很有信息量：停在「仓库里没有 schema 文件」说明客户端已经
 * 成功拿到凭据并连上了对象存储；停在 `UnsupportedSchemeException` 说明类路径里
 * 缺 FileIO 实现；停在一句 `UnknownReason` 说明凭据没送到客户端手上。
 * 只断言「失败了」是压不住这些的，断在失败链的哪一步才压得住。
 *
 * <p>默认跳过，需要显式指向一个已启动的服务端：
 *
 * <pre>
 * # 目标服务端的 catalog 决定它访问本地还是对象存储，用例本身不关心
 * mvn -o -pl paimon-rest-spark test -Dtest=PaimonRestCatalogTest \
 *     -De2e.management.url=http://127.0.0.1:8080/api/management/v1 \
 *     -De2e.management.token=root
 * </pre>
 *
 * <p>在 IDE 里跑时把同样两个参数写进 Run Configuration 的 VM options。
 *
 * <p><b>会话复用 [[io.github.melin.paimonrest.spark.LiveSparkSession]]，这里不自己建会话。</b>
 * 一个 JVM 只能有一个 SparkContext，而 `spark.sql.extensions` 与 catalog 选项
 * 只在建会话那一刻生效。自建会话会让同批运行的其它 live 用例拿到配置对不上的会话，
 * 失败形式还会伪装成「catalog 不存在」之类的无关报错
 * （ManagementSqlExecutionTests 是唯一自建会话的例外，它用桩服务端，且不能与本类同批运行）。
 *
 * <p><b>对象存储 warehouse 需要两样东西，缺一样都会失败</b>，且失败信息都指向别处：
 *
 * <ol>
 *   <li>类路径里有 `s3://` 的 FileIO 实现——Paimon 的 `paimon-s3`（见模块 POM）。
 *       缺了就是 `UnsupportedSchemeException: Could not find a file io implementation
 *       for scheme 's3'`；
 *   <li>catalog 开 `data-token.enabled`（见 LiveSparkSession），且服务端下发的凭据里
 *       **同时**有规格键名（`s3.access-key-id`）与引擎键名（`s3.access-key`）。
 *       只给前者：Paimon 会把 `s3.*` 整体翻译成 Hadoop 的 `fs.s3a.*`，
 *       `fs.s3a.access-key-id` 是个 Hadoop 不认识的键，密钥被静默丢弃，
 *       客户端退回默认凭据链。
 * </ol>
 *
 * <p><b>用例自建独立的库并自行清理</b>，不碰服务端上已有的库表：
 * live 用例对着的往往是手工搭的环境，留下垃圾表会污染下一次排查。
 */
class PaimonRestCatalogTest {

  import PaimonRestCatalogTest._

  /** 每个用例独占一个库，避免与服务端上已有的库表相互干扰。 */
  private var database: String = ""

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见本类 scaladoc")
    database = "example_" + UUID.randomUUID().toString.replace("-", "").substring(0, 12)
  }

  @AfterEach
  def dropDatabase(): Unit = {
    // @BeforeEach 的 assumption 失败时本方法仍会被调用，此时库名还是空串
    if (database == null || database.isEmpty) {
      return
    }
    // Paimon 不允许删非空库，先清空表
    try {
      sql(s"SHOW TABLES IN $catalog.$database")
        .map(_.getString(1))
        .foreach(table => sqlQuietly(s"DROP TABLE IF EXISTS $catalog.$database.$table"))
    } catch {
      case _: Throwable => ()
    }
    sqlQuietly(s"DROP DATABASE IF EXISTS $catalog.$database")
  }

  /**
   * 建表之后，表路径要由**服务端**按 warehouse 模板展开，schema 要能立刻读回来。
   *
   * <p>这里只查服务端返回的 `path` 与客户端 `DESCRIBE` 的结果，**不**断言那个目录
   * 在对象存储里真实存在——本服务端不写数据面，目录不会被动过（第 4 节）。
   * 之所以仍然值得断言路径：它是「写入落在哪个存储上」的决定因素，
   * 而 `path` 是服务端算的、不是客户端自拟的，只有读原始响应才能证明这一点。
   */
  @Test
  def createdTableReportsWarehousePathAndSchema(): Unit = {
    val table = "materialised"
    sql(s"CREATE DATABASE $catalog.$database")
    // 不用固定桶：追加表一旦写死 `bucket`，Paimon 就要求同时给 `bucket-key`，
    // 而这条用例只关心路径与 schema，不必背上那层约束。
    sql(s"CREATE TABLE $catalog.$database.$table (k INT, v STRING) USING paimon")

    val path = serverTablePath(database, table)
    assertTrue(path.nonEmpty, "服务端应返回表路径")
    // 路径由服务端按 warehouse 模板展开：本地仓库是 file://...，对象存储是 s3://...
    assertTrue(path.endsWith(s"/$database.db/$table"),
      s"路径应遵循 <warehouse>/<db>.db/<table> 模板，实际: $path")

    // 建表之后客户端应能立刻读到这张表（schema 来自服务端）
    val columns = sql(s"DESCRIBE $catalog.$database.$table")
      .filterNot(_.getString(0).startsWith("#"))
      .map(row => row.getString(0) -> row.getString(1))
      .toMap
    assertEquals(Map("k" -> "int", "v" -> "string"), columns)
  }

  /**
   * `INSERT` 要一路走到「仓库里没有 Paimon 的 schema 文件」为止，
   * 而不是卡在类路径或凭据上。
   *
   * <p>这条断言同时压住两件事：`s3://` 的 FileIO 实现要在类路径里（缺了报
   * `UnsupportedSchemeException`），服务端下发的对象存储凭据要按引擎认的键名
   * 送达（没送到时客户端退回默认凭据链，报 `NoAuthWithAWSException`；
   * 而这个异常类来自 `paimon-s3` 的隔离类加载器，Spark 反序列化不到，
   * 最终在用户面前只剩一句 `UnknownReason`，看不出任何东西）。
   * 两样都齐，报错才会恰好停在文档第 4 节记录的那一步。
   *
   * <p>数据面补齐之后这条会失败，那时它应该改成断言写入成功并读回数据。
   * 与 `PaimonTableDdlTests.createTableAsSelectNeedsADataPlaneTheCatalogDoesNotHave`
   * 是同一种用法：边界被移动了就提醒改动者回来同步文档。
   */
  @Test
  def writeStopsAtTheDocumentedDataPlaneGap(): Unit = {
    val table = "orders"
    sql(s"CREATE DATABASE $catalog.$database")
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "k INT, v STRING, pt STRING) USING paimon " +
      "PARTITIONED BY (pt) " +
      "TBLPROPERTIES ('primary-key' = 'k,pt', 'bucket' = '1')")

    val error = assertThrows(classOf[RuntimeException], () =>
      sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'x', '20240812')"))

    val chain = causeChain(error)
    assertFalse(chain.contains("UnsupportedSchemeException"),
      s"客户端类路径里缺 s3:// 的 FileIO 实现（paimon-s3）。报错链: $chain")
    assertTrue(chain.contains("Cannot get latest schema for table"),
      "报错链应与 docs/spark-paimon-rest-e2e.md 第 4 节一致。" +
        "若这里是 UnknownReason，多半是服务端没把对象存储凭据按引擎键名下发。" +
        s"实际报错链: $chain")
  }

  // ------------------------------------------------------------------ 辅助

  private def session: SparkSession = LiveSparkSession.instance

  private def sql(statement: String): Array[Row] = session.sql(statement).collect()

  private def sqlQuietly(statement: String): Unit =
    try {
      sql(statement)
      ()
    } catch {
      case _: Throwable => ()
    }
}

object PaimonRestCatalogTest {

  private def catalog: String = LiveSparkSession.catalog

  /**
   * 把异常链上每一层的类型与消息拼成一段文本，供断言检索。
   *
   * <p>为什么不只看 `getMessage`：Paimon / Spark 的报错是层层包装的，
   * 关键原因往往埋在 `Caused by` 里，外层只留一句看不出所以然的话。
   * 这里刻意不取栈轨迹——太长，而且含机器相关的路径。
   */
  private def causeChain(error: Throwable): String = {
    val messages = scala.collection.mutable.ArrayBuffer.empty[String]
    var current = error
    // 自引用（getCause 返回自己）会让循环停不下来，一并挡掉
    while (current != null && messages.length < 20 && current != current.getCause) {
      messages += s"${current.getClass.getName}: ${current.getMessage}"
      current = current.getCause
    }
    messages.mkString("\n")
  }

  /**
   * 直接读服务端的表元数据，取 `path`。
   *
   * <p>只有服务端侧的答案能证明路径是**服务端**按 warehouse 模板展开的，
   * 而不是客户端自拟的——这正是「写入落在哪个存储上」的决定因素。
   */
  private def serverTablePath(database: String, table: String): String = {
    import com.fasterxml.jackson.databind.ObjectMapper
    import java.net.URI
    import java.net.http.{HttpClient, HttpRequest, HttpResponse}
    import java.nio.charset.StandardCharsets
    import java.time.Duration

    val path = s"/v1/${LiveSparkSession.catalog}/databases/$database/tables/$table"
    val request = HttpRequest.newBuilder(URI.create(LiveSparkSession.catalogUrl + path))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + LiveSparkSession.token)
      .header("Accept", "application/json")
      .GET().build()
    val response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(), () => s"GET $path -> ${response.body()}")
    new ObjectMapper().readTree(response.body()).get("path").asText()
  }
}
