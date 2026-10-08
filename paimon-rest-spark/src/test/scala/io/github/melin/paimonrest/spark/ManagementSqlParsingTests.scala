package io.github.melin.paimonrest.spark

import io.github.melin.paimonrest.spark.client.ManagementApiClient
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.types.{DataType, StructType}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

import java.time.Duration

import scala.collection.JavaConverters._

/**
 * 解析层测试。
 *
 * <p>只验证「哪条语句被识别成哪个命令、参数解成什么」：不建 `SparkSession`、
 * 不发 HTTP 请求。命令对象本身是无状态的（只带参数与客户端引用），因此可以
 * 直接对解析结果做断言。
 *
 * <p>客户端指向一个不会有人监听的端口：解析不触发任何网络调用，
 * 构造它只是为了满足命令的签名。
 */
class ManagementSqlParsingTests {

  /** 记录被交回的语句，用于验证「不是管理语句时原样交给原生解析器」。 */
  private class RecordingDelegate extends ParserInterface {
    val delegated = scala.collection.mutable.ListBuffer.empty[String]

    override def parsePlan(sqlText: String): LogicalPlan = {
      delegated += sqlText
      throw new UnsupportedOperationException("delegated: " + sqlText)
    }

    override def parseQuery(sqlText: String): LogicalPlan = parsePlan(sqlText)

    override def parseExpression(sqlText: String): Expression =
      throw new UnsupportedOperationException("unused")

    override def parseTableIdentifier(sqlText: String): TableIdentifier =
      throw new UnsupportedOperationException("unused")

    override def parseFunctionIdentifier(sqlText: String): FunctionIdentifier =
      throw new UnsupportedOperationException("unused")

    override def parseMultipartIdentifier(sqlText: String): Seq[String] =
      throw new UnsupportedOperationException("unused")

    override def parseTableSchema(sqlText: String): StructType =
      throw new UnsupportedOperationException("unused")

    override def parseDataType(sqlText: String): DataType =
      throw new UnsupportedOperationException("unused")
  }

  private val delegate = new RecordingDelegate()
  private val parser = new PaimonRestSqlParser(
    delegate,
    () => new ManagementApiClient("http://127.0.0.1:1/api/management/v1", null, Duration.ofSeconds(1)))

  /** 断言语句被识别为管理语句，并取出对应的命令。 */
  private def parseAs[T](sql: String): T = parser.parseManagementStatement(sql) match {
    case Some(plan) => plan.asInstanceOf[T]
    case None => throw new AssertionError("语句未被识别为管理语句: " + sql)
  }

  /** 断言语句**不**被本扩展接管。 */
  private def assertNotManagement(sql: String): Unit =
    assertEquals(None, parser.parseManagementStatement(sql),
      s"语句不应被管理语法接管: $sql")

  // ------------------------------------------------------------------ DDL：主体

  @Test
  def createPrincipalParsesNamePropertiesAndIfNotExists(): Unit = {
    val command = parseAs[CreatePrincipalCommand](
      "CREATE PRINCIPAL analyst PROPERTIES ('owner' = 'risk-team', tier = gold)")
    assertEquals("analyst", command.name)
    assertEquals(Map("owner" -> "risk-team", "tier" -> "gold"), command.properties)
    assertFalse(command.ifNotExists)

    val conditional = parseAs[CreatePrincipalCommand]("CREATE PRINCIPAL IF NOT EXISTS analyst")
    assertEquals("analyst", conditional.name)
    assertEquals(Map.empty[String, String], conditional.properties)
    assertTrue(conditional.ifNotExists)
  }

  @Test
  def quotedIdentifiersAndKeywordCaseAreAccepted(): Unit = {
    // 连字符不是 IDENTIFIER 的合法字符，必须用反引号
    val quoted = parseAs[CreatePrincipalCommand]("create principal `svc-analytics@example`")
    assertEquals("svc-analytics@example", quoted.name)

    // 反引号内的 `` 表示一个反引号
    val escaped = parseAs[DropPrincipalCommand]("DROP PRINCIPAL `a``b`")
    assertEquals("a`b", escaped.name)
  }

  @Test
  def dropPrincipalParsesIfExists(): Unit = {
    assertTrue(parseAs[DropPrincipalCommand]("DROP PRINCIPAL IF EXISTS analyst").ifExists)
    assertFalse(parseAs[DropPrincipalCommand]("DROP PRINCIPAL analyst").ifExists)
  }

