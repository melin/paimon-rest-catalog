package com.example.paimonrest.spark

import com.example.paimonrest.spark.client.{ManagementApiClient, ManagementApiException}
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.types.StringType

import java.util.LinkedHashMap
import scala.collection.JavaConverters._

/**
 * 全部管理命令的公共基类。
 *
 * <p><b>存在的理由。</b>Spark 3.5 的 `RunnableCommand` 只是一个 trait，它继承了
 * `LogicalPlan`，但没有实现 `TreeNode` 的抽象方法 `withNewChildrenInternal`；
 * 因此直接 `extends RunnableCommand` 的类会被编译器判定为抽象类（报错形如
 * 「class XxxCommand needs to be abstract, since method withNewChildrenInternal
 * in class TreeNode ... is not defined」）。
 *
 * <p>管理命令全部是叶子节点——它们不持有任何子计划，只把语句参数和 REST 客户端
 * 带在身上。这里的实现直接返回自身：即使 Spark 在优化阶段用「新旧子节点」的
 * 方式重建节点（`TreeNode` 的通用遍历机制），也不会有子节点被替换掉。
 *
 * <p>之所以不在每个命令里重复这段代码，是因为 18 个命令的行为完全一致，
 * 分散实现只会给后续新增语句留下漏写的口子。
 */
private[spark] trait ManagementCommand extends RunnableCommand {

  override protected def withNewChildrenInternal(
      newChildren: IndexedSeq[LogicalPlan]): LogicalPlan = this
}

/**
 * 管理语句对应的可执行命令。
 *
 * <p>每个命令实现成 {@link RunnableCommand}：`run` 在分析阶段被调用一次，
 * 返回的 `Seq[Row]` 成为查询结果，因此 `spark.sql("SHOW PRINCIPALS").show()` 能直接看到表格。
 *
 * <p><b>结果集的两种形态。</b>
 *   - 读类语句（SHOW）与 `CREATE PRINCIPAL` 返回数据行，其中 `CREATE PRINCIPAL` 的
 *     明文密钥只在这一次结果里出现，必须用 `show()` / `collect()` 取走，否则只能靠
 *     `ROTATE PRINCIPAL` 重新签发——这一点与服务端「不保存明文」的约定一致；
 *   - 写类语句（CREATE 角色 / DROP / ALTER / GRANT / REVOKE）返回 0 行，
 *     与 Spark 原生 `CREATE TABLE` 这类命令的行为保持一致，便于脚本编排。
 *
 * <p>命令不持有 SparkSession 状态，客户端在构造时注入，因此同一个解析器实例可以
 * 服务同一会话内的多条语句。
 */
private[spark] object ManagementCommands {

  /** 结果集的一列：列名 + 字符串类型。所有列都是可空字符串，唯一例外见各命令的 output。 */
  def attr(name: String, nullable: Boolean = true): AttributeReference =
    AttributeReference(name, StringType, nullable)()

  /** Scala Map → Java Map，避免依赖 Scala 版本的隐式转换。 */
  def toJavaMap(properties: Map[String, String]): java.util.Map[String, String] = {
    val result = new LinkedHashMap[String, String]()
    properties.foreach { case (key, value) => result.put(key, value) }
    result
  }

  /** 对象名在结果集中统一用点号连接的多级名表示。 */
  def qualified(namespace: Seq[String], objectName: String): String =
    (namespace :+ objectName).mkString(".")
}

// ---------------------------------------------------------------------- DDL：主体

private[spark] case class CreatePrincipalCommand(
    name: String,
    properties: Map[String, String],
    ifNotExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("principal", nullable = false),
    ManagementCommands.attr("client_id"),
    ManagementCommands.attr("client_secret"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      val credentials = client.createPrincipal(name, ManagementCommands.toJavaMap(properties))
      Seq(Row(credentials.name(), credentials.clientId(), credentials.clientSecret()))
    } catch {
      case e: ManagementApiException if ifNotExists && e.alreadyExists() => Seq.empty
    }
  }
}

