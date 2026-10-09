package io.github.melin.paimonrest.spark

import io.github.melin.paimonrest.spark.client.{ManagementApiClient, ManagementApiException}
import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 对着**真实服务端**的验收测试。
 *
 * <p>为什么还需要这一层：前面三层测试里的管理 API 都是桩，桩只能证明「客户端与我的
 * 假设自洽」，证明不了「服务端真的接受这些请求」。这一层用真的 Paimon Rest Catalog Server
 * 跑完整的 SQL 链路，验证的是两侧实现的契约一致性——请求路径、请求体字段、
 * 状态码、以及 `entityVersion` 这类容易被双方各自理解错的细节。
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
 * mvn -o -pl paimon-rest-spark test -Dtest=ManagementSqlLiveServerTests \
 *     -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
 *     -De2e.management.token=root
 * </pre>
 */
object ManagementSqlLiveServerTests {

  /**
   * 会话按进程共享。
   *
   * <p>实现在 {@link LiveSparkSession}：一个 JVM 只能有一个 SparkContext，
   * 而 `spark.sql.extensions` 只在建会话时生效，因此所有需要真实服务端的
   * 测试类必须取同一个会话——包括需要 Paimon 扩展的建表用例
   * （见 {@link PaimonTableDdlTests}）。这里保留这个入口，
   * 是为了不打断既有引用（{@link SparkSqlDocExamplesTests}）。
   */
  lazy val session: SparkSession = LiveSparkSession.instance
}

class ManagementSqlLiveServerTests {

  private val url = Option(System.getProperty("e2e.management.url")).filter(_.nonEmpty)
  private val token = System.getProperty("e2e.management.token", "")

