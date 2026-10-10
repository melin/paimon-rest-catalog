package io.github.melin.paimonrest.spark.example

import io.github.melin.paimonrest.spark.LiveSparkSession.{catalog, catalogUrl, managementUrl, token}
import io.github.melin.paimonrest.spark.{LiveSparkSession, PaimonRestManagement}
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.Test

class PaimonRestCatalogTest {

  def baseUrl: String = s"http://127.0.0.1:8080/api/management/v1"

  /*
    获取 token:
    curl -s -X POST http://127.0.0.1:8080/api/catalog/v1/oauth/tokens \
      -d grant_type=client_credentials \
      -d client_id="230698be-b91b-4262-b208-179cc530e54c" -d client_secret="nUmM_Y2nvcCLNxqBP7U-TM9uA3bENYFW1_Io2Cjumss" \
      -d scope=PRINCIPAL_ROLE:ALL | jq -r .access_token
   */
  val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJwYWltb24tcmVzdCIsInN1YiI6InJlZ3Jlc3MtcHJpbmNpcGFsIiwiaWF0IjoxNzkxNTk1ODI2LCJleHAiOjE3OTE1OTk0MjYsImp0aSI6Ijg3MmVlYWUwLTQxYzctNDE2Ni04OGI3LTAzNDgwMWMxNjcyMCIsInNjb3BlIjoiUFJJTkNJUEFMX1JPTEU6QUxMIn0.OonQtdHvb-scEg2zk2ijGKiZDFEyXAdauDXM06FsXG8"

  // @Test
  def test(): Unit = {
    val warehouse = java.nio.file.Files.createTempDirectory("paimon-rest-warehouse")
    val spark = SparkSession.builder()
      .master("local[1]")
      .appName("paimon-rest-spark")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", warehouse.toString)
      .config("spark.sql.extensions", LiveSparkSession.extensions)
      // Paimon 自己的扩展：建表/读写等 Paimon SQL 由它落地
      .config("spark.sql.catalog." + catalog, "org.apache.paimon.spark.SparkCatalog")
      .config("spark.sql.catalog." + catalog + ".metastore", "rest")
      .config("spark.sql.catalog." + catalog + ".uri", "http://127.0.0.1:8080")
      // warehouse 传的是 catalog 的 prefix：服务端 GET /v1/config 会用仓库名换回 prefix，
      // 客户端后续路径都以它开头。这里两侧同名
      .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
      .config("spark.sql.catalog." + catalog + ".token.provider", "bear")
      .config("spark.sql.catalog." + catalog + ".token", token)
      .config(PaimonRestManagement.MANAGEMENT_URL, "http://127.0.0.1:8080/api/management/v1")
      .config(PaimonRestManagement.TOKEN, token)
      .getOrCreate()

    spark.catalog.setCurrentCatalog(catalog)
    spark.sql("SELECT current_catalog()").show()
    spark.sql("CREATE DATABASE IF NOT EXISTS demo")
    spark.sql("SHOW TABLES IN demo").show()

    spark.sql("DROP TABLE if exists demo.paimon_sample2")
    val paimonCreateTableDdl =
      """
        |create table demo.paimon_sample2 (
        |    k int,
        |    v string,
        |    pt string
        |) USING paimon
        |PARTITIONED BY (pt)
        |tblproperties (
        |    'primary-key' = 'k, pt'
        |)
        |""".stripMargin
    // query paimon table
    spark.sql(paimonCreateTableDdl)

    val insertSql = "insert into table demo.paimon_sample2 values(1, 'xx', '20240812')"
    spark.sql(insertSql)

    val insertSql1 = "insert into table demo.paimon_sample2 values(1, 'yy', '20240812')"
    spark.sql(insertSql1)

    spark.sql("select * from demo.paimon_sample2").show()
  }
}
