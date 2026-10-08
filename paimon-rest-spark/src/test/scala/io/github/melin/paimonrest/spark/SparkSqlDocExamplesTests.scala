package io.github.melin.paimonrest.spark

import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 * 把参考文档里的示例当作验收用例执行。
 *
 * <p><b>为什么值得单独一层。</b>`docs/spark-sql-reference.md` 第 11 节给出的是一段
 * 「按顺序执行即可跑通全部 21 条语句」的脚本。文档里的 SQL 有两种坏法：
 * 写错语法（解析期就会失败），以及顺序不成立（例如先 `DROP` 再 `REVOKE`，
 * 或者少了 `GRANT` 却断言 `SHOW` 有结果）。前者靠读稿难以发现，
 * 后者只有真跑一遍才暴露。因此这里把那段示例从 Markdown 里**抽出来执行**，
 * 让文档与代码同生共死：改坏了示例，构建会失败。
 *
 * <p>执行对象是**真实服务端**，与 {@link ManagementSqlLiveServerTests} 同一层：
 * 桩服务端不会校验「撤销后授权是否真的少了」，也就验不出示例的顺序是否正确。
 * 默认跳过，需要显式指向服务端（`scripts/e2e-spark-sql.sh` 会自动带上）。
 *
 * <p>会话复用 {@link ManagementSqlLiveServerTests} 的那一个：一个 JVM 只能有一个
 * `SparkContext`，同一个会话对象既保证配置一致，也避免建会话的重复开销。
 */
class SparkSqlDocExamplesTests {

  private val url = Option(System.getProperty("e2e.management.url")).filter(_.nonEmpty)

  @BeforeEach
  def requireLiveServer(): Unit =
    Assumptions.assumeTrue(url.isDefined,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")

  private def session: SparkSession = ManagementSqlLiveServerTests.session

  /** 文档路径：相对模块目录解析，因此从仓库根或模块目录运行都能找到。 */
  private def docPath: Path = {
    val candidates = Seq(
      Paths.get("docs/spark-sql-reference.md"),
      Paths.get("../docs/spark-sql-reference.md"))
    candidates.find(Files.exists(_)).getOrElse(
      throw new IllegalStateException(
        "找不到 docs/spark-sql-reference.md，当前目录: " + Paths.get("").toAbsolutePath))
  }

  /**
   * 抽出示例块并切成单条语句。
   *
   * <p>切分依据是「每行一条语句、以分号结尾」这一约定，而不是通用 SQL 分词：
   * 示例本身就是按这个约定写的，而通用分词会引入字符串字面量里分号的歧义，
   * 反而更难保证正确。
   */
  private def statements(): Seq[String] = {
    val markdown = new String(Files.readAllBytes(docPath), StandardCharsets.UTF_8)
    val begin = markdown.indexOf("<!-- doc-example:begin -->")
    val end = markdown.indexOf("<!-- doc-example:end -->")
    assertTrue(begin >= 0 && end > begin,
      "文档里应保留 doc-example:begin / doc-example:end 标记，校验脚本依赖它们")
    val block = markdown.substring(begin, end)
    val sql = block.linesIterator
      .map(_.trim)
      .filterNot(_.isEmpty)
      .filterNot(_.startsWith("```"))
      .filterNot(_.startsWith("<!--"))
      .filterNot(_.startsWith("--")) // 示例块里的分节注释
      .mkString(" ")
    sql.split(';').map(_.trim).filter(_.nonEmpty).toSeq
  }

  @Test
  def everyStatementInTheReferenceExampleExecutesAgainstTheRealServer(): Unit = {
    val all = statements()
    assertTrue(all.length >= 21,
      s"示例块应至少覆盖 21 条语句，实际 ${all.length} 条")

    // 逐条执行；记下每条的结果，供下面按语义断言
    val executed = all.map { sql =>
      val rows = session.sql(sql).collect()
      (sql, rows)
    }

    // ① 主体：RESET 与 ROTATE 各自签发一份新的明文密钥
    val reset = executed.collectFirst { case (sql, rows) if sql.startsWith("RESET PRINCIPAL") => rows }
    val rotate = executed.collectFirst { case (sql, rows) if sql.startsWith("ROTATE PRINCIPAL") => rows }
    assertEquals(1, reset.get.length, "RESET PRINCIPAL 应返回一行凭据")
    assertEquals(1, rotate.get.length, "ROTATE PRINCIPAL 应返回一行凭据")
    assertNotNull(reset.get(0).getString(2), "RESET 应返回明文密钥")
    assertNotEquals(reset.get(0).getString(2), rotate.get(0).getString(2),
      "轮换后密钥应与重置时不同")

    // ④ 资源授权：撤销前 4 条（catalog 1 + namespace 1 + table 2），撤销后 3 条
    val grants = executed.collect {
      case (sql, rows) if sql.startsWith("SHOW GRANTS FOR CATALOG ROLE doc_demo_reader") => rows
    }
    assertEquals(2, grants.length, "示例里应有两次 SHOW GRANTS：撤销前后各一次")
    assertEquals(4, grants.head.length,
      "撤销前应有 4 条授权：CATALOG_READ_PROPERTIES、NAMESPACE_LIST、TABLE_READ_DATA、TABLE_LIST")
    assertEquals(3, grants.last.length, "撤销 TABLE_LIST 后应剩 3 条")
    val privileges = (rows: Array[Row]) => rows.map(_.getString(6)).toSet
    assertTrue(privileges(grants.head).contains("TABLE_LIST"), "撤销前应含 TABLE_LIST")
    assertFalse(privileges(grants.last).contains("TABLE_LIST"), "撤销后不应再有 TABLE_LIST")
    assertTrue(privileges(grants.last).contains("TABLE_READ_DATA"),
      "撤销一项权限不应影响同一资源上的其它权限")

    // resource 列的拼法：catalog 级写 catalog 名，namespace 级写路径，表级写全名
    val byPrivilege = grants.head.map(row => row.getString(6) -> row).toMap
    assertEquals("paimon", byPrivilege("CATALOG_READ_PROPERTIES").getString(3))
    assertEquals("default", byPrivilege("NAMESPACE_LIST").getString(3))
    assertEquals("default.orders", byPrivilege("TABLE_READ_DATA").getString(3))

    // ⑤ 清理：示例最后一条 SHOW CATALOGS 应仍能看到预置的 catalog
    val catalogs = executed.last
    assertTrue(catalogs._1.startsWith("SHOW CATALOGS"), "示例最后一条应为 SHOW CATALOGS")
    assertTrue(catalogs._2.exists(_.getString(0) == "paimon"),
      "SHOW CATALOGS 应包含预置的 paimon")
  }
}