  @Test
  def alterPrincipalParsesSetAndUnsetSeparately(): Unit = {
    val set = parseAs[AlterPrincipalCommand](
      "ALTER PRINCIPAL analyst SET PROPERTIES ('owner' = 'quant')")
    assertEquals("analyst", set.name)
    assertEquals(Map("owner" -> "quant"), set.toSet)
    assertTrue(set.toUnset.isEmpty)

    val unset = parseAs[AlterPrincipalCommand]("ALTER PRINCIPAL analyst UNSET PROPERTIES (owner, tier)")
    assertEquals(Set("owner", "tier"), unset.toUnset)
    assertTrue(unset.toSet.isEmpty)
  }

  @Test
  def resetAndRotateDifferOnlyInTheRotateFlag(): Unit = {
    assertFalse(parseAs[ResetPrincipalCommand]("RESET PRINCIPAL analyst").rotate)
    assertTrue(parseAs[ResetPrincipalCommand]("ROTATE PRINCIPAL analyst").rotate)
    assertEquals("analyst", parseAs[ResetPrincipalCommand]("rotate principal analyst").name)
  }

  // ------------------------------------------------------------------ DDL：角色

  @Test
  def principalRoleDdlParses(): Unit = {
    val created = parseAs[CreatePrincipalRoleCommand](
      "CREATE PRINCIPAL ROLE IF NOT EXISTS data_engineer PROPERTIES ('purpose' = 'etl')")
    assertEquals("data_engineer", created.name)
    assertEquals(Map("purpose" -> "etl"), created.properties)
    assertTrue(created.ifNotExists)

    assertTrue(parseAs[DropPrincipalRoleCommand]("DROP PRINCIPAL ROLE IF EXISTS data_engineer").ifExists)

    val altered = parseAs[AlterPrincipalRoleCommand](
      "ALTER PRINCIPAL ROLE data_engineer UNSET PROPERTIES (purpose)")
    assertEquals(Set("purpose"), altered.toUnset)
  }

  @Test
  def catalogRoleDdlParsesIncludingTheOwningCatalog(): Unit = {
    val created = parseAs[CreateCatalogRoleCommand](
      "CREATE CATALOG ROLE reader IN CATALOG paimon PROPERTIES (scope = 'north')")
    assertEquals("paimon", created.catalog)
    assertEquals("reader", created.name)
    assertEquals(Map("scope" -> "north"), created.properties)

    val dropped = parseAs[DropCatalogRoleCommand]("DROP CATALOG ROLE reader IN CATALOG paimon")
    assertEquals("paimon", dropped.catalog)
    assertEquals("reader", dropped.name)

    val altered = parseAs[AlterCatalogRoleCommand](
      "ALTER CATALOG ROLE reader IN CATALOG paimon SET PROPERTIES (scope = 'south')")
    assertEquals("paimon", altered.catalog)
    assertEquals(Map("scope" -> "south"), altered.toSet)
  }

  // ------------------------------------------------------------------ DCL：角色装配

  @Test
  def grantAndRevokePrincipalRoleDifferOnlyInTheRevokeFlag(): Unit = {
    val granted = parseAs[GrantPrincipalRoleCommand](
      "GRANT PRINCIPAL ROLE data_engineer TO PRINCIPAL analyst")
    assertEquals("analyst", granted.principal)
    assertEquals("data_engineer", granted.principalRole)
    assertFalse(granted.revoke)

    val revoked = parseAs[GrantPrincipalRoleCommand](
      "REVOKE PRINCIPAL ROLE data_engineer FROM PRINCIPAL analyst")
    assertEquals("analyst", revoked.principal)
    assertEquals("data_engineer", revoked.principalRole)
    assertTrue(revoked.revoke)
  }

  @Test
  def grantAndRevokeCatalogRoleDifferOnlyInTheRevokeFlag(): Unit = {
    val granted = parseAs[GrantCatalogRoleCommand](
      "GRANT CATALOG ROLE reader TO PRINCIPAL ROLE data_engineer IN CATALOG paimon")
    assertEquals("data_engineer", granted.principalRole)
    assertEquals("paimon", granted.catalog)
    assertEquals("reader", granted.catalogRole)
    assertFalse(granted.revoke)

    val revoked = parseAs[GrantCatalogRoleCommand](
      "REVOKE CATALOG ROLE reader FROM PRINCIPAL ROLE data_engineer IN CATALOG paimon")
    assertTrue(revoked.revoke)
  }

