package io.github.melin.paimonrest.spark

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.analysis.{NoSuchNamespaceException, TableAlreadyExistsException}
import org.apache.spark.sql.catalyst.parser.ParseException
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{AfterEach, Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import scala.collection.JavaConverters._

/**
 * 「用 Spark SQL 建 Paimon 表」的验收测试：语句经 Paimon 的 Spark catalog 落到
 * 我们的 REST 服务端，再回读服务端保存的元数据。
 *
 * <p><b>为什么这一层不能用桩。</b>建表语句由 Paimon 自己的 catalog 实现执行，
 * 它按 Paimon 的 REST 契约发请求。用桩替换服务端就只能证明「我们与自己的假设自洽」，
 * 证明不了「Paimon 客户端真的接受我们的响应」。因此这里跑真实服务端 +
 * 真实 SparkSession，断言两侧对同一张表的理解一致。
 *
 * <p>默认跳过，需要显式指向一个已启动的服务端：
 *
 * <pre>
 * scripts/e2e-spark-sql.sh          # 自动起服务端并跑本测试
 * </pre>
 *
 * <p>或手工：
 *
 * <pre>
 * mvn -o -pl paimon-rest-spark test -Dtest=PaimonTableDdlTests \
 *     -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
 *     -De2e.management.token=root
 * </pre>
 *
 * <p><b>会话如何配置。</b>Spark 的 `spark.sql.extensions` 只在建会话那一刻生效，
 * 且一个 JVM 只能有一个 SparkContext，所以这里共用 {@link LiveSparkSession}
 * 里那个已配好 Paimon 扩展与 `spark.sql.catalog.<catalog>` 的会话；
 * catalog 名一律走 {@link #catalog}，不写死。
 *
 * <p><b>断言分两层。</b>
 * <ol>
 *   <li>Spark 侧：`DESCRIBE` / `SHOW TABLES` / `SHOW CREATE TABLE` 的输出——
 *       验证客户端拿到并渲染出的表结构与提交的一致；
 *   <li>服务端侧：直接 `GET /v1/{prefix}/databases/{db}/tables/{table}` 读原始 JSON——
 *       验证 `comment`、`fields[].description`、`partitionKeys`、`primaryKeys`、`options`
 *       真的落到了元数据库，而不是只存在于客户端内存里。
 * </ol>
 * 少了第二层，服务端完全可以「建表返回成功但什么都没存」，而第一层照样通过。
 *
 * <p><b>写入曾经不可用，现已打通。</b>`INSERT INTO paimon.&lt;db&gt;.&lt;table&gt;` 与
 * `CREATE TABLE ... AS SELECT` 一度都在提交 snapshot 时报
 * `Cannot get latest schema for table`：引擎提交前要在仓库里读
 * `&lt;table&gt;/schema/schema-&lt;n&gt;`，而服务端当时只把 schema 存进自己的数据库、
 * 没有把它物化到表目录，那个文件根本不存在。服务端补上物化之后（见
 * {@code TableMetadataService}），写入链路随之通畅。
 *
 * <p>{@link #createTableAsSelectWritesDataAndReadsItBack} 现在断言的是写入**成功**。
 * 它在缺口存在时是被刻意写成「断言不可用」的**守卫用例**——边界一移动就先响，
 * 提醒回来更新 {@code docs/spark-paimon-rest-e2e.md} 第 4 节。那次翻转已经发生。
 */
class PaimonTableDdlTests {

  import PaimonTableDdlTests._

  /**
   * 目标 catalog 名，由 [[LiveSparkSession]] 统一给出，**不要在 SQL 里写死**。
   *
   * <p>它既是 `spark.sql.catalog.<name>` 的后缀，也是服务端上的 catalog prefix，
   * 取决于目标服务端的配置。写死某个名字（例如 `paimon`）而目标服务端的 catalog
   * 叫别的名字时，Spark 会把它当成「未注册的 catalog」，报出来的却是一句
   * `Undefined error message parameter for error class '_LEGACY_ERROR_TEMP_1055'`——
   * 与真正的原因（catalog 名字对不上）毫无关系，非常难查。
   * 注意 `USING paimon` 里的 `paimon` 是**数据源名**，与 catalog 名无关，保持原样。
   */
  private def catalog: String = LiveSparkSession.catalog

  /** 每个测试独占一个库，避免用例之间互相看见对方的表。 */
  private var database: String = ""

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")
    database = "ddl_" + UUID.randomUUID().toString.replace("-", "").substring(0, 12)
    sql(s"CREATE DATABASE $catalog.$database")
  }

  @AfterEach
  def dropDatabase(): Unit = {
    // @BeforeEach 里 assumption 失败时本方法仍会被调用，此时库名还是空串
    if (database == null || database.isEmpty) {
      return
    }
    // Paimon 不允许删非空库，先清空表
    try {
      sql(s"SHOW TABLES IN $catalog.$database").map(_.getString(1)).foreach { table =>
        sqlQuietly(s"DROP TABLE IF EXISTS $catalog.$database.$table")
      }
    } catch {
      case _: Throwable => ()
    }
    sqlQuietly(s"DROP DATABASE IF EXISTS $catalog.$database")
  }

  // ------------------------------------------------------------------ 正常路径

  /**
   * 分区 append 表：列注释、表注释、分区键、表选项都要原样到达服务端。
   *
   * <p>固定 bucket 的 append 表必须同时给 `bucket-key`，否则 Paimon 拒绝建表
   * （`You should define a 'bucket-key' for bucketed append mode`），所以这里两者同给。
   */
  @Test
  def partitionedAppendTableRoundTripsThroughServer(): Unit = {
    val table = "orders"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT COMMENT '订单号', " +
      "amount DECIMAL(18,2) COMMENT '成交金额', " +
      "dt STRING COMMENT '分区日') " +
      "USING paimon " +
      "COMMENT '订单明细表' " +
      "PARTITIONED BY (dt) " +
      "TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id', 'purpose' = 'e2e')")

    // ---- Spark 侧：客户端渲染出的结构与提交的一致
    val columns = describedColumns(database, table)
    assertEquals(("bigint", "订单号"), columns("id"))
    assertEquals(("decimal(18,2)", "成交金额"), columns("amount"))
    assertEquals(("string", "分区日"), columns("dt"))

    val ddl = singleValue(s"SHOW CREATE TABLE $catalog.$database.$table")
    assertTrue(ddl.contains("USING paimon"), ddl)
    assertTrue(ddl.contains("PARTITIONED BY (dt)"), ddl)
    assertTrue(ddl.contains("COMMENT '订单明细表'"), ddl)
    assertTrue(ddl.contains("'bucket-key' = 'id'"), ddl)
    assertTrue(ddl.contains("'purpose' = 'e2e'"), ddl)

    assertTrue(sql(s"SHOW TABLES IN $catalog.$database").exists(_.getString(1) == table),
      "SHOW TABLES 应包含刚建的表")

    // ---- 服务端侧：schema 与选项确实落库
    val metadata = serverTable(database, table)
    val schema = metadata.get("schema")
    assertEquals("订单明细表", schema.get("comment").asText())
    assertEquals(List("dt"), textList(schema.get("partitionKeys")))
    assertEquals(List.empty[String], textList(schema.get("primaryKeys")))
    assertEquals(List("id", "amount", "dt"), fieldNames(schema))
    assertEquals("订单号", fieldOf(schema, "id").get("description").asText())
    assertEquals("成交金额", fieldOf(schema, "amount").get("description").asText())
    assertEquals("DECIMAL(18, 2)", fieldOf(schema, "amount").get("type").asText())

    val options = schema.get("options")
    assertEquals("e2e", options.get("purpose").asText())
    assertEquals("1", options.get("bucket").asText())
    assertEquals("id", options.get("bucket-key").asText())

    // 表路径由服务端的 path-template 展开，不由客户端自拟
    assertTrue(metadata.get("path").asText().endsWith(s"/$database.db/$table"),
      "路径应遵循服务端模板 <warehouse>/<db>.db/<table>，实际: " + metadata.get("path").asText())
  }

  /**
   * 主键表：Spark + Paimon 组合下只能用 `TBLPROPERTIES ('primary-key' = ...)` 声明，
   * 见 {@link #inlinePrimaryKeyConstraintIsRejectedBySparkParser}。
   */
  @Test
  def primaryKeyTableIsDeclaredThroughTableProperty(): Unit = {
    val table = "pk_orders"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT COMMENT '订单号', amount DECIMAL(18,2)) " +
      "USING paimon TBLPROPERTIES ('primary-key' = 'id', 'bucket' = '1')")

    val schema = serverTable(database, table).get("schema")
    assertEquals(List("id"), textList(schema.get("primaryKeys")))
    assertEquals(List.empty[String], textList(schema.get("partitionKeys")))

    // Paimon 把 primary-key 解释成 schema 的主键定义后，就不再把它当表属性保留；
    // 而 bucket-key 对主键表无意义，也不出现。断言这两点是为了固定「属性 → 结构」的转换结果。
    assertFalse(schema.get("options").has("primary-key"),
      "primary-key 应已转成 schema 主键，不该继续留在 options 里: " + schema.get("options"))
    assertFalse(schema.get("options").has("bucket-key"),
      "主键表不需要 bucket-key: " + schema.get("options"))
  }

  /** 分区主键表：Paimon 要求主键覆盖全部分区列，主键与分区键都要正确落库。 */
  @Test
  def partitionedPrimaryKeyTableKeepsPartitionColumnsInTheKey(): Unit = {
    val table = "pk_partitioned"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT, amount DECIMAL(18,2), dt STRING) " +
      "USING paimon PARTITIONED BY (dt) " +
      "TBLPROPERTIES ('primary-key' = 'id,dt', 'bucket' = '1')")

    val schema = serverTable(database, table).get("schema")
    assertEquals(List("id", "dt"), textList(schema.get("primaryKeys")))
    assertEquals(List("dt"), textList(schema.get("partitionKeys")))
  }

  /** `CREATE TABLE ... LIKE` 复制结构，包括列注释与分区键。 */
  @Test
  def createTableLikeCopiesSchemaAndComments(): Unit = {
    val source = "orders"
    val copy = "orders_copy"
    sql(s"CREATE TABLE $catalog.$database.$source (" +
      "id BIGINT COMMENT '订单号', dt STRING COMMENT '分区日') " +
      "USING paimon PARTITIONED BY (dt) " +
      "TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id')")

    sql(s"CREATE TABLE $catalog.$database.$copy LIKE $catalog.$database.$source USING paimon")

    assertEquals(describedColumns(database, source), describedColumns(database, copy),
      "LIKE 应复制列定义与注释")
    val schema = serverTable(database, copy).get("schema")
    assertEquals(List("dt"), textList(schema.get("partitionKeys")), "LIKE 应复制分区键")
    assertEquals(List("id", "dt"), fieldNames(schema))
  }

  // ------------------------------------------------------------------ 幂等与冲突

  /**
   * `IF NOT EXISTS` 幂等且**不覆盖**已有结构；不带该子句时重复建表报冲突。
   *
   * <p>第二条 `IF NOT EXISTS` 故意换了 schema：如果实现是「先删后建」或「覆盖」，
   * 这里就会把 `extra` 列加进去——那与「已存在则什么都不做」的语义不符。
   */
  @Test
  def ifNotExistsIsIdempotentAndBareCreateConflicts(): Unit = {
    val table = "dup"
    sql(s"CREATE TABLE IF NOT EXISTS $catalog.$database.$table (id BIGINT) USING paimon")
    sql(s"CREATE TABLE IF NOT EXISTS $catalog.$database.$table (id BIGINT, extra STRING) USING paimon")

    assertEquals(List("id"), fieldNames(serverTable(database, table).get("schema")),
      "IF NOT EXISTS 不应修改已存在的表结构")

    val conflict = assertThrows(classOf[TableAlreadyExistsException], () =>
      sql(s"CREATE TABLE $catalog.$database.$table (id BIGINT) USING paimon"))
    assertTrue(conflict.getMessage.contains("already exists"), conflict.getMessage)
  }

  // ------------------------------------------------------------------ 易错点

  /**
   * `PRIMARY KEY (id) NOT ENFORCED` 这类内联约束在本组合下**解析不了**。
   *
   * <p>记录它是为了避免文档与示例写出这条语句：Spark 3.5 的 `CREATE TABLE` 语法不带
   * 列级/表级 PRIMARY KEY 约束，Paimon 的主键只能通过 `TBLPROPERTIES` 表达。
   */
  @Test
  def inlinePrimaryKeyConstraintIsRejectedBySparkParser(): Unit = {
    val error = assertThrows(classOf[ParseException], () =>
      sql(s"CREATE TABLE $catalog.$database.inline_pk (" +
        "id BIGINT, amount DECIMAL(18,2), PRIMARY KEY (id) NOT ENFORCED) USING paimon"))
    assertTrue(error.getMessage.contains("Syntax error"), error.getMessage)
  }

  /**
   * `owner` 是 Spark 的保留表属性，写进 `TBLPROPERTIES` 会在**解析阶段**就被拒。
   *
   * <p>这条在客户端侧就被 Spark 拦下，请求根本不会到服务端。放在这里是因为它极容易
   * 通过「服务端支持 owner」的直觉写重——服务端确实会自己补一个 `owner`（取提交者），
   * 但那不是客户端该传的字段。
   */
  @Test
  def reservedTablePropertyIsRejectedBeforeItReachesTheServer(): Unit = {
    val error = assertThrows(classOf[ParseException], () =>
      sql(s"CREATE TABLE $catalog.$database.reserved (id BIGINT) USING paimon " +
        "TBLPROPERTIES ('owner' = 'someone-else')"))
    assertTrue(error.getMessage.contains("reserved table property"), error.getMessage)
  }

  /** 建到不存在的库：客户端把服务端的 404 映射成 `NoSuchNamespaceException`，而不是静默建库。 */
  @Test
  def creatingTableInMissingDatabaseFails(): Unit = {
    val missing = database + "_absent"
    val error = assertThrows(classOf[NoSuchNamespaceException], () =>
      sql(s"CREATE TABLE $catalog.$missing.t (id BIGINT) USING paimon"))
    assertTrue(error.getMessage.contains(missing), error.getMessage)
  }

  // ------------------------------------------------------------------ 写入链路

  /**
   * `CREATE TABLE ... AS SELECT` 能写完并把数据读回来。
   *
   * <p>这条曾经是「数据面缺口」的守卫用例，断言的是写入**失败**、且失败停在
   * `Cannot get latest schema for table`。服务端补上 schema 物化（把
   * `schema/schema-<n>` 写进表目录）之后，它按设计翻转成断言写入成功——
   * 这正是当初把它写成守卫用例的用意：边界一移动，它就先响。
   *
   * <p>断言分三层，缺一层都压不住回归：
   *
   * <ol>
   *   <li>CTAS 本身不抛异常；
   *   <li>`SELECT` 能把数据读回来——读路径要在仓库里找到那个 schema 文件与刚提交的
   *       快照，能读出数据就说明两边都齐；
   *   <li>catalog 里表还在（对照之下，缺口时期失败会让这张表根本建不出来）。
   * </ol>
   *
   * <p>注意这里**不**断言失败是原子的。原子性在缺口时期是要紧的（那时写入必然失败，
   * 决定失败后要不要手工清理），现在写入不再失败，那条断言就没有对象了。
   */
  @Test
  def createTableAsSelectWritesDataAndReadsItBack(): Unit = {
    val table = "ctas"
    sql(s"CREATE TABLE $catalog.$database.$table USING paimon AS SELECT 1L AS id, 'x' AS name")

    val rows = sql(s"SELECT id, name FROM $catalog.$database.$table")
    assertEquals(1, rows.length, "CTAS 应写入恰好一行")
    assertEquals(1L, rows(0).getLong(0))
    assertEquals("x", rows(0).getString(1))

    assertTrue(sql(s"SHOW TABLES IN $catalog.$database").exists(_.getString(1) == table),
      "CTAS 成功之后表应留在 catalog 里")
  }

  /**
   * `INSERT` 能写入分区主键表，并按分区键读回。
   *
   * <p>与上一条互补：CTAS 走的是「建表 + 写」的合并路径，这里走的是「先建表、再单独写」，
   * 是使用者最常走的路径。也顺带覆盖分区表——分区列的写入要在仓库里按分区建目录，
   * 比非分区表多一层。
   */
  @Test
  def insertIntoPartitionedKeyTableIsReadable(): Unit = {
    val table = "pk_write"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "k INT, v STRING, pt STRING) USING paimon " +
      "PARTITIONED BY (pt) TBLPROPERTIES ('primary-key' = 'k,pt', 'bucket' = '1')")

    sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'x', '20240812')")

    val rows = sql(s"SELECT k, v, pt FROM $catalog.$database.$table")
    assertEquals(1, rows.length)
    assertEquals(1, rows(0).getInt(0))
    assertEquals("x", rows(0).getString(1))
    assertEquals("20240812", rows(0).getString(2))
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

  private def singleValue(statement: String): String = {
    val rows = sql(statement)
    assertEquals(1, rows.length, () => statement + " 应返回恰好一行")
    rows(0).getString(0)
  }

  /** `DESCRIBE` 的列名 → (类型, 注释)，丢掉 `# Partition Information` 这类分隔行。 */
  private def describedColumns(db: String, table: String): Map[String, (String, String)] =
    sql(s"DESCRIBE $catalog.$db.$table")
      .filterNot(_.getString(0).startsWith("#"))
      .map(row => row.getString(0) -> (row.getString(1), row.getString(2)))
      .toMap

  /** 直接读服务端的表元数据——只有这一层能证明元数据真的落了库。 */
  private def serverTable(db: String, table: String): JsonNode = {
    val path = s"/v1/${LiveSparkSession.catalog}/databases/$db/tables/$table"
    val request = HttpRequest.newBuilder(URI.create(LiveSparkSession.catalogUrl + path))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + LiveSparkSession.token)
      .header("Accept", "application/json")
      .GET().build()
    val response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(), () => "GET " + path + " -> " + response.body())
    MAPPER.readTree(response.body())
  }
}

object PaimonTableDdlTests {

  private val MAPPER = new ObjectMapper()

  private def textList(node: JsonNode): List[String] = node.elements().asScala.map(_.asText()).toList

  private def fieldNames(schema: JsonNode): List[String] =
    schema.get("fields").elements().asScala.map(_.get("name").asText()).toList

  private def fieldOf(schema: JsonNode, name: String): JsonNode =
    schema.get("fields").elements().asScala.find(_.get("name").asText() == name).getOrElse(
      throw new AssertionError("服务端返回的 schema 里没有列 " + name + "：" + schema))
}
