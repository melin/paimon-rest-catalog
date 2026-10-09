package io.github.melin.paimonrest.spark

import org.apache.spark.sql.SparkSession

import java.nio.file.Files

/**
 * 需要真实服务端的测试类共用的 SparkSession。
 *
 * <p><b>为什么必须共用而不是各建各的。</b>一个 JVM 只能有一个 SparkContext，
 * `SparkSession.builder().getOrCreate()` 在已有活动会话时会直接返回它，
 * 而 `spark.sql.extensions` 只在**建会话那一刻**生效、之后改不了。
 * 若每个测试类各自建会话，后建的类会静默拿到先建的那个——
 * 扩展其实没装上，用例却会以「语法错误」「catalog 不存在」之类的形式失败，
 * 排查起来要绕很远。因此这里是唯一的会话入口。
 *
 * <p>配置一次性配齐，覆盖两类被测对象：
 *
 * <ul>
 *   <li>{@code io.github.melin.paimonrest.spark.ManagementSparkExtensions} —— 本项目的管理语句扩展；
 *   <li>{@code org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions} 与
 *       {@code spark.sql.catalog.paimon} —— Paimon 自身的扩展与 REST catalog，
 *       供 `CREATE TABLE ... USING paimon` 一类的建表用例使用。
 * </ul>
 *
 * <p>两个扩展可以并存：本项目的扩展先用自己的解析器识别管理语句，
 * 识别不了就原样交回 Spark 原生解析器，与 Paimon 注入的规则不重叠。
 *
 * <p>目标地址取自系统属性，未配置时 {@link #isAvailable} 为 false，
 * 各测试类据此跳过（见 {@code scripts/e2e-spark-sql.sh}）。
 */
object LiveSparkSession {

    /** 管理 API 基址，例如 {@code http://127.0.0.1:18080/api/management/v1}。 */
    private val managementUrl = System.getProperty("e2e.management.url", "")

    /** 管理 API 在服务端上的固定前缀，用来反推 catalog API 的基址。 */
    private val managementPath = "/api/management/v1"

    /** 调用者令牌；测试类要直接读服务端原始响应时也用它。 */
    private[spark] val token = System.getProperty("e2e.management.token", "")

    /**
     * Paimon Rest Catalog 的基址。
     *
     * <p>默认从管理 API 基址去掉 {@value #managementPath} 得到——两者本来就挂在
     * 同一个服务端上，这样只需给一个地址。需要指向别处时用
     * {@code -De2e.paimon.url=} 覆盖。
     */
    def catalogUrl: String = {
        val explicit = System.getProperty("e2e.paimon.url", "")
        if (explicit.nonEmpty) {
            explicit
        } else if (managementUrl.endsWith(managementPath)) {
            managementUrl.substring(0, managementUrl.length - managementPath.length)
        } else {
            managementUrl
        }
    }

    /** 是否给了目标地址。没给就说明没打算跑端到端用例。 */
    def isAvailable: Boolean = managementUrl.nonEmpty

    /** Paimon 自己的扩展类名。 */
    private val PaimonExtensionClass = "org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions"

    /**
     * `spark.sql.extensions` 的取值：两个扩展都要装。
     *
     * <p>顺序上本项目的扩展在前——它先用自己的解析器识别管理语句，
     * 识别不了就原样交回 Spark 原生解析器，与 Paimon 注入的规则不重叠。
     *
     * <p>Paimon 的扩展**不能省**：`SparkCatalog.initialize` 会检查
     * `spark.sql.extensions` 里有没有它，缺了就直接抛异常、连建库都做不了
     * （提示语是 "When using Paimon, it is necessary to configure `spark.sql.extensions`…"）。
     */
    private val extensions = PaimonRestManagement.EXTENSION_CLASS + "," + PaimonExtensionClass

    /**
     * 测试用的 catalog 标识，既是 `spark.sql.catalog.<name>` 的后缀，
     * 也是服务端上的 prefix（服务端默认 prefix 由 `paimon.rest.default-prefix` 决定，默认同名）。
     *
     * <p>SQL 里要用全限定名 `paimon.<database>.<table>` 才能落到这个 catalog 上：
     * `USE <database>` 只切换当前库、不切换 catalog，漏写限定名会把表建到
     * `spark_catalog` 里，之后 `INSERT` 会以「paimon is not a valid Spark SQL Data Source」
     * 收场——报错信息与真正的原因相去甚远。
     */
    val catalog: String = "paimon"

    /** 会话按进程共享，只建一次。仅在有目标地址时才被触碰。 */
    lazy val instance: SparkSession = {
        // 同上：JDK 24+ 下 Spark 建会话必失败，提前换成一句能照做的提示
        SparkJdkRequirement.requireHadoopCompatibleJdk()
        val warehouse = Files.createTempDirectory("paimon-rest-live-warehouse")
        val session = SparkSession.builder()
            .master("local[1]")
            .appName("paimon-rest-live")
            .config("spark.ui.enabled", "false")
            .config("spark.driver.host", "127.0.0.1")
            .config("spark.driver.bindAddress", "127.0.0.1")
            .config("spark.sql.shuffle.partitions", "1")
            .config("spark.sql.warehouse.dir", warehouse.toString)
            .config("spark.sql.extensions", LiveSparkSession.extensions)
            // Paimon 自己的扩展：建表/读写等 Paimon SQL 由它落地
            .config("spark.sql.catalog." + catalog, "org.apache.paimon.spark.SparkCatalog")
            .config("spark.sql.catalog." + catalog + ".metastore", "rest")
            .config("spark.sql.catalog." + catalog + ".uri", catalogUrl)
            // warehouse 传的是 catalog 的 prefix：服务端 GET /v1/config 会用仓库名换回 prefix，
            // 客户端后续路径都以它开头。这里两侧同名
            .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
            .config("spark.sql.catalog." + catalog + ".token.provider", "bear")
            .config("spark.sql.catalog." + catalog + ".token", token)
            .config(PaimonRestManagement.MANAGEMENT_URL, managementUrl)
            .config(PaimonRestManagement.TOKEN, token)
            .getOrCreate()

        // 守住会话复用这个坑：若本进程里已经有别的 SparkSession（例如用桩服务端的
        // ManagementSqlExecutionTests），getOrCreate 会**直接返回那一个**，
        // 我们设的 extensions 与管理 API 地址都不会生效。此时用例仍会跑，
        // 但会以「catalog 不存在」「客户端 404」这类无关报错收场，很难定位。
        // 与其静默跑错，不如立刻说清楚。
        val effective = session.conf.get("spark.sql.extensions", "")
        require(effective.contains(PaimonExtensionClass)
                && effective.contains(PaimonRestManagement.EXTENSION_CLASS),
            s"""拿到的 SparkSession 不是本 holder 创建的那个，扩展配置为 "$effective"。
               |一个 JVM 只能有一个 SparkContext，而 spark.sql.extensions 只在建会话时生效。
               |请只运行需要真实服务端的测试类，例如：
               |  mvn -o -pl paimon-rest-spark test -Dtest=ManagementSqlLiveServerTests,PaimonTableDdlTests,SparkSqlDocExamplesTests
               |或直接用 scripts/e2e-spark-sql.sh。""".stripMargin)

        // 提前初始化 sessionState：解析器在此时装配，装配出问题会立刻暴露，
        // 而不是拖到第一条语句才报错。
        val _ = session.sessionState.sqlParser
        session
    }
}
