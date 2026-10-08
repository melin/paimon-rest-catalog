# Spark 通过 Paimon REST Catalog 建表：可运行示例

这个目录是一个能直接跑通的示例：自动起一个 Paimon REST Server，
用 Spark SQL 经 Paimon REST catalog 建两张 Paimon 表，
然后把**服务端侧看到的元数据**打印出来。

| 文件 | 作用 |
| --- | --- |
| `run-example.sh` | 一键：解析依赖 → 编译 → 起服务端 → 跑示例 → 停服务端 |
| `SparkPaimonRestExample.java` | 示例程序本体，Java 调用 Spark 的写法 |
| `spark-defaults.conf` | 在自己的 Spark 集群上用的会话配置模板 |

---

## 跑起来

前置（只需一次）：

```bash
./mvnw -o install -DskipTests
```

然后：

```bash
cd examples/spark-paimon-rest
./run-example.sh
```

不需要预先安装 Spark 发行版：classpath 由本仓库的 Maven 依赖提供
（`paimon-rest-spark` 模块的 test 依赖里带着 Paimon 的 Spark bundle）。
服务端用 h2 内存库启动，跑完即丢，不要求本机有 MySQL。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | 18080 | 服务端端口 |
| `TOKEN` | root | REST 令牌，服务端与客户端用同一个值 |
| `DATABASE` | demo | 建在哪个库里 |
| `KEEP` | 空 | 设为 `1` 则保留服务端，便于接着用 curl 看 |

---

## 示例做了什么

分六步，每步都打印出来：

1. **建立 Spark 会话** —— catalog 指向服务端。两个 `spark.sql.extensions` 缺一不可，
   否则 Paimon 的 `SparkCatalog` 在初始化阶段就拒绝工作。
2. **建库** `paimon.demo` —— 用全限定名。`USE <库>` 只切库不切 catalog。
3. **建分区追加表** `orders` —— 含列注释、表注释、分区键、表选项。
   追加表用了固定 `bucket` 就必须给 `bucket-key`。
4. **建主键表** `dim_user` —— 主键只能写成 `TBLPROPERTIES ('primary-key' = ...)`。
   列定义里的 `PRIMARY KEY (id) NOT ENFORCED` 在 Spark 3.5 上会直接解析失败。
5. **Spark 侧查询** —— `SHOW TABLES` / `DESCRIBE` / `SHOW CREATE TABLE`。
6. **服务端侧查询** —— 直接 `GET /v1/paimon/databases/demo/tables/<表>` 读原始 JSON。

第 6 步是刻意的。前五步看到的都只是「客户端认为建好了」，
只有第 6 步能证明元数据真的落到了服务端——一个「建表返回成功但什么都没存」的
服务端，也能让前五步全部通过。

---

## 输出示例

第 5 步：

```
---- DESCRIBE paimon.demo.orders ----
  id         bigint           订单号
  amount     decimal(10,2)    null
  dt         string           分区日

---- SHOW CREATE TABLE ----
CREATE TABLE paimon.demo.dim_user (
  user_id BIGINT NOT NULL COMMENT '用户号',
  region STRING NOT NULL,
  name STRING)
USING paimon
PARTITIONED BY (region)
LOCATION 'file:/tmp/paimon-warehouse/demo.db/dim_user'
TBLPROPERTIES (
  'createdAt' = '1790786556959',
  'createdBy' = 'root',
  'path' = 'file:/tmp/paimon-warehouse/demo.db/dim_user',
  'primary-key' = 'user_id,region',
  'updatedAt' = '1790786556959',
  'updatedBy' = 'root')
```

第 6 步，`orders` 表在服务端的样子（截取）：

```json
{
  "database":"demo",
  "name":"orders",
  "path":"file:///tmp/paimon-warehouse/demo.db/orders",
  "schema":{
    "comment":"订单表",
    "fields":[
      { "description":"订单号", "id":0, "name":"id", "type":"BIGINT" },
      { "id":1, "name":"amount", "type":"DECIMAL(10, 2)" },
      { "description":"分区日", "id":2, "name":"dt", "type":"STRING" }
    ],
    "options":{ "bucket":"1", "bucket-key":"id", "owner":"melin" },
    "partitionKeys":[ "dt" ],
    "primaryKeys":[]
  },
  "owner":"root"
}
```

列注释、表注释、分区键、表选项都在。主键表那边 `primaryKeys` 是
`["user_id","region"]`，且这两项已从 `options` 里移除——服务端做了归一化。

---

## 用你自己的 Spark 集群

把这个目录里的 `spark-defaults.conf` 放进 Spark 发行版的 `conf/`，
改掉 `uri` 与 `token`，然后：

```bash
spark-sql --jars /path/to/paimon-spark-3.5_2.12-2.0.0.jar
```

jar 的坐标钉在仓库根 `pom.xml` 的 `<paimon.version>`，本地仓库路径是
`~/.m2/repository/org/apache/paimon/paimon-spark-3.5_2.12/<版本>/`。

SQL 与报错对照见 [`../../docs/spark-paimon-rest-e2e.md`](../../docs/spark-paimon-rest-e2e.md)。

---

## 示例到哪一步为止

**建表与读元数据。写入不在范围内。**

`INSERT` 与 CTAS 目前不可用：服务端还没有托管 Paimon 的 snapshot 元数据，
写入路径会在提交快照时报 `Cannot get latest schema for table` 而失败
（失败是原子的，不会在 catalog 里留下空表）。这是服务端的已知缺口，
不是配置问题——换连接方式也不会好。

---

## 两处纯 Java 调用方才会踩的坑

示例代码里有两处看起来像风格问题、实际是必需的写法：

**用 `collectAsList()` 而不是 `collect()`。** `Dataset` 是 Scala 类型，
`collect()` 返回 Scala 的 `Array[T]`，而 Scala 为泛型数组生成的签名带不出类型参数，
javac 读到的是裸 `Object`，于是 `for (Row row : ds.collect())` 报
「for-each 不适用于表达式类型，找到: Object」——看起来像 Spark 的类没加载对。
用 Scala 写调用方不会遇到。

**`--add-opens` 不能省。** Java 17+ 上跑 Spark 3.5 时，Spark 会反射访问
`java.base` 里默认封装的那几个包，缺了会报 `InaccessibleObjectException`。
`run-example.sh` 里传了五个，缺一个都会以不同的报错失败。
