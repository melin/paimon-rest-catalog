package com.example.paimonrest.spark

import com.example.paimonrest.spark.client.ManagementApiClient
import com.example.paimonrest.spark.client.ManagementApiClient.GrantInfo
import com.example.paimonrest.spark.parser.ManagementSqlBaseVisitor
import com.example.paimonrest.spark.parser.ManagementSqlParser._
import org.antlr.v4.runtime.tree.ParseTree
import org.apache.spark.sql.execution.command.RunnableCommand

import java.util.Locale
import scala.collection.JavaConverters._

/**
 * 把管理语句的语法树转换成 Spark 的逻辑计划。
 *
 * <p>每个 `visit*` 方法返回一个 {@link RunnableCommand}，由 Spark 在分析阶段执行；
 * 命令内部再调用 REST 管理 API。
 *
 * <p><b>解析期只做「形状」校验</b>（例如对象级授权必须写成 `namespace.对象名`）。
 * 权限取值是否合法、权限与资源层级是否匹配，都交给服务端判定：服务端已有从规格生成的
 * 64 项权限表，SQL 层再抄一份只会多一处会漂移的副本。
 *
 * <p><b>访问器的两种形态。</b>ANTLR 对带标签的元素（`name=identifier`）生成公共**字段**，
 * 对未加标签的元素生成**方法**。因此 `ctx.name` 不带括号，而 `ctx.propertyList()` 带括号——
 * 写反了会被 Scala 报成「该类型不接受参数」。
 */
