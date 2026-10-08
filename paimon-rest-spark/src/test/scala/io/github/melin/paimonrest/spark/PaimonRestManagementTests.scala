package io.github.melin.paimonrest.spark

import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

/**
 * 配置解析测试。
 *
 * <p>覆盖的是「配置写错不应该让会话或语句失败」这条容错约定：
 * 超时写成非数字、0 或负数时退化为默认值。
 */
class PaimonRestManagementTests {

  private val default = PaimonRestManagement.defaultTimeoutSeconds.toLong

  @Test
  def validTimeoutIsHonoured(): Unit = {
    assertEquals(5L, PaimonRestManagement.parseTimeout("5"))
    assertEquals(90L, PaimonRestManagement.parseTimeout(" 90 "))
  }

  @Test
  def invalidOrNonPositiveTimeoutFallsBackToTheDefault(): Unit = {
    assertEquals(default, PaimonRestManagement.parseTimeout(""))
    assertEquals(default, PaimonRestManagement.parseTimeout(null))
    assertEquals(default, PaimonRestManagement.parseTimeout("0"))
    assertEquals(default, PaimonRestManagement.parseTimeout("-3"))
    assertEquals(default, PaimonRestManagement.parseTimeout("30s"))
    assertEquals(default, PaimonRestManagement.parseTimeout("abc"))
  }

  @Test
  def configurationKeysAreNamespacedUnderSpark(): Unit = {
    // SparkConf 只保留 spark. 前缀的键，配置键不以此开头就会被静默丢弃
    assertTrue(PaimonRestManagement.MANAGEMENT_URL.startsWith("spark."))
    assertTrue(PaimonRestManagement.TOKEN.startsWith("spark."))
    assertTrue(PaimonRestManagement.TIMEOUT_SECONDS.startsWith("spark."))
  }

  @Test
  def declaredKeywordsCoverEveryStatementCategory(): Unit = {
    val keywords = PaimonRestManagement.STATEMENT_KEYWORDS.toSet
    List("CREATE", "DROP", "ALTER", "RESET", "ROTATE", "GRANT", "REVOKE", "SHOW")
      .foreach(keyword => assertTrue(keywords.contains(keyword), "缺少关键字: " + keyword))
  }
}