  @BeforeEach
  def requireLiveServer(): Unit =
    Assumptions.assumeTrue(url.isDefined,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")

  private def session: SparkSession = ManagementSqlLiveServerTests.session

  /** 直接用 HTTP 读原始响应：`SHOW` 语句不返回属性，验证属性必须走原始接口。 */
  private def rawGet(path: String): String = {
    val request = HttpRequest.newBuilder(URI.create(url.get + path))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + token)
      .header("Accept", "application/json")
      .GET().build()
    val response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(), () => "GET " + path + " -> " + response.body())
    response.body()
  }

  private def collect(sql: String): Array[Row] = session.sql(sql).collect()

  // ------------------------------------------------------------------ 主体

  @Test
  def principalLifecycleRoundTripsThroughRealSql(): Unit = {
    val name = "e2e_analyst"
    collect(s"DROP PRINCIPAL IF EXISTS $name")

    // 创建：一次性返回 clientId 与明文密钥
    val created = collect(s"CREATE PRINCIPAL $name PROPERTIES ('purpose' = 'e2e')")
    assertEquals(1, created.length)
    assertEquals(name, created(0).getString(0))
    assertNotNull(created(0).getString(1), "应返回 clientId")
    val firstSecret = created(0).getString(2)
    assertNotNull(firstSecret, "应返回一次性明文密钥")

    // 重复创建：服务端 409，经客户端映射成 alreadyExists
    val duplicate = assertThrows(classOf[ManagementApiException], () => collect(s"CREATE PRINCIPAL $name"))
    assertTrue(duplicate.alreadyExists(),
      "重复创建应映射为 409，实际: " + duplicate.status() + " / " + duplicate.getMessage)

    // IF NOT EXISTS 应当吞掉 409
    assertTrue(collect(s"CREATE PRINCIPAL IF NOT EXISTS $name").isEmpty)

    // ALTER：SQL 是增量语义，而规格的 PUT 是整体替换，客户端必须读-合并-回写
    collect(s"ALTER PRINCIPAL $name SET PROPERTIES ('tier' = 'gold')")
    assertTrue(rawGet(s"/principals/$name").contains("\"tier\":\"gold\""), "SET 应写入属性")

    collect(s"ALTER PRINCIPAL $name SET PROPERTIES ('owner' = 'risk')")
    val merged = rawGet(s"/principals/$name")
    assertTrue(merged.contains("\"tier\":\"gold\""), "第二次 SET 不应丢掉先前的属性: " + merged)
    assertTrue(merged.contains("\"owner\":\"risk\""), merged)

    collect(s"ALTER PRINCIPAL $name UNSET PROPERTIES (tier)")
    val unset = rawGet(s"/principals/$name")
    assertFalse(unset.contains("\"tier\""), "UNSET 应移除属性: " + unset)
    assertTrue(unset.contains("\"owner\":\"risk\""), "UNSET 不应影响其它属性: " + unset)

    // SHOW
    val listed = collect("SHOW PRINCIPALS")
    assertTrue(listed.exists(_.getString(0) == name), "SHOW PRINCIPALS 应包含刚创建的主体")

    // ROTATE：重新签发凭据，与首次的密钥不同
    val rotated = collect(s"ROTATE PRINCIPAL $name")
    assertEquals(1, rotated.length)
    assertNotEquals(firstSecret, rotated(0).getString(2), "轮换后密钥应变化")

    // 清理
    assertTrue(collect(s"DROP PRINCIPAL $name").isEmpty)
    assertFalse(collect("SHOW PRINCIPALS").exists(_.getString(0) == name), "删除后不应再出现在列表里")

    // IF EXISTS 应当吞掉 404
    assertTrue(collect(s"DROP PRINCIPAL IF EXISTS $name").isEmpty)
    val missing = assertThrows(classOf[ManagementApiException], () => collect(s"DROP PRINCIPAL $name"))
    assertTrue(missing.notFound(), "删除不存在的主体应映射为 404，实际: " + missing.status())
  }

  // ------------------------------------------------------------------ 角色与授权链路

  @Test
  def roleAndGrantChainRoundTripsThroughRealSql(): Unit = {
    val principalRole = "e2e_reader_role"
    val catalogRole = "e2e_reader_catalog_role"
    val principal = "e2e_reader"

    // 前一轮可能残留（服务端是内存库，正常不会）
    collect(s"DROP PRINCIPAL IF EXISTS $principal")
    collect(s"DROP CATALOG ROLE IF EXISTS $catalogRole IN CATALOG paimon")
    collect(s"DROP PRINCIPAL ROLE IF EXISTS $principalRole")

    collect(s"CREATE PRINCIPAL $principal")
    collect(s"CREATE PRINCIPAL ROLE $principalRole PROPERTIES ('purpose' = 'e2e')")
    collect(s"CREATE CATALOG ROLE $catalogRole IN CATALOG paimon")

    // 角色的 ALTER 同样是读-合并-回写，且连续两次才暴露版本号读错的问题：
    // 第一次读到的版本若被解析成 0，服务端放行并把版本推进到 1，第二次就会 409
    collect(s"ALTER PRINCIPAL ROLE $principalRole SET PROPERTIES ('tier' = 'gold')")
    collect(s"ALTER PRINCIPAL ROLE $principalRole SET PROPERTIES ('owner' = 'risk')")
    val principalRoleJson = rawGet(s"/principal-roles/$principalRole")
    assertTrue(principalRoleJson.contains("\"tier\":\"gold\""),
      "第二次 SET 不应丢掉先前属性: " + principalRoleJson)
    assertTrue(principalRoleJson.contains("\"owner\":\"risk\""), principalRoleJson)

    collect(s"ALTER CATALOG ROLE $catalogRole IN CATALOG paimon SET PROPERTIES ('tier' = 'gold')")
    collect(s"ALTER CATALOG ROLE $catalogRole IN CATALOG paimon SET PROPERTIES ('owner' = 'risk')")
    val catalogRoleJson = rawGet(s"/catalogs/paimon/catalog-roles/$catalogRole")
    assertTrue(catalogRoleJson.contains("\"tier\":\"gold\""),
      "第二次 SET 不应丢掉先前属性: " + catalogRoleJson)

    collect(s"ALTER PRINCIPAL ROLE $principalRole UNSET PROPERTIES (tier)")
    assertFalse(rawGet(s"/principal-roles/$principalRole").contains("\"tier\""), "UNSET 应移除属性")

    // 主体 → principal role → catalog role
    collect(s"GRANT PRINCIPAL ROLE $principalRole TO PRINCIPAL $principal")
    collect(s"GRANT CATALOG ROLE $catalogRole TO PRINCIPAL ROLE $principalRole IN CATALOG paimon")

    assertTrue(collect(s"SHOW PRINCIPAL ROLES FOR PRINCIPAL $principal")
      .exists(_.getString(0) == principalRole), "主体应持有该 principal role")
    assertTrue(collect(s"SHOW PRINCIPALS FOR PRINCIPAL ROLE $principalRole")
      .exists(_.getString(0) == principal), "principal role 应关联该主体")
    assertTrue(collect(s"SHOW CATALOG ROLES FOR PRINCIPAL ROLE $principalRole IN CATALOG paimon")
      .exists(_.getString(0) == catalogRole), "principal role 应持有该 catalog role")

    // 三种资源层级的授权
    collect(s"GRANT TABLE_READ_DATA ON TABLE default.e2e_orders IN CATALOG paimon TO CATALOG ROLE $catalogRole")
    collect(s"GRANT NAMESPACE_LIST ON NAMESPACE default IN CATALOG paimon TO CATALOG ROLE $catalogRole")
    collect(s"GRANT CATALOG_MANAGE_CONTENT ON CATALOG paimon TO CATALOG ROLE $catalogRole")

    def grants: Array[Row] = collect(s"SHOW GRANTS FOR CATALOG ROLE $catalogRole IN CATALOG paimon")
    assertEquals(3, grants.length, "三次授权应各自成为一条记录")

    val byPrivilege = grants.map(row => row.getString(6) -> row).toMap
    assertEquals("default.e2e_orders", byPrivilege("TABLE_READ_DATA").getString(3))
    assertEquals("table", byPrivilege("TABLE_READ_DATA").getString(2))
    assertEquals("default", byPrivilege("NAMESPACE_LIST").getString(3))
    assertEquals("namespace", byPrivilege("NAMESPACE_LIST").getString(2))
    assertEquals("paimon", byPrivilege("CATALOG_MANAGE_CONTENT").getString(3))
    assertEquals("catalog", byPrivilege("CATALOG_MANAGE_CONTENT").getString(2))

    // 一次语句多权限：展开成多条授权
    collect(s"GRANT TABLE_LIST, TABLE_WRITE_DATA ON TABLE default.e2e_orders IN CATALOG paimon " +
      s"TO CATALOG ROLE $catalogRole")
    assertEquals(5, grants.length, "两项权限应新增两条记录")

    // 撤销其中一条
    collect(s"REVOKE TABLE_WRITE_DATA ON TABLE default.e2e_orders IN CATALOG paimon " +
      s"FROM CATALOG ROLE $catalogRole")
    val afterRevoke = grants
    assertEquals(4, afterRevoke.length)
    assertFalse(afterRevoke.exists(_.getString(6) == "TABLE_WRITE_DATA"), "被撤销的权限不应还在")
    assertTrue(afterRevoke.exists(_.getString(6) == "TABLE_LIST"), "其它权限不应受影响")

    // 清理
    collect(s"DROP CATALOG ROLE $catalogRole IN CATALOG paimon")
    collect(s"DROP PRINCIPAL ROLE $principalRole")
    collect(s"DROP PRINCIPAL $principal")
  }

  @Test
  def catalogDiscoveryAndShowCatalogsAgree(): Unit = {
    val catalogs = collect("SHOW CATALOGS")
    assertTrue(catalogs.exists(_.getString(0) == "paimon"),
      "初始配置预置的 catalog 应出现在 SHOW CATALOGS 中")
  }

  // ------------------------------------------------------------------ 授权由服务端强制

  @Test
  def serverDeniesAPrincipalWithoutAnyGrant(): Unit = {
    // 令牌 limited 经服务端 token-principals 映射到主体 e2e_limited，该主体没有任何授权。
    // 管理主体属于服务管理员的职责范围，因此普通主体应被拒绝。
    val limited = new ManagementApiClient(url.get, "limited", Duration.ofSeconds(10))

    val error = assertThrows(classOf[ManagementApiException], () => limited.listPrincipals())

    assertTrue(error.forbidden(),
      "无授权主体管理主体应被拒绝，实际状态码: " + error.status() + " / " + error.getMessage)
  }
}