private[spark] class ManagementAstBuilder(client: ManagementApiClient)
  extends ManagementSqlBaseVisitor[AnyRef] {

  override def visitSingleStatement(ctx: SingleStatementContext): AnyRef =
    ctx.statement().accept(this)

  /**
   * 分派到具体的语句分支。
   *
   * <p>不能交给 `visitChildren`：其默认聚合规则是「取最后一个子节点的结果」，
   * 而 `singleStatement` 的最后一个子节点是 EOF 终结符，结果会是 null。
   * `statement` 的每个可选分支恰好对应一个子节点，因此直接分派给该子节点。
   */
  override def visitStatement(ctx: StatementContext): AnyRef =
    ctx.getChild(0).asInstanceOf[ParseTree].accept(this)

  // ------------------------------------------------------------------ DDL：主体

  override def visitCreatePrincipal(ctx: CreatePrincipalContext): AnyRef =
    CreatePrincipalCommand(
      identifier(ctx.name),
      properties(ctx.propertyList()),
      ifNotExists = ctx.EXISTS() != null,
      client = client)

  override def visitDropPrincipal(ctx: DropPrincipalContext): AnyRef =
    DropPrincipalCommand(identifier(ctx.name), ifExists = ctx.EXISTS() != null, client = client)

  override def visitAlterPrincipal(ctx: AlterPrincipalContext): AnyRef = {
    val name = identifier(ctx.name)
    ctx.setPropertyList() match {
      case null =>
        AlterPrincipalCommand(name, Map.empty, unsetKeys(ctx.unsetPropertyList()), client)
      case set =>
        AlterPrincipalCommand(name, setProperties(set), Set.empty, client)
    }
  }

  override def visitResetPrincipal(ctx: ResetPrincipalContext): AnyRef =
    ResetPrincipalCommand(identifier(ctx.name), rotate = ctx.ROTATE() != null, client = client)

  // ------------------------------------------------------------------ DDL：角色

  override def visitCreatePrincipalRole(ctx: CreatePrincipalRoleContext): AnyRef =
    CreatePrincipalRoleCommand(
      identifier(ctx.name),
      properties(ctx.propertyList()),
      ifNotExists = ctx.EXISTS() != null,
      client = client)

  override def visitDropPrincipalRole(ctx: DropPrincipalRoleContext): AnyRef =
    DropPrincipalRoleCommand(identifier(ctx.name), ifExists = ctx.EXISTS() != null, client = client)

  override def visitAlterPrincipalRole(ctx: AlterPrincipalRoleContext): AnyRef = {
    val name = identifier(ctx.name)
    ctx.setPropertyList() match {
      case null =>
        AlterPrincipalRoleCommand(name, Map.empty, unsetKeys(ctx.unsetPropertyList()), client)
      case set =>
        AlterPrincipalRoleCommand(name, setProperties(set), Set.empty, client)
    }
  }

  override def visitCreateCatalogRole(ctx: CreateCatalogRoleContext): AnyRef =
    CreateCatalogRoleCommand(
      identifier(ctx.catalog),
      identifier(ctx.name),
      properties(ctx.propertyList()),
      ifNotExists = ctx.EXISTS() != null,
      client = client)

  override def visitDropCatalogRole(ctx: DropCatalogRoleContext): AnyRef =
    DropCatalogRoleCommand(identifier(ctx.catalog), identifier(ctx.name),
      ifExists = ctx.EXISTS() != null, client = client)

  override def visitAlterCatalogRole(ctx: AlterCatalogRoleContext): AnyRef = {
    val catalog = identifier(ctx.catalog)
    val name = identifier(ctx.name)
    ctx.setPropertyList() match {
      case null =>
        AlterCatalogRoleCommand(catalog, name, Map.empty, unsetKeys(ctx.unsetPropertyList()), client)
      case set =>
        AlterCatalogRoleCommand(catalog, name, setProperties(set), Set.empty, client)
    }
  }

  // ------------------------------------------------------------------ DCL：角色装配

  override def visitGrantPrincipalRole(ctx: GrantPrincipalRoleContext): AnyRef =
    GrantPrincipalRoleCommand(identifier(ctx.principal), identifier(ctx.role),
      revoke = false, client = client)

  override def visitRevokePrincipalRole(ctx: RevokePrincipalRoleContext): AnyRef =
    GrantPrincipalRoleCommand(identifier(ctx.principal), identifier(ctx.role),
      revoke = true, client = client)

  override def visitGrantCatalogRole(ctx: GrantCatalogRoleContext): AnyRef =
    GrantCatalogRoleCommand(identifier(ctx.principalRole), identifier(ctx.catalog),
      identifier(ctx.role), revoke = false, client = client)

  override def visitRevokeCatalogRole(ctx: RevokeCatalogRoleContext): AnyRef =
    GrantCatalogRoleCommand(identifier(ctx.principalRole), identifier(ctx.catalog),
      identifier(ctx.role), revoke = true, client = client)

  // ------------------------------------------------------------------ DCL：资源授权

  override def visitGrantPrivileges(ctx: GrantPrivilegesContext): RunnableCommand =
    privilegeCommand(ctx.privilegeList(), ctx.grantResource(), identifier(ctx.role), revoke = false)

  override def visitRevokePrivileges(ctx: RevokePrivilegesContext): RunnableCommand =
    privilegeCommand(ctx.privilegeList(), ctx.grantResource(), identifier(ctx.role), revoke = true)

  private def privilegeCommand(privileges: PrivilegeListContext, resource: GrantResourceContext,
                               catalogRole: String, revoke: Boolean): RunnableCommand = {
    val target = grantTarget(resource)
    val grants = privileges.privilege().asScala.map { privilege =>
      target.objectName match {
        case Some(objectName) =>
          GrantInfo.onObject(target.resourceType, target.namespace.asJava, objectName,
            privilegeText(privilege))
        case None if target.resourceType == "namespace" =>
          GrantInfo.onNamespace(target.namespace.asJava, privilegeText(privilege))
        case None =>
          GrantInfo.onCatalog(privilegeText(privilege))
      }
    }
    GrantPrivilegesCommand(target.catalog, catalogRole, grants.toSeq, revoke, client)
  }

  /** 资源描述解出的目标：catalog、类型、命名空间、对象名（catalog 与 namespace 级为 None）。 */
  private case class GrantTarget(catalog: String, resourceType: String,
                                 namespace: Seq[String], objectName: Option[String])

  private def grantTarget(ctx: GrantResourceContext): GrantTarget =
    Option(ctx.catalogResource()).map { r =>
      GrantTarget(identifier(r.catalog), "catalog", Seq.empty, None)
    }.orElse(Option(ctx.namespaceResource()).map { r =>
      GrantTarget(identifier(r.catalog), "namespace", parts(r.namespace), None)
    }).orElse(Option(ctx.tableResource()).map { r =>
      objectTarget(identifier(r.catalog), "table", r.objectName)
    }).orElse(Option(ctx.viewResource()).map { r =>
      objectTarget(identifier(r.catalog), "view", r.objectName)
    }).orElse(Option(ctx.policyResource()).map { r =>
      objectTarget(identifier(r.catalog), "policy", r.objectName)
    }).orElse(Option(ctx.semanticModelResource()).map { r =>
      objectTarget(identifier(r.catalog), "semantic-model", r.objectName)
    }).getOrElse(throw new ManagementSqlException(
      "unsupported grant resource: " + ctx.getText))

  /**
   * 对象级资源必须写成「命名空间.对象名」。
   *
   * <p>命名空间是授权作用域的一部分（规格要求对象级授权带 `namespace`），
   * 单段名字无法确定作用域。这里不做「猜默认命名空间」的处理，直接报错。
   */
  private def objectTarget(catalog: String, resourceType: String,
                           multipart: MultipartIdentifierContext): GrantTarget = {
    val names = parts(multipart)
    if (names.size < 2) {
      throw new ManagementSqlException(
        s"$resourceType grants require a qualified name of the form <namespace>.<name>, " +
          s"got '${names.mkString(".")}'")
    }
    GrantTarget(catalog, resourceType, names.dropRight(1), Some(names.last))
  }

  private def privilegeText(ctx: PrivilegeContext): String =
    identifier(ctx.identifier()).toUpperCase(Locale.ROOT)

  // ------------------------------------------------------------------ SHOW

  override def visitShowCatalogs(ctx: ShowCatalogsContext): AnyRef = ShowCatalogsCommand(client)

  override def visitShowPrincipals(ctx: ShowPrincipalsContext): AnyRef =
    ShowPrincipalsCommand(Option(ctx.principalRole).map(identifier), client)

  override def visitShowPrincipalRoles(ctx: ShowPrincipalRolesContext): AnyRef =
    ShowPrincipalRolesCommand(Option(ctx.principal).map(identifier), client)

  override def visitShowCatalogRoles(ctx: ShowCatalogRolesContext): AnyRef =
    ShowCatalogRolesCommand(identifier(ctx.catalog), Option(ctx.principalRole).map(identifier),
      client)

  override def visitShowGrants(ctx: ShowGrantsContext): AnyRef =
    ShowGrantsCommand(identifier(ctx.catalog), identifier(ctx.role), client)

  // ------------------------------------------------------------------ 公共工具

  private def identifier(ctx: IdentifierContext): String = {
    val quoted = ctx.BACKQUOTED_IDENTIFIER()
    if (quoted != null) {
      val raw = quoted.getText
      // 反引号内的 `` 表示一个反引号
      raw.substring(1, raw.length - 1).replace("``", "`")
    } else {
      ctx.IDENTIFIER().getText
    }
  }

  private def parts(ctx: MultipartIdentifierContext): Seq[String] =
    ctx.identifier().asScala.map(identifier).toSeq

  private def properties(ctx: PropertyListContext): Map[String, String] =
    if (ctx == null) Map.empty else toMap(ctx.property().asScala.toSeq)

  private def setProperties(ctx: SetPropertyListContext): Map[String, String] =
    toMap(ctx.property().asScala.toSeq)

  private def unsetKeys(ctx: UnsetPropertyListContext): Set[String] =
    ctx.propertyKey().asScala.map(propertyKey).toSet

  private def toMap(items: Seq[PropertyContext]): Map[String, String] =
    items.map { item =>
      // 只写键不写值等价于设置成空串，属性是否合法由服务端校验
      val value = Option(item.value).map(propertyValue).getOrElse("")
      propertyKey(item.key) -> value
    }.toMap

  private def propertyKey(ctx: PropertyKeyContext): String = {
    val literal = ctx.STRING()
    if (literal != null) stringValue(literal.getText) else identifier(ctx.identifier())
  }

  private def propertyValue(ctx: PropertyValueContext): String = {
    val literal = ctx.STRING()
    if (literal != null) {
      stringValue(literal.getText)
    } else if (ctx.number() != null) {
      ctx.number().getText
    } else {
      identifier(ctx.identifier())
    }
  }

  /** 去掉字符串字面量的引号，并还原成对引号。 */
  private def stringValue(raw: String): String = {
    val quote = raw.charAt(0)
    raw.substring(1, raw.length - 1).replace(s"$quote$quote", quote.toString)
  }
}