  // ------------------------------------------------------------------ DCL：资源授权

  @Test
  def catalogGrantProducesACatalogScopedGrant(): Unit = {
    val command = parseAs[GrantPrivilegesCommand](
      "GRANT CATALOG_MANAGE_CONTENT ON CATALOG paimon TO CATALOG ROLE admin")
    assertEquals("paimon", command.catalog)
    assertEquals("admin", command.catalogRole)
    assertFalse(command.revoke)
    assertEquals(1, command.grants.size)

    val grant = command.grants.head
    assertEquals("catalog", grant.`type`())
    assertTrue(grant.namespace().isEmpty)
    assertNull(grant.objectName())
    assertEquals("CATALOG_MANAGE_CONTENT", grant.privilege())
  }

  @Test
  def namespaceGrantKeepsTheNamespaceAndHasNoObjectName(): Unit = {
    val command = parseAs[GrantPrivilegesCommand](
      "GRANT NAMESPACE_CREATE ON NAMESPACE raw_layer IN CATALOG paimon TO CATALOG ROLE writer")
    val grant = command.grants.head
    assertEquals("namespace", grant.`type`())
    assertEquals(Seq("raw_layer").asJava, grant.namespace())
    assertNull(grant.objectName())
    assertEquals("NAMESPACE_CREATE", grant.privilege())
  }

  @Test
  def objectGrantSplitsTheNamespaceFromTheObjectName(): Unit = {
    val table = parseAs[GrantPrivilegesCommand](
      "GRANT TABLE_READ_DATA ON TABLE raw_layer.events.orders IN CATALOG paimon TO CATALOG ROLE reader")
    val tableGrant = table.grants.head
    assertEquals("table", tableGrant.`type`())
    assertEquals(Seq("raw_layer", "events").asJava, tableGrant.namespace())
    assertEquals("orders", tableGrant.objectName())

    val view = parseAs[GrantPrivilegesCommand](
      "GRANT VIEW_READ_DATA ON VIEW raw_layer.v IN CATALOG paimon TO CATALOG ROLE reader")
    assertEquals("view", view.grants.head.`type`())
    assertEquals(Seq("raw_layer").asJava, view.grants.head.namespace())
    assertEquals("v", view.grants.head.objectName())
  }

  @Test
  def policyAndSemanticModelGrantsMapToTheirOwnResourceTypes(): Unit = {
    val policy = parseAs[GrantPrivilegesCommand](
      "GRANT POLICY_READ ON POLICY ns1.mask_pii IN CATALOG paimon TO CATALOG ROLE reader")
    assertEquals("policy", policy.grants.head.`type`())
    assertEquals("mask_pii", policy.grants.head.objectName())

    val semanticModel = parseAs[GrantPrivilegesCommand](
      "GRANT SEMANTIC_MODEL_READ ON SEMANTIC MODEL ns1.revenue IN CATALOG paimon TO CATALOG ROLE reader")
    assertEquals("semantic-model", semanticModel.grants.head.`type`())
    assertEquals("revenue", semanticModel.grants.head.objectName())
  }

  @Test
  def multiplePrivilegesExpandToMultipleGrantsInOrder(): Unit = {
    val command = parseAs[GrantPrivilegesCommand](
      "GRANT TABLE_READ_DATA, TABLE_WRITE_DATA, TABLE_LIST " +
        "ON TABLE raw_layer.events.orders IN CATALOG paimon TO CATALOG ROLE writer")
    assertEquals(3, command.grants.size)
    assertEquals(
      Seq("TABLE_READ_DATA", "TABLE_WRITE_DATA", "TABLE_LIST").asJava,
      command.grants.map(_.privilege()).toList.asJava)
  }

  @Test
  def privilegeNamesAreNormalisedToUppercase(): Unit = {
    val command = parseAs[GrantPrivilegesCommand](
      "grant table_read_data on table raw_layer.orders in catalog paimon to catalog role reader")
    assertEquals("TABLE_READ_DATA", command.grants.head.privilege())
    assertEquals("reader", command.catalogRole)
    assertEquals("paimon", command.catalog)
  }

