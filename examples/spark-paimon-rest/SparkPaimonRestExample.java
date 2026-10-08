package com.example.paimonrest.examples;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * Spark 通过 Paimon REST Catalog 建表的最小示例。
 *
 * <p>与 {@code PaimonTableDdlTests} 的区别：那是断言用的测试，这是给人读的示例——
 * 每一步都打印出来，包括**直接读服务端 API 看到的元数据**。这一点是刻意的：
 * Spark 侧 {@code SHOW TABLES} 只能证明「客户端认为建好了」，
 * 而示例要展示的是「元数据确实进了服务端」，两者不是同一件事。
 *
 * <p>用法（不依赖外部 Spark 发行版，classpath 由本仓库的 Maven 依赖提供）：
 *
 * <pre>
 *   ./run-example.sh                      # 自动起服务端、跑示例、清理
 *   ./run-example.sh --keep-server        # 保留服务端，便于手工接着试
 * </pre>
 *
 * <p>也可以把 {@code spark-defaults.conf} 的内容套进自己的 Spark 发行版，
 * 用 {@code spark-sql} 手工执行同样的 SQL。
 */
public final class SparkPaimonRestExample {

    /** catalog 名。同时也是服务端的 prefix，两者必须一致。 */
    private static final String CATALOG = "paimon";

    public static void main(String[] args) throws Exception {
        String uri = prop("paimon.uri", "http://127.0.0.1:8080");
        String token = prop("paimon.token", "root");
        String database = prop("paimon.database", "demo");

        SparkSession spark = buildSession(uri, token);
        try {
            System.out.println();
            System.out.println("============================================================");
            System.out.println(" Spark → Paimon REST Catalog 示例");
            System.out.println(" 服务端：" + uri);
            System.out.println("============================================================");

            createDatabase(spark, database);
            createPartitionedTable(spark, database);
            createPrimaryKeyTable(spark, database);
            showTables(spark, database);
            System.out.println();
            System.out.println("---- DESCRIBE " + CATALOG + "." + database + ".orders ----");
            describe(spark, database, "orders");
            System.out.println();
            System.out.println("---- SHOW CREATE TABLE ----");
            showCreateTable(spark, database, "dim_user");
            inspectServerSide(uri, token, database);
            summary();
        } finally {
            spark.stop();
        }
    }

    // ------------------------------------------------------------------ 会话

    private static SparkSession buildSession(String uri, String token) throws Exception {
        System.out.println("[1/6] 建立 Spark 会话（local 模式，catalog 指向服务端）");
        // Spark 自带的内置 Hive 支持（spark_catalog）会在工作目录下创建
        // metastore_db/ 与 derby.log。示例只用 paimon catalog，用不到它们，
        // 把落点指到临时目录，免得在仓库里留下一堆散落文件。
        Path scratch = Files.createTempDirectory("spark-paimon-example");
        return SparkSession.builder()
                .master("local[1]")
                .appName("spark-paimon-rest-example")
                .config("spark.ui.enabled", "false")
                .config("spark.driver.host", "127.0.0.1")
                .config("spark.driver.bindAddress", "127.0.0.1")
                .config("spark.sql.shuffle.partitions", "1")
                .config("spark.sql.warehouse.dir", scratch.toString())
                // 两个扩展都必须装。
                //
                // Paimon 的 SparkCatalog 在初始化时会检查
                // PaimonSparkSessionExtensions 是否已注册，缺了它连建库都做不了，
                // 报错是 "Paimon Spark extensions are not enabled"。
                // 本项目的扩展放在前面：它先识别管理语句，识别不了就原样交回
                // Spark 原生解析器，与 Paimon 注入的规则不重叠。
                .config("spark.sql.extensions",
                        "com.example.paimonrest.spark.ManagementSparkExtensions,"
                                + "org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions")
                .config("spark.sql.catalog." + CATALOG, "org.apache.paimon.spark.SparkCatalog")
                .config("spark.sql.catalog." + CATALOG + ".metastore", "rest")
                .config("spark.sql.catalog." + CATALOG + ".uri", uri)
                // 这里填的是 catalog 的 prefix，**不是文件路径**。
                // 客户端启动时用 GET /v1/config?warehouse=paimon 拿回真正的 prefix，
                // 之后所有请求路径都以它开头。服务端的 default-prefix 也叫 paimon。
                .config("spark.sql.catalog." + CATALOG + ".warehouse", CATALOG)
                .config("spark.sql.catalog." + CATALOG + ".token.provider", "bear")
                .config("spark.sql.catalog." + CATALOG + ".token", token)
                .getOrCreate();
    }