private[spark] case class DropPrincipalCommand(
    name: String,
    ifExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.dropPrincipal(name)
      Seq.empty
    } catch {
      case e: ManagementApiException if ifExists && e.notFound() => Seq.empty
    }
  }
}

private[spark] case class AlterPrincipalCommand(
    name: String,
    toSet: Map[String, String],
    toUnset: Set[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.alterPrincipalProperties(
      name, ManagementCommands.toJavaMap(toSet), toUnset.toSet.asJava)
    Seq.empty
  }
}

private[spark] case class ResetPrincipalCommand(
    name: String,
    rotate: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("principal", nullable = false),
    ManagementCommands.attr("client_id"),
    ManagementCommands.attr("client_secret"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val credentials = client.resetPrincipal(name, rotate)
    Seq(Row(credentials.name(), credentials.clientId(), credentials.clientSecret()))
  }
}

// ---------------------------------------------------------------------- DDL：角色

private[spark] case class CreatePrincipalRoleCommand(
    name: String,
    properties: Map[String, String],
    ifNotExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.createPrincipalRole(name, ManagementCommands.toJavaMap(properties))
      Seq.empty
    } catch {
      case e: ManagementApiException if ifNotExists && e.alreadyExists() => Seq.empty
    }
  }
}

private[spark] case class DropPrincipalRoleCommand(
    name: String,
    ifExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.dropPrincipalRole(name)
      Seq.empty
    } catch {
      case e: ManagementApiException if ifExists && e.notFound() => Seq.empty
    }
  }
}

private[spark] case class AlterPrincipalRoleCommand(
    name: String,
    toSet: Map[String, String],
    toUnset: Set[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.alterPrincipalRoleProperties(
      name, ManagementCommands.toJavaMap(toSet), toUnset.toSet.asJava)
    Seq.empty
  }
}

private[spark] case class CreateCatalogRoleCommand(
    catalog: String,
    name: String,
    properties: Map[String, String],
    ifNotExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.createCatalogRole(catalog, name, ManagementCommands.toJavaMap(properties))
      Seq.empty
    } catch {
      case e: ManagementApiException if ifNotExists && e.alreadyExists() => Seq.empty
    }
  }
}

private[spark] case class DropCatalogRoleCommand(
    catalog: String,
    name: String,
    ifExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.dropCatalogRole(catalog, name)
      Seq.empty
    } catch {
      case e: ManagementApiException if ifExists && e.notFound() => Seq.empty
    }
  }
}

private[spark] case class AlterCatalogRoleCommand(
    catalog: String,
    name: String,
    toSet: Map[String, String],
    toUnset: Set[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.alterCatalogRoleProperties(
      catalog, name, ManagementCommands.toJavaMap(toSet), toUnset.toSet.asJava)
    Seq.empty
  }
}

// ---------------------------------------------------------------------- DCL

private[spark] case class GrantPrincipalRoleCommand(
    principal: String,
    principalRole: String,
    revoke: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    if (revoke) {
      client.revokePrincipalRole(principal, principalRole)
    } else {
      client.grantPrincipalRole(principal, principalRole)
    }
    Seq.empty
  }
}

private[spark] case class GrantCatalogRoleCommand(
    principalRole: String,
    catalog: String,
    catalogRole: String,
    revoke: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    if (revoke) {
      client.revokeCatalogRole(principalRole, catalog, catalogRole)
    } else {
      client.grantCatalogRole(principalRole, catalog, catalogRole)
    }
    Seq.empty
  }
}

/**
 * 资源授权：一次语句可以带多项权限，展开成多条授权请求。
 *
 * <p>多条权限时按顺序逐条调用；如果中途失败，前面的已经生效。服务端没有为
 * 「批量授权」提供原子端点（规格里每条 grant 是一个请求），因此这里不做伪原子化，
 * 而是让失败直接抛出，由调用方按需回滚——SQL 层面保持与规格一致的行为。
 */