  @Test
  def revokePrivilegesCarriesTheRevokeFlag(): Unit = {
    val command = parseAs[GrantPrivilegesCommand](
      "REVOKE TABLE_READ_DATA ON TABLE raw_layer.orders IN CATALOG paimon FROM CATALOG ROLE reader")
    assertTrue(command.revoke)
    assertEquals(1, command.grants.size)
  }

  @Test
  def objectGrantWithoutANamespaceIsRejected(): Unit = {
    // 规格要求对象级授权带 namespace，单段名字无法确定作用域，因此直接在解析期拒绝
    val error = assertThrows(classOf[ManagementSqlException], () =>
      parseAs[GrantPrivilegesCommand](
        "GRANT TABLE_READ_DATA ON TABLE orders IN CATALOG paimon TO CATALOG ROLE reader"))
    assertTrue(error.getMessage.contains("namespace"),
      "报错信息应说明需要 namespace，实际为: " + error.getMessage)
  }

  // ------------------------------------------------------------------ SHOW

  @Test
  def showStatementsParseTheirScopes(): Unit = {
    assertNotNull(parseAs[ShowCatalogsCommand]("SHOW CATALOGS"))

    assertTrue(parseAs[ShowPrincipalsCommand]("SHOW PRINCIPALS").forPrincipalRole.isEmpty)
    assertEquals("data_engineer",
      parseAs[ShowPrincipalsCommand]("SHOW PRINCIPALS FOR PRINCIPAL ROLE data_engineer")
        .forPrincipalRole.orNull)

    assertTrue(parseAs[ShowPrincipalRolesCommand]("SHOW PRINCIPAL ROLES").forPrincipal.isEmpty)
    assertEquals("analyst",
      parseAs[ShowPrincipalRolesCommand]("SHOW PRINCIPAL ROLES FOR PRINCIPAL analyst")
        .forPrincipal.orNull)

    val allRoles = parseAs[ShowCatalogRolesCommand]("SHOW CATALOG ROLES IN CATALOG paimon")
    assertEquals("paimon", allRoles.catalog)
    assertTrue(allRoles.forPrincipalRole.isEmpty)

    val ofRole = parseAs[ShowCatalogRolesCommand](
      "SHOW CATALOG ROLES FOR PRINCIPAL ROLE data_engineer IN CATALOG paimon")
    assertEquals("paimon", ofRole.catalog)
    assertEquals("data_engineer", ofRole.forPrincipalRole.orNull)

    val grants = parseAs[ShowGrantsCommand](
      "SHOW GRANTS FOR CATALOG ROLE reader IN CATALOG paimon")
    assertEquals("paimon", grants.catalog)
    assertEquals("reader", grants.catalogRole)
  }

  // ------------------------------------------------------------------ 接管边界

  @Test
  def nonManagementStatementsAreHandedBackToTheNativeParser(): Unit = {
    val delegate = new RecordingDelegate()
    val parser = new PaimonRestSqlParser(delegate,
      () => new ManagementApiClient("http://127.0.0.1:1/api/management/v1", null, Duration.ofSeconds(1)))

    val sql = "SELECT * FROM paimon.raw_layer.orders"
    assertThrows(classOf[UnsupportedOperationException], () => parser.parsePlan(sql))
    assertEquals(List(sql), delegate.delegated.toList)
  }

  @Test
  def statementsThatOnlyLookLikeManagementSqlAreNotSwallowed(): Unit = {
    // Spark 原生的 SHOW / CREATE 语句不能误判
    assertNotManagement("SHOW TABLES")
    assertNotManagement("SHOW DATABASES")
    assertNotManagement("CREATE TABLE t (a INT)")
    assertNotManagement("GRANT SELECT ON TABLE t TO user")
    // 管理关键字开头但不完整：语法要求整句匹配到 EOF，因此不会被接管
    assertNotManagement("CREATE PRINCIPAL")
    assertNotManagement("CREATE PRINCIPAL analyst extra_token")
    assertNotManagement("DROP PRINCIPAL")
  }

  @Test
  def trailingSemicolonAndCommentsAreTolerated(): Unit = {
    assertEquals("analyst", parseAs[DropPrincipalCommand]("DROP PRINCIPAL analyst;").name)
    assertEquals("analyst", parseAs[DropPrincipalCommand]("DROP PRINCIPAL analyst;;").name)
    assertEquals("analyst",
      parseAs[DropPrincipalCommand]("-- 迁移脚本\nDROP PRINCIPAL analyst /* 一次性 */").name)
  }
}