    // ------------------------------------------------------------------ 建库

    private static void createDatabase(SparkSession spark, String database) {
        System.out.println();
        System.out.println("[2/6] 建库 " + CATALOG + "." + database);
        // 库名必须带 catalog 前缀。USE <库> 只切换当前库、不切换 catalog，
        // 漏掉前缀会把对象建到 spark_catalog 里。
        run(spark, "CREATE DATABASE IF NOT EXISTS " + CATALOG + "." + database);
        System.out.println("      → " + CATALOG + "." + database + " 就绪");
    }

    // ------------------------------------------------------------------ 建表

    private static void createPartitionedTable(SparkSession spark, String database) {
        System.out.println();
        System.out.println("[3/6] 建分区追加表 orders");
        run(spark, "CREATE TABLE IF NOT EXISTS " + CATALOG + "." + database + ".orders (\n"
                + "  id     BIGINT        COMMENT '订单号',\n"
                + "  amount DECIMAL(10,2),\n"
                + "  dt     STRING        COMMENT '分区日'\n"
                + ") USING paimon\n"
                + "PARTITIONED BY (dt)\n"
                + "COMMENT '订单表'\n"
                // 追加表用了固定 bucket 就必须给分桶键，否则建表被拒：
                // "You should define a 'bucket-key' for bucketed append mode"
                + "TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id')");
        System.out.println("      → 列注释、表注释、分区键、表选项详见第 5 步的服务端视角");
    }