private[spark] case class GrantPrivilegesCommand(
    catalog: String,
    catalogRole: String,
    grants: Seq[ManagementApiClient.GrantInfo],
    revoke: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    grants.foreach { grant =>
      if (revoke) {
        client.revokeGrant(catalog, catalogRole, grant)
      } else {
        client.addGrant(catalog, catalogRole, grant)
      }
    }
    Seq.empty
  }
}

// ---------------------------------------------------------------------- SHOW

private[spark] case class ShowCatalogsCommand(client: ManagementApiClient)
  extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("name", nullable = false),
    ManagementCommands.attr("type"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.listCatalogs().asScala.map { catalog =>
      Row(catalog.name(), catalog.`type`())
    }.toSeq
  }
}

private[spark] case class ShowPrincipalsCommand(
    forPrincipalRole: Option[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = forPrincipalRole match {
    case Some(_) => Seq(ManagementCommands.attr("principal", nullable = false))
    case None => Seq(
      ManagementCommands.attr("name", nullable = false),
      ManagementCommands.attr("client_id"))
  }

  override def run(sparkSession: SparkSession): Seq[Row] = forPrincipalRole match {
    case Some(role) =>
      client.listPrincipalsOf(role).asScala.map(name => Row(name)).toSeq
    case None =>
      client.listPrincipals().asScala.map { principal =>
        Row(principal.name(), principal.clientId())
      }.toSeq
  }
}

private[spark] case class ShowPrincipalRolesCommand(
    forPrincipal: Option[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = forPrincipal match {
    case Some(_) => Seq(ManagementCommands.attr("principal_role", nullable = false))
    case None => Seq(
      ManagementCommands.attr("name", nullable = false),
      ManagementCommands.attr("federated"))
  }

  override def run(sparkSession: SparkSession): Seq[Row] = forPrincipal match {
    case Some(principal) =>
      client.listPrincipalRolesOf(principal).asScala.map(name => Row(name)).toSeq
    case None =>
      client.listPrincipalRoles().asScala.map { role =>
        Row(role.name(), Option(role.federated()).map(_.toString).orNull)
      }.toSeq
  }
}

private[spark] case class ShowCatalogRolesCommand(
    catalog: String,
    forPrincipalRole: Option[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = forPrincipalRole match {
    case Some(_) => Seq(ManagementCommands.attr("catalog_role", nullable = false))
    case None => Seq(
      ManagementCommands.attr("name", nullable = false),
      ManagementCommands.attr("catalog", nullable = false))
  }

  override def run(sparkSession: SparkSession): Seq[Row] = forPrincipalRole match {
    case Some(principalRole) =>
      client.listCatalogRolesOf(principalRole, catalog).asScala.map(name => Row(name)).toSeq
    case None =>
      client.listCatalogRoles(catalog).asScala.map { role =>
        Row(role.name(), catalog)
      }.toSeq
  }
}

/**
 * 列出某个 catalog role 持有的资源授权。
 *
 * <p>行与规格的 grant 一一对应；{@code resource} 列把多级命名空间与对象名拼成
 * 可读的完整名，同时保留 {@code namespace} / {@code object_name} 两列以便筛选。
 */
private[spark] case class ShowGrantsCommand(
    catalog: String,
    catalogRole: String,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("catalog", nullable = false),
    ManagementCommands.attr("catalog_role", nullable = false),
    ManagementCommands.attr("type", nullable = false),
    ManagementCommands.attr("resource"),
    ManagementCommands.attr("namespace"),
    ManagementCommands.attr("object_name"),
    ManagementCommands.attr("privilege", nullable = false))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.listGrants(catalog, catalogRole).asScala.map { grant =>
      val namespace = grant.namespace().asScala.toSeq
      val objectName = Option(grant.objectName())
      val resource = (namespace, objectName) match {
        case (Seq(), None) => catalog
        case (ns, None) => ns.mkString(".")
        case (Seq(), Some(name)) => name
        case (ns, Some(name)) => ManagementCommands.qualified(ns, name)
      }
      Row(catalog, catalogRole, grant.`type`(), resource,
        if (namespace.isEmpty) null else namespace.mkString("."),
        objectName.orNull,
        grant.privilege())
    }.toSeq
  }
}
