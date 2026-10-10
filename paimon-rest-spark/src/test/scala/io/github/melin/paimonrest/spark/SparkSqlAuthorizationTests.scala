package io.github.melin.paimonrest.spark

import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 「同一个 catalog、同一张表，两种身份」的 Spark SQL 权限验收。
 *
 * <p><b>为什么要在 Spark 侧再验一遍。</b>服务端已有两层授权测试
 * （`AuthorizationTests` 验判定逻辑、`CatalogEndpointAuthorizationTests` 验映射覆盖率），
 * 但它们都用 MockMvc 直接打 REST。真正要拦住的是**引擎发出的请求**，而请求由 Paimon 的
 * `SparkCatalog` 生成：「读一张表会用到哪几个端点」「403 会不会被客户端吞掉或改写成别的错」
 * 「拒绝之后数据到底有没有变」这些只有跑真 SQL 才知道。因此本类对着真实服务端跑真实
 * SparkSession，是授权链路上唯一致力于端到端的那一层。
 *
 * <p><b>场景。</b>两个用户，同一个 catalog、同一张表：
 *
 * <ul>
 *   <li>{@link #readWriteUser}（令牌 {@code alice}）：能建库、建表，能读写数据；
 *   <li>{@link #readOnlyUser}（令牌 {@code bob}）：只能读数据——`SELECT` 正常，
 *       而 `INSERT` / `CREATE TABLE` / `CREATE DATABASE` 一律被服务端拒绝。
 * </ul>
 *
 * <p><b>只读身份也必须有 `NAMESPACE_READ_PROPERTIES`。</b>这是实测出来的约束，不是顺手多给：
 * `SparkCatalog.initialize` 在 Spark **首次**解析到该 catalog 的限定名时懒加载它，
 * 而加载过程会去读默认命名空间 `default` 的属性（`GET /v1/{prefix}/databases/default`）。
 * 缺了这一项，连第一句 `SELECT` 都到不了表——它与「读不读数据」无关，
 * 属于「用得了这个 catalog」的门槛。只读角色因此是「读元数据 + 读数据」而不是「只有读数据」。
 *
 * <p><b>身份是怎么来的。</b>两个身份共用 {@link LiveSparkSession#instance} 这一个会话的
 * 两份 SQLConf（见 {@link LiveSparkSession#sessionAs}），差别只有发给服务端的令牌。
 * 「谁能做什么」完全由服务端的 RBAC 决定：本模块不认识「用户」，只认识令牌。
 *
 * <p><b>断言锚在服务端自己的措辞上。</b>被拒绝时错误信息里必须出现服务端授权判定产出的
 * `does not have permission`，以及 `principal e2e_bob lacks TABLE_WRITE_DATA` 这类文案——
 * 前者证明拒绝来自本服务端的授权判定（而不是文件系统凭据、网络之类的偶发原因），
 * 后者钉住了「缺的到底是哪一项权限」。少了后半句，一个「碰巧也失败了」的实现照样能过。
 *
 * <p>刻意不锚 Paimon 客户端加的那层壳：同一个 403 有「被包装成 `*NoPermissionException`」
 * 与「直接抛 `ForbiddenException`」两种落地方式（见 `assertDenied` 里的说明），
 * 只有服务端原文在两种情况下都稳定存在。
 *
 * <p>默认跳过，需要显式指向一个**同时开了认证与授权、且配了两个令牌**的服务端，
 * 现在的做法是走脚本：
 *
 * <pre>
 * scripts/e2e-spark-sql.sh          # 起服务端（含令牌 alice / bob）并跑本测试
 * </pre>
 *
 * <p><b>本类只验「能不能写」，不验「写得对不对」。</b>被拒绝的写入可能在提交之前已经往
 * 仓库写了数据文件（授权判定发生在 commit 那一步），留下的是无人引用的孤儿文件，
 * 不影响任何查询结果——用例断言的是「数据没变」，不是「仓库里没有多余文件」。
 */
object SparkSqlAuthorizationTests {

  /** 数据面用的 catalog。既是服务端的 prefix，也是 SQL 里的 catalog 限定名。 */
  private[spark] def catalog: String = LiveSparkSession.catalog

  /** 有「建库 + 建表 + 读写数据」权限的那一个。令牌 `alice` 由服务端映射到该主体。 */
  private[spark] val readWriteToken = "alice"
  private[spark] val readWriteUser = "e2e_alice"

  /** 只有「读数据」权限的那一个。令牌 `bob` 由服务端映射到该主体。 */
  private[spark] val readOnlyToken = "bob"
  private[spark] val readOnlyUser = "e2e_bob"

  // 装配用的实体名。带前缀是为了不和既有用例（e2e_reader / authz_* 等）撞名。
  private val readWritePrincipalRole = "e2e_authz_rw_role"
  private val readOnlyPrincipalRole = "e2e_authz_ro_role"
  private val readWriteCatalogRole = "e2e_authz_rw"
  private val readOnlyCatalogRole = "e2e_authz_ro"

  /** 两个角色的授权集合，装配完照此自查。 */
  private val readWriteGrants = Set("NAMESPACE_CREATE", "NAMESPACE_LIST",
    "NAMESPACE_READ_PROPERTIES", "TABLE_CREATE", "TABLE_LIST", "TABLE_READ_PROPERTIES",
    "TABLE_READ_DATA", "TABLE_WRITE_DATA")
  private val readOnlyGrants = Set("NAMESPACE_LIST", "NAMESPACE_READ_PROPERTIES",
    "TABLE_LIST", "TABLE_READ_PROPERTIES", "TABLE_READ_DATA")

  /**
   * 装配只跑一次。
   *
   * <p>状态放在伴生对象而不是测试类字段里：JUnit 默认**每个用例新建一个测试类实例**，
   * 实例字段记不住「已经装配过」。服务端是内存库、随本次运行一起消失，
   * 因此一次运行内不需要把主体删掉。
   */
  @volatile private var prepared = false

  private[spark] def ensureFixture(): Unit = synchronized {
    if (prepared) {
      return
    }
    registerDataPlaneCatalog()
    wireIdentities()
    prepared = true
  }

  /**
   * 先让服务端把数据面要用的 catalog 登记出来。
   *
   * <p>顺序不能反：Spark 客户端请求的仓库名（`spark.sql.catalog.<catalog>.warehouse`）
   * 就是它之后要用的 prefix，服务端只有在 `paimon.rest.auto-create-catalog=true` 时才
   * 按需登记它。而管理面建 catalog role 之前会按名字 `require` 这个 catalog，
   * 不存在就直接 404——catalog 不先造出来，下一步建角色必然失败。
   *
   * <p>这里带 root 的令牌：`/v1/config` 免的是**授权**（`CatalogAccessRules.isPublic`），
   * 不是认证——开了 `auth.enabled` 之后不带令牌照样 401。
   */
  private def registerDataPlaneCatalog(): Unit = {
    val uri = URI.create(LiveSparkSession.catalogUrl
      + "/v1/config?warehouse=" + LiveSparkSession.catalog)
    val request = HttpRequest.newBuilder(uri)
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + LiveSparkSession.token)
      .GET().build()
    val response = HttpClient.newHttpClient().send(request,
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(),
      () => "GET /v1/config 应当把数据面 catalog 登记出来，需要服务端开启 "
        + "paimon.rest.auto-create-catalog；实际响应: " + response.body())
  }

  /**
   * 用 root 的管理语句把两个用户装配好：主体 → principal role → catalog role → 资源授权。
   *
   * <p>授权都挂在 **catalog 级**：两个用户要能看到彼此建出来的库与表，而库名是每次随机
   * 生成的，授权不可能事先写死在某个 namespace 上。
   *
   * <p>两个角色的权限故意拉开：读写那边是「建库 + 建表 + 读写数据」，只读那边只有
   * 「看得见 + 读得到」。少给一项，就会有一条用例如实报出缺什么权限。
   */
  private def wireIdentities(): Unit = {
    val c = catalog
    def root(statement: String): Unit = {
      LiveSparkSession.instance.sql(statement).collect()
      ()
    }

    // 幂等：正常不会残留（内存库），但对着同一个实例重跑时要能再装配一遍
    root(s"DROP PRINCIPAL IF EXISTS $readWriteUser")
    root(s"DROP PRINCIPAL IF EXISTS $readOnlyUser")
    root(s"DROP CATALOG ROLE IF EXISTS $readWriteCatalogRole IN CATALOG $c")
    root(s"DROP CATALOG ROLE IF EXISTS $readOnlyCatalogRole IN CATALOG $c")
    root(s"DROP PRINCIPAL ROLE IF EXISTS $readWritePrincipalRole")
    root(s"DROP PRINCIPAL ROLE IF EXISTS $readOnlyPrincipalRole")

    root(s"CREATE PRINCIPAL $readWriteUser")
    root(s"CREATE PRINCIPAL $readOnlyUser")
    root(s"CREATE PRINCIPAL ROLE $readWritePrincipalRole PROPERTIES ('purpose' = 'e2e-authz')")
    root(s"CREATE PRINCIPAL ROLE $readOnlyPrincipalRole PROPERTIES ('purpose' = 'e2e-authz')")
    root(s"CREATE CATALOG ROLE $readWriteCatalogRole IN CATALOG $c")
    root(s"CREATE CATALOG ROLE $readOnlyCatalogRole IN CATALOG $c")

    // 主体 → principal role；principal role → catalog role（两段都是多对多，要显式装配）
    root(s"GRANT PRINCIPAL ROLE $readWritePrincipalRole TO PRINCIPAL $readWriteUser")
    root(s"GRANT PRINCIPAL ROLE $readOnlyPrincipalRole TO PRINCIPAL $readOnlyUser")
    root(s"GRANT CATALOG ROLE $readWriteCatalogRole TO PRINCIPAL ROLE $readWritePrincipalRole IN CATALOG $c")
    root(s"GRANT CATALOG ROLE $readOnlyCatalogRole TO PRINCIPAL ROLE $readOnlyPrincipalRole IN CATALOG $c")

    // 读写用户：建库、建表、读写数据。
    //
    // 三个读元数据的权限（LIST / READ_PROPERTIES）看着与「读写数据」无关，其实都是必须的：
    // 客户端每次 loadTable 都要读表属性（TABLE_READ_PROPERTIES），而
    // **`SparkCatalog.initialize` 一开始就会去读默认命名空间 `default` 的属性**
    // （`GET /v1/{prefix}/databases/default`）——Spark 首次解析到该 catalog 的限定名时懒加载它。
    // 因此任何能用这个 catalog 跑 SQL 的身份都躲不过 NAMESPACE_READ_PROPERTIES，
    // 与它读不读数据无关；少了它连第一句 SELECT 都到不了表。
    //
    // 刻意**不给** DROP 系权限：能建、能写不等于能删，删除是单独授予的权限。
    // 这也意味着下面的清理是尽力而为，删不掉是预期行为。
    root("GRANT NAMESPACE_CREATE, NAMESPACE_LIST, NAMESPACE_READ_PROPERTIES, "
      + "TABLE_CREATE, TABLE_LIST, TABLE_READ_PROPERTIES, "
      + s"TABLE_READ_DATA, TABLE_WRITE_DATA ON CATALOG $c TO CATALOG ROLE $readWriteCatalogRole")

    // 只读用户：看得见 + 读得到，别的都不给。
    // NAMESPACE_READ_PROPERTIES 不是「顺手给的」——见上面的说明，它和读数据是绑在一起的。
    root("GRANT NAMESPACE_LIST, NAMESPACE_READ_PROPERTIES, TABLE_LIST, "
      + s"TABLE_READ_PROPERTIES, TABLE_READ_DATA ON CATALOG $c TO CATALOG ROLE $readOnlyCatalogRole")

    // 自查：装配没生效时（例如两个令牌没配上、或 catalog 名字对不上），后面每个用例都会
    // 以「谁缺什么权限」的报文失败——读起来像权限判定错了。这里先把装配结果读回来，
    // 让环境问题在装配阶段就露出原貌。
    assertEquals(readWriteGrants, grantsOf(readWriteCatalogRole),
      "读写角色的授权集合与预期不符")
    assertEquals(readOnlyGrants, grantsOf(readOnlyCatalogRole),
      "只读角色的授权集合与预期不符")
  }

  /** `SHOW GRANTS` 的第七列是权限；这里只取权限做集合比较。 */
  private def grantsOf(catalogRole: String): Set[String] =
    LiveSparkSession.instance
      .sql(s"SHOW GRANTS FOR CATALOG ROLE $catalogRole IN CATALOG ${catalog}")
      .collect()
      .map(_.getString(6))
      .toSet
}

class SparkSqlAuthorizationTests {

  import SparkSqlAuthorizationTests._

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")
    SparkSqlAuthorizationTests.ensureFixture()
  }

  private def sqlAs(session: SparkSession, statement: String): Array[Row] =
    session.sql(statement).collect()

  /** 每个用例独占一个库，避免互相看见对方的表。 */
  private def newDatabase(tag: String): String =
    "authz_" + tag + "_" + UUID.randomUUID().toString.replace("-", "").substring(0, 8)

  /** 用有写权限的那个身份把「一张有数据的表」准备好。 */
  private def prepareTable(session: SparkSession, database: String): Unit = {
    sqlAs(session, s"CREATE DATABASE $catalog.$database")
    sqlAs(session, s"CREATE TABLE $catalog.$database.orders (id BIGINT, v STRING) USING paimon "
      + "TBLPROPERTIES ('primary-key' = 'id', 'bucket' = '1')")
    sqlAs(session, s"INSERT INTO $catalog.$database.orders VALUES (1, 'x'), (2, 'y')")
  }

  /**
   * 尽力而为地回收用例自己的库。
   *
   * <p>**删不掉是预期结果**：读写的那个角色刻意没有被授予 `TABLE_DROP` / `NAMESPACE_DROP`
   * ——能建、能写不等于能删。库名每次随机，残留不影响别的用例；服务端是内存库，
   * 整轮跑完一起消失。真正要保住的是「清理失败不能把用例判成失败」。
   */
  private def dropDatabaseQuietly(session: SparkSession, database: String): Unit = {
    try {
      sqlAs(session, s"SHOW TABLES IN $catalog.$database")
        .map(_.getString(1))
        .foreach { table =>
          try {
            sqlAs(session, s"DROP TABLE IF EXISTS $catalog.$database.$table")
          } catch {
            case _: Throwable => ()
          }
        }
    } catch {
      case _: Throwable => ()
    }
    try {
      sqlAs(session, s"DROP DATABASE IF EXISTS $catalog.$database")
    } catch {
      case _: Throwable => ()
    }
  }

  /**
   * 跑一段 SQL 并断言它被服务端的授权判定拒绝。
   *
   * @param principal    期望被点名的那个主体
   * @param action       用例描述，失败时用来说明是哪一步
   * @param expectedLack 服务端报文里应当点名的、缺失的那一项权限
   */
  private def assertDenied(principal: String, action: String, expectedLack: String)
                          (body: => Any): Unit = {
    val error = assertThrows(classOf[Throwable], () => { body; () })
    val text = messages(error)

    // 锚点必须取**服务端自己的措辞**（`AuthorizationService.require` 的原文），不能用
    // Paimon 客户端加的外壳。原因是同一个 403 在客户端有两种落地方式：
    //   - 走 commit 的写入、以及 DROP 一类操作：被包装成 `TableNoPermissionException`，
    //     外层消息是「Table x has no permission. Cause by <服务端原文>」；
    //   - `CREATE TABLE` / `CREATE DATABASE`：`RESTCatalog` 不包装，直接抛出
    //     `ForbiddenException`，消息就是服务端原文。
    // 只锚「has no permission」会在第二种情况下误报失败，故锚两者的公共部分。
    assertTrue(text.contains("does not have permission"),
      s"$action 应当被拒（应报服务端授权判定原文 does not have permission），实际: $text")

    // 光「失败了」还不够：可能是网络、文件系统权限之类的偶发原因。点名主体与缺失权限，
    // 才能证明拒绝确实来自本服务端的 RBAC 判定。
    assertTrue(text.contains(s"principal $principal lacks $expectedLack"),
      s"$action 的拒绝应当来自本服务端授权判定、并点名 $principal 缺少 $expectedLack，实际: $text")
  }

  /** 异常链上每条异常的 message——被 Spark / Paimon 包了几层要看得见。 */
  private def messages(error: Throwable): String =
    Iterator.iterate(error)(_.getCause)
      .takeWhile(_ != null)
      .take(20)
      .map(e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
      .mkString(" <- ")

  // ------------------------------------------------------------------ 用户 A：建库建表 + 读写

  /**
   * 读写用户可以完整走一遍：建库 → 建表 → 写入 → 读回。
   *
   * <p>这条同时是权限用例的**对照组**：只有它绿了，B 那边的拒绝才能说明是
   * 「B 少了权限」，而不是「整个链路压根不通」。
   */
  @Test
  def readWriteUserCanCreateDatabaseCreateTableAndWriteThenRead(): Unit = {
    val session = LiveSparkSession.sessionAs(readWriteToken)
    val database = newDatabase("rw")
    try {
      sqlAs(session, s"CREATE DATABASE $catalog.$database")
      sqlAs(session, s"CREATE TABLE $catalog.$database.orders (id BIGINT, v STRING) USING paimon "
        + "TBLPROPERTIES ('primary-key' = 'id', 'bucket' = '1')")
      sqlAs(session, s"INSERT INTO $catalog.$database.orders VALUES (1, 'x'), (2, 'y')")

      val rows = sqlAs(session, s"SELECT id, v FROM $catalog.$database.orders ORDER BY id")
      assertEquals(2, rows.length, "写入的两行应当都能读回来")
      assertEquals(1L, rows(0).getLong(0))
      assertEquals("x", rows(0).getString(1))
      assertEquals(2L, rows(1).getLong(0))
      assertEquals("y", rows(1).getString(1))

      // 建、写、读都有，**但没有删**：DROP 是单独授予的权限，读写角色不该顺带拿到它。
      // 这条同时解释了下面清理为什么可以不成功。
      assertDenied(readWriteUser, "读写用户 DROP TABLE", "TABLE_DROP") {
        sqlAs(session, s"DROP TABLE $catalog.$database.orders")
      }
    } finally {
      dropDatabaseQuietly(session, database)
    }
  }

  // ------------------------------------------------------------------ 用户 B：只能读

  /**
   * 只读用户读得到别人写的数据，但每一次写都在**服务端的授权判定**处被挡下。
   *
   * <p>拒绝的检查分三层，缺一层都压不住回归：
   *
   * <ol>
   *   <li>确实抛错，且报错点名「缺哪一项权限」（见 {@link #assertDenied}）
   *       ——建表缺 `TABLE_CREATE`、建库缺 `NAMESPACE_CREATE`、写数据缺 `TABLE_WRITE_DATA`；
   *   <li>读与「发现」在同一时刻仍然是通的（那次 `SELECT` 与 `SHOW TABLES`）
   *       ——证明拒绝是「这一项没授权」，不是「这个主体整个被关在门外」；
   *   <li>被拒之后数据**没变**——写入真的没生效，而不是「报了错但已经写进去了」。
   * </ol>
   */
  @Test
  def readOnlyUserReadsDataButEveryWriteIsDenied(): Unit = {
    val writer = LiveSparkSession.sessionAs(readWriteToken)
    val database = newDatabase("ro")
    prepareTable(writer, database)
    try {
      val reader = LiveSparkSession.sessionAs(readOnlyToken)

      // ① 读：正常，且看到的是真实数据
      val rows = sqlAs(reader, s"SELECT id, v FROM $catalog.$database.orders ORDER BY id")
      assertEquals(2, rows.length, "只读用户应当能读到全部数据")
      assertEquals(1L, rows(0).getLong(0))
      assertEquals("x", rows(0).getString(1))

      // ② 发现能力：看得见有哪些表（TABLE_LIST），否则「只读」会变成「只有精确知道表名才能读」
      assertTrue(sqlAs(reader, s"SHOW TABLES IN $catalog.$database")
        .exists(_.getString(1) == "orders"), "只读用户应当能看到表列表")

      // ③ 写数据：被拒，缺的是数据写权限
      assertDenied(readOnlyUser, "只读用户 INSERT", "TABLE_WRITE_DATA") {
        sqlAs(reader, s"INSERT INTO $catalog.$database.orders VALUES (3, 'z')")
      }

      // ④ 建表：被拒，缺的是建表权限
      assertDenied(readOnlyUser, "只读用户 CREATE TABLE", "TABLE_CREATE") {
        sqlAs(reader, s"CREATE TABLE $catalog.$database.orders_copy (id BIGINT) USING paimon")
      }

      // ⑤ 建库：被拒，缺的是建命名空间的权限
      assertDenied(readOnlyUser, "只读用户 CREATE DATABASE", "NAMESPACE_CREATE") {
        sqlAs(reader, s"CREATE DATABASE $catalog.${database}_extra")
      }

      // ⑥ 三次被拒之后数据仍是两行：写入没有「报错但半途生效」
      val after = sqlAs(reader, s"SELECT count(*) FROM $catalog.$database.orders")
      assertEquals(2L, after(0).getLong(0), "被拒绝的写入不应改变数据")
    } finally {
      dropDatabaseQuietly(writer, database)
    }
  }
}