    private static void createPrimaryKeyTable(SparkSession spark, String database) {
        System.out.println();
        System.out.println("[4/6] 建主键表 dim_user");
        // 主键只能写成表选项。列定义里的 `PRIMARY KEY (id) NOT ENFORCED`
        // 在 Spark 3.5 的解析器上会直接抛 ParseException，请求根本到不了服务端。
        run(spark, "CREATE TABLE IF NOT EXISTS " + CATALOG + "." + database + ".dim_user (\n"
                + "  user_id BIGINT COMMENT '用户号',\n"
                + "  region  STRING,\n"
                + "  name    STRING\n"
                + ") USING paimon\n"
                + "PARTITIONED BY (region)\n"
                + "TBLPROPERTIES ('primary-key' = 'user_id,region')");
        System.out.println("      → 主键会被服务端归一化进 schema 的 primaryKeys，并从 options 里移除");
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 下面几处都用 collectAsList() 而不是 collect()，这不是风格偏好。
     *
     * <p>Dataset 是 Scala 类型，collect() 的返回类型是 Scala 的 Array[T]，
     * 而 Scala 为泛型数组生成的签名带不出类型参数，javac 读到的就是裸 Object，
     * 于是 {@code for (Row row : ds.collect())} 报
     * 「for-each 不适用于表达式类型，找到: Object」——看起来像 Spark 的类没加载对，
     * 实际是 Scala 泛型数组与 Java 的差异。
     * collectAsList() 返回标准的 java.util.List[T]，没有这个问题。
     *
     * <p>用 Scala 写调用方不会遇到；这是纯 Java 调用方才会踩的坑。
     */
    private static void showTables(SparkSession spark, String database) {
        System.out.println();
        System.out.println("[5/6] Spark 侧看到的对象");
        System.out.println("---- SHOW TABLES IN " + CATALOG + "." + database + " ----");
        for (Row row : spark.sql("SHOW TABLES IN " + CATALOG + "." + database).collectAsList()) {
            System.out.println("  " + row.getString(1));
        }
    }

    private static void describe(SparkSession spark, String database, String table) {
        for (Row row : spark.sql("DESCRIBE " + CATALOG + "." + database + "." + table).collectAsList()) {
            String col = row.getString(0);
            // 列定义段之后是 "# Partition Information" 段，分区列会在那里
            // 再出现一次。遇到段落标记就停，免得同一个列打印两遍。
            if (col == null || col.isEmpty() || col.startsWith("#")) {
                break;
            }
            System.out.printf("  %-10s %-16s %s%n", col, row.getString(1), row.getString(2));
        }
    }

    private static void showCreateTable(SparkSession spark, String database, String table) {
        for (Row row : spark.sql("SHOW CREATE TABLE " + CATALOG + "." + database + "." + table).collectAsList()) {
            System.out.println(row.getString(0));
        }
    }

    // -------------------------------------------------------------- 服务端视角

    /**
     * 直接调服务端 API 读表元数据。
     *
     * <p>这一步是示例的重点：前面几步看到的都是「客户端认为的结果」，
     * 只有这里能证明元数据真的落到了服务端。只做客户端断言的话，
     * 一个「建表返回成功但什么都没存」的服务端也能让它们全部通过。
     */
    private static void inspectServerSide(String uri, String token, String database) throws Exception {
        System.out.println();
        System.out.println("[6/6] 服务端侧看到的元数据（直接调 REST API）");
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String table : new String[]{"orders", "dim_user"}) {
                String url = uri + "/v1/" + CATALOG + "/databases/" + database + "/tables/" + table;
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println();
                System.out.println("---- GET " + "/v1/" + CATALOG + "/databases/" + database
                        + "/tables/" + table + "  → HTTP " + response.statusCode() + " ----");
                System.out.println(pretty(response.body()));
            }
        }
    }

    /**
     * 一个够用的 JSON 缩进器。
     *
     * <p>不引 Jackson 是有意的：示例的价值在于「少依赖、照着能改」，
     * 为了一段打印引入一个 JSON 库会让人以为它是必需的。
     * 这里只处理结构字符，字符串内部原样保留（够这段输出用）。
     */
    private static String pretty(String json) {
        StringBuilder out = new StringBuilder(json.length() * 2);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    char close = c == '{' ? '}' : ']';
                    // 空的 {} 与 [] 直接写成一行。否则会输出成
                    // "primaryKeys":[\n\n] 这样带一个空行的三行结构。
                    if (i + 1 < json.length() && json.charAt(i + 1) == close) {
                        out.append(c).append(close);
                        i++;
                    } else {
                        out.append(c).append('\n').append("  ".repeat(++indent));
                    }
                }
                case '}', ']' -> out.append('\n').append("  ".repeat(--indent)).append(c);
                case ',' -> out.append(c).append('\n').append("  ".repeat(indent));
                default -> out.append(c);
            }
        }
        String text = out.toString();
        return text.length() > 1600 ? text.substring(0, 1600) + "\n  …（截断）" : text;
    }

    private static void summary() {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(" 完成。两个表都已建在服务端，元数据可在服务端库里查到。");
        System.out.println();
        System.out.println(" 注意：本示例只到「建表 + 查元数据」为止。");
        System.out.println(" INSERT 与 CTAS 目前不可用——服务端还没有托管 Paimon 的");
        System.out.println(" snapshot 元数据，写入路径会在提交快照时报");
        System.out.println(" 「Cannot get latest schema for table」而失败。");
        System.out.println("============================================================");
    }

    // ------------------------------------------------------------------ 辅助

    private static void run(SparkSession spark, String sql) {
        spark.sql(sql);
    }

    private static String prop(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private SparkPaimonRestExample() {
    }
}
