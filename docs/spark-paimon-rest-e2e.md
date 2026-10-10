# 用 Spark SQL 通过 Paimon Rest Catalog 建表

本文说明怎么把 Spark 接到本服务端的 Paimon Rest Catalog 上、用 SQL 建 Paimon 表，
以及哪些能力已经可用、哪些还不在范围内。

会用到本文的场景：

- 想知道 Spark 侧的连接配置怎么写；
- 想用 SQL 建表并确认元数据真的落到了服务端；
- 遇到报错想快速对照原因（见第 5 节）。

可执行的验收用例在 `PaimonTableDdlTests`，跑法见第 6 节。

---

## 1. Spark 侧需要什么

### 1.1 依赖

| 构件 | 版本 | 说明 |
| --- | --- | --- |
| `org.apache.paimon:paimon-spark-3.5_2.12` | `2.0.0` | Paimon 的 Spark bundle，自带 Paimon 核心与文件系统实现，并把 Guava / Jackson / netty 等 shade 到 `org.apache.paimon.shade.*`，因此不会与 Spark 自带的依赖冲突 |
| `org.apache.spark:spark-hive_2.12` | 与 Spark 同版本 | Paimon 的 Spark 扩展在初始化时引用其中的 `HiveUDFExpressionBuilder`。用 `spark-submit` / `spark-sql` 时发行版自带，直接跑 JUnit 时要显式引入 |

构件名带 Scala 二进制版本后缀（`_2.12`），换 Spark 或 Scala 版本时必须同步换。

本项目把这两项声明为 `paimon-rest-spark` 的**测试**依赖（`paimon-rest-spark/pom.xml`），
发布产物里不含它们——本模块自己只做管理语句扩展，不依赖 Paimon 运行时。

**warehouse 落在对象存储上时还要补一样**：对应 scheme 的 FileIO 实现。
`paimon-spark` bundle 只带本地文件系统与 Hadoop 回退，catalog 的仓库是 `s3://…` 时，
客户端会以

```
org.apache.paimon.fs.UnsupportedSchemeException: Could not find a file io implementation
for scheme 's3' in the classpath. Hadoop FileSystem also cannot access this path 's3://…'
```

失败。这条报错来自**引擎的类路径，与服务端无关**：服务端做不做 S3 都改变不了它。
S3 加 `org.apache.paimon:paimon-s3:2.0.0`（OBS / OSS 分别是 `paimon-obs` / `paimon-oss`），
生产上放进 `spark/jars`，本仓库的测试已经在 `paimon-rest-spark/pom.xml` 里声明。

### 1.2 会话配置

```properties
# 两个扩展都要装，缺 Paimon 那个会被它自己的启动检查拦下
spark.sql.extensions=io.github.melin.paimonrest.spark.ManagementSparkExtensions,org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions

spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog
spark.sql.catalog.paimon.metastore=rest
spark.sql.catalog.paimon.uri=http://catalog-host:8080
# 这里填的是 catalog 的 prefix，不是文件路径：
# 客户端先请求 GET /v1/config?warehouse=paimon，服务端用仓库名换回 prefix，之后所有路径都以它开头
spark.sql.catalog.paimon.warehouse=paimon
spark.sql.catalog.paimon.token.provider=bear
spark.sql.catalog.paimon.token=<调用者主体的令牌>
# 仓库在对象存储（s3:// / obs:// / oss://）上时**必须**打开，见 1.4
spark.sql.catalog.paimon.data-token.enabled=true
```

两个扩展的顺序有讲究：本项目的扩展先用自己的解析器识别管理语句，识别不了就原样交回
Spark 原生解析器，与 Paimon 注入的规则不重叠，因此谁在前都能工作，但把它放在前面更符合
「先尝试扩展语法、失败即回落」的读法。

`spark.sql.extensions` 只在**建会话那一刻**生效，会话建好之后再设不会生效。

### 1.3 手工验证

```bash
spark-sql \
  --jars paimon-spark-3.5_2.12-2.0.0.jar \
  --conf spark.sql.extensions=io.github.melin.paimonrest.spark.ManagementSparkExtensions,org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
  --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
  --conf spark.sql.catalog.paimon.metastore=rest \
  --conf spark.sql.catalog.paimon.uri=http://127.0.0.1:8080 \
  --conf spark.sql.catalog.paimon.warehouse=paimon \
  --conf spark.sql.catalog.paimon.token.provider=bear \
  --conf spark.sql.catalog.paimon.token=root
```

### 1.4 对象存储的凭据从哪来

仓库在 `s3://`（或 `obs://` / `oss://`）上时，客户端自己**没有任何密钥**——
端点和密钥都由服务端下发，这正是 REST 目录存在的意义。链路是：

1. 类路径里要有对应 scheme 的 FileIO 实现，见 1.1；
2. 会话里要打开 `data-token.enabled`，见 1.2；
3. 客户端在新建 FileIO 之前请求
   `GET /v1/{prefix}/databases/{db}/tables/{t}/token`，服务端按 catalog 的
   `storageConfigInfo` 返回一份短时效令牌（键值对 + 有效期）。

服务端返回的令牌里，S3 的**密钥有两个名字**，都要给：

```
s3.access-key-id / s3.secret-access-key   # 规格（Polaris / Iceberg）的叫法
s3.access-key    / s3.secret-key          # Paimon 引擎的叫法
```

Paimon 拿到令牌后会把所有 `s3.*` 键按前缀整体翻译成 Hadoop 的 `fs.s3a.*`
（`s3.access-key` → `fs.s3a.access-key`），再做一次小范围镜像
（`fs.s3a.access-key` → `fs.s3a.access.key`）。于是 `s3.access-key-id` 只会变成
`fs.s3a.access-key-id`——Hadoop 不认识这个键，镜像表里也没有它，**密钥被静默丢弃**，
引擎退回默认凭据链（环境变量 / 实例身份），在测试机上必然认证失败。
定位类键名同理：`s3.endpoint` → `fs.s3a.endpoint`、`s3.region` → `fs.s3a.region`
都能对上，`s3.path-style-access` 会被镜像成 Hadoop 的
`fs.s3a.path.style.access`。

> **`endpoint` 写裸 IP 时注意路径风格。** 关掉 path-style 后，客户端会按虚拟主机风格
> 把桶名拼进主机名（`<bucket>.<endpoint>`）去解析；`endpoint` 是
> `http://172.18.6.181:9330` 这种裸 IP 时，拼出来的主机名解析不了。
> 这类对象存储上的 catalog 一般要把 `storageConfigInfo.pathStyleAccess` 设为 `true`。

---

## 2. 能执行的 SQL

**库名与表名一律用全限定名**（`paimon.<库>.<表>`）。`USE <库>` 只切换当前库、
不切换 catalog，漏掉 catalog 前缀会把表建到 `spark_catalog` 里，后续操作会以
「paimon is not a valid Spark SQL Data Source」收场（见第 5 节）。

### 2.1 建库

```sql
CREATE DATABASE IF NOT EXISTS paimon.demo;
```

### 2.2 建分区追加表

```sql
CREATE TABLE IF NOT EXISTS paimon.demo.orders (
  id     BIGINT        COMMENT '订单号',
  amount DECIMAL(10,2),
  dt     STRING        COMMENT '分区日'
) USING paimon
PARTITIONED BY (dt)
COMMENT '订单表'
TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id');
```

`'bucket-key'` 不能省：追加表用固定 bucket 时 Paimon 要求显式指定分桶键，否则建表被拒
（`You should define a 'bucket-key' for bucketed append mode`）。想用动态分桶就写
`'bucket' = '-1'`。

### 2.3 建主键表

```sql
CREATE TABLE IF NOT EXISTS paimon.demo.dim_user (
  user_id BIGINT,
  region  STRING,
  name    STRING
) USING paimon
TBLPROPERTIES ('primary-key' = 'user_id', 'bucket' = '1');
```

主键必须用表选项写。Spark 3.5 的解析器不接受把主键写在列定义里
（`..., PRIMARY KEY (user_id) NOT ENFORCED)` 会直接 `ParseException`），
这一点与 Paimon 文档里 Flink 侧的写法不同。

### 2.4 其他形态

```sql
-- 未分区表
CREATE TABLE paimon.demo.flat (id BIGINT, note STRING) USING paimon
TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id');

-- 未知选项原样保留（Paimon 的表选项是开放的）
CREATE TABLE paimon.demo.with_opts (id BIGINT) USING paimon
TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id',
               'custom.retention' = '7d', 'snapshot.num-retained.min' = '3');

-- 复制结构（含分区键），不复制数据
CREATE TABLE paimon.demo.dst LIKE paimon.demo.orders;
```

### 2.5 查看

```sql
SHOW TABLES IN paimon.demo;
DESCRIBE paimon.demo.orders;
SHOW CREATE TABLE paimon.demo.orders;
```

时间旅行：

```sql
-- 数字是**快照 id**（客户端从 1 开始递增分配），不是「第几个版本」，
-- 也不是快照 JSON 里的 version——后者是快照文件格式版本，每个快照都是同一个值（当前 3）
SELECT * FROM paimon.demo.orders VERSION AS OF 2;

-- 标签名也可以放在这里：标签本身就是「给某个快照起的名字」
SELECT * FROM paimon.demo.orders VERSION AS OF 'v1';
```

服务端对 `version` 的解析顺序与 Paimon 客户端一致：`EARLIEST` / `LATEST` / 数字（快照 id）/
标签名，前三者之外的一律按标签名查，取不到回 404。写成 `VERSION AS OF 3` 而表里只有快照
1 与 2 时，得到的是 404，而不是「第 3 版」——这一点容易与「版本号」的直觉搞混。

---

## 3. 服务端保存了什么

建表请求由 Paimon 客户端发出，服务端按 catalog OpenAPI 规格接收。用 HTTP 直接读元数据
看到的形状（节选自真实响应）：

```bash
curl -H 'Authorization: Bearer root' \
  http://127.0.0.1:8080/v1/paimon/databases/demo/tables/orders
```

```json
{
  "database": "demo",
  "name": "orders",
  "path": "file:///tmp/paimon-warehouse/demo.db/orders",
  "isExternal": false,
  "schemaId": 0,
  "schema": {
    "comment": "订单表",
    "fields": [
      { "id": 0, "name": "id",     "type": "BIGINT",        "description": "订单号" },
      { "id": 1, "name": "amount", "type": "DECIMAL(10, 2)" },
      { "id": 2, "name": "dt",     "type": "STRING",        "description": "分区日" }
    ],
    "options": { "bucket": "1", "bucket-key": "id" },
    "partitionKeys": ["dt"],
    "primaryKeys": []
  }
}
```

三点值得留意：

- **表注释与列注释都真的传到了服务端**：客户端侧 `SHOW CREATE TABLE` 能显示注释，
  但那是它自己拼的字符串，只有读服务端元数据才能确认注释没有在路上丢。
- **`path` 由服务端的仓库位置推导**：管理 API 改过 catalog 的 `storageConfigInfo` 之后，
  新建的表就会落到新位置下——这是管理面与数据面共享同一份配置的证据。
- **`primaryKeys` 与 `options` 是两个独立字段**：主键用表选项表达时，服务端会把它
  归一化进 `primaryKeys`。

---

## 4. 服务端会物化 Paimon 表目录

本服务端保存**服务端数据库里的元数据**，并把其中引擎读写必需的那一部分
**物化到 Paimon 表目录**——具体是 `schema/schema-<n>` 与 `snapshot/snapshot-<n>`
（外加 `snapshot/LATEST` 提示文件）。因此：

| 能力 | 状态 |
| --- | --- |
| 建库、建表（含分区、主键、选项、注释、`LIKE`） | 可用 |
| `SHOW TABLES` / `DESCRIBE` / `SHOW CREATE TABLE` | 可用（读的是服务端元数据） |
| `DROP TABLE` / `DROP DATABASE` | 可用 |
| `INSERT` / `CREATE TABLE ... AS SELECT` / `SELECT` 数据 | 可用 |
| 绕开服务端、直接读仓库的读端（Paimon CLI、以文件系统为源的快照枚举等） | 可用（元数据已落进仓库） |

### 4.1 为什么必须物化 schema 与快照文件

`INSERT` 走到提交快照那一步时，引擎会在**仓库**里读 `<table>/schema/schema-<n>`
取 schemaId。这个文件不在，就抛：

```
RuntimeException: Exception occurs when preparing snapshot #1 by user <uuid> with hash
  9223372036854775807 and kind APPEND. Clean up.
  -> RuntimeException: Cannot get latest schema for table orders
```

并回滚这次提交，把已经写好的 parquet 文件与 manifest 留在目录里当孤儿。
所以缺的从来不是「服务端拒绝写入」，而是「服务端把 schema 存在自己库里、
却没落到引擎要看的那个位置」。

**这份责任为什么在服务端。** Paimon 的 REST Catalog 客户端是瘦客户端：
`RESTCatalog.createTable` 只把 schema POST 给服务端，客户端代码里根本没有写
`schema/schema-<n>` 的路径。对照之下，`HiveCatalog`（自己就是表的拥有者）
的 `createTableImpl` 会先 `SchemaManager.createTable` 写目录与 schema 文件、
再登记 HMS。换成 REST 形态，这一步就落到了服务端——官方 REST 服务端是内嵌一个
文件系统 catalog 来做的，本工程按同样的分工实现，见 `TableMetadataService`。

**号必须与数据库里的 `schemaId` 逐号对应**，这不是格式问题而是语义问题：

| 动作 | 写出的文件 | `schemaId` |
| --- | --- | --- |
| 建表 | `schema-0` | `0` |
| `ALTER TABLE` | `schema-<n>` | `n`（递增） |
| `rollback-schema` | 把旧内容写到 `schema-<新号>` | 回滚在版本号上也是**向前一步**，不复用旧号 |

落库与落仓库的顺序也固定：**先落库再落仓库**。严格模式（见 4.2）下仓库写入失败会
让整个事务回滚，catalog 里不会留下一张没有 schema 文件的表；反过来先写仓库再落库，
失败时留下的是仓库里的孤儿目录，而那种残留没有任何接口能清理。

挂接点是 `TableService` 的 create / alter / rollback-schema / drop / commit / rollback
六处。`register` **刻意不挂**：它的语义是「仓库里已经有一张表，本服务端只登记它的位置」，
往那个目录里写 schema 等于用服务端的空 schema 覆盖那张表的真实元数据，是数据损坏
而不是补全。`external=true` 的表在后续所有写入路径上同样被跳过。

**快照是同一件事的另一半，而且它的缺失更隐蔽。** 提交快照时，`SnapshotCommit` 实现的选择在
`CatalogEnvironment.snapshotCommit`：`catalogLoader != null && supportsVersionManagement`
时用 `CatalogSnapshotCommit`（把 `Snapshot` 对象 POST 给 `/tables/{t}/commit`），
否则才用 `RenamingSnapshotCommit` 自己写 `snapshot/snapshot-<n>`。
`RESTCatalog.supportsVersionManagement()` **恒返回 true**，所以走 REST catalog 的客户端
从不写快照文件，`RenamingSnapshotCommit` 在这条链路上根本不会被实例化。

不写会怎样：**用 REST catalog 读写一切正常**——读路径也走服务端，
`SnapshotLoaderImpl.load()` 调的就是 `catalog.loadSnapshot(identifier)`，
查的是服务端数据库。于是「查得出来数据」与「仓库里没有 snapshot 目录」可以同时成立，
只有绕开服务端直接看仓库时才会露出来。本服务端因此把
`RenamingSnapshotCommit.commit` 的两步（原子写 `snapshot/snapshot-<id>` + 更新
`snapshot/LATEST`）在服务端重做一遍。

写进仓库的内容是**客户端发来的那一段 JSON 原文**，不是按服务端数据库字段重新拼的：
规格里的 `Snapshot` 只建模了一部分字段，客户端的 `org.apache.paimon.Snapshot` 还带
`properties`（序列号水位）、`operation`、`nextRowId` 等。按 DTO 重拼会静默丢字段，
而丢 `properties` 尤其阴——它不会解析失败，只会让读端重算序列号，表现为去重与变更日志
语义悄悄改变。快照文件同样先解析一遍 Paimon 自己的模型再落盘：写进去的东西至少要能被
Paimon 读回来。

回滚快照（`rollback`）会把目标之后的 `snapshot-<id>` 删掉、把 `LATEST` 指回目标。
只改库不删文件，留下的是「数据库说只有快照 1、仓库里有 1/2/3 且 `LATEST` 指着 3」。

### 4.2 三档开关

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `paimon.rest.table-metadata.enabled` | `true` | 是否物化 schema 与快照。`external` 表始终不物化 |
| `paimon.rest.table-metadata.fail-on-error` | `false` | 物化失败时是否让请求失败（500 且事务回滚）。默认「尽力而为」：记一条 WARN，元数据照常落库；打开后建表与提交快照会因仓库不可写而直接失败 |
| `paimon.rest.table-metadata.purge-on-drop` | `false` | `DROP TABLE` 时是否连表目录一起删。默认只删元数据、保留数据，删错不可逆 |

**仓库在对象存储上时，服务端自己也要有对应 scheme 的 FileIO 实现。**
这一步与第 1.1 节客户端那件事是**两件独立的事**，缺任何一件都在各自的进程里失败：

- 客户端缺 → `UnsupportedSchemeException`，报在 Spark 侧；
- 服务端缺 → 物化失败，报在服务端日志里（`fail-on-error=false` 时只是一条 WARN，
  于是表现又回到 `Cannot get latest schema`）。

`paimon-core` 自带的 SPI 实现只有 `file` / `hdfs` / `viewfs` 三种（启动后日志会打一行
`discovered Paimon FileIO implementations for schemes [...]`，可以据此确认）。
`s3://` / `obs://` / `oss://` 要按 Paimon 的插件机制额外把对应构件放进服务端类路径，
例如 `org.apache.paimon:paimon-s3:<版本>`。

**对象存储上还有第二个前提：服务端运行时必须是 JDK 17，不能是 18 及以上。**
JDK 18 的 JEP 418 给 `InetAddress` 加了解析器 SPI，`InetAddress.loadResolver()` 会
`ServiceLoader` 找一个 `InetAddressResolverProvider`；而 `paimon-s3` 插件包把 dnsjava 那个
provider 的**声明**放在插件根、**实现类**放在 `META-INF/versions/18/`（多版本 jar 布局，
解成插件目录后不再生效），`PluginFileIO` 又把线程上下文类加载器切成插件 loader，
于是那块作用域里**任何** `InetAddress` 解析都抛
`ServiceConfigurationError: ... DnsjavaInetAddressResolverProvider not found`，
触发点是 Hadoop `MetricsSystemImpl.getHostname()`。后果是 `s3://` 上的
`<table>/schema` 与 `<table>/snapshot` 一个文件都写不出去——而因为是插件隔离的异常类，
现象与 S3 看着毫无关系。`Dockerfile` 两个阶段都钉 17 就是为了这条。

### 4.3 这两个边界用例为什么会翻转

`PaimonTableDdlTests.createTableAsSelectWritesDataAndReadsItBack`
与 `PaimonRestCatalogTest.insertWritesThroughAndIsReadable` 曾经断言的是写入**失败**，
并且把失败链钉在某一步上。那是刻意的**守卫用例**：断言「当前不可用」而不是
「期望不可用」，边界一移动就先响，提醒改动者回来同步本节。

schema 物化补上之后它们已经翻转成断言写入成功。这个类保留下来的、仍然压着
「失败停在哪儿」的部分是**别的东西**：写入必然要碰 `s3://` 上的 `<table>/schema`
与 `<table>/snapshot`，因此它也是客户端第一次真正访问对象存储的地方——停在
`UnsupportedSchemeException` 说明客户端类路径缺 FileIO 实现（见 1.1）；
停在一句 `UnknownReason` 说明凭据没按引擎认的键名送达（见 1.4）。

---

## 5. 常见报错对照

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `paimon is not a valid Spark SQL Data Source` | 表被建到了 `spark_catalog` 而非 `paimon` catalog。`USE <库>` 只切库不切 catalog，漏写全限定名就会走到 `spark_catalog` 的 V1 数据源解析 | 用 `paimon.<库>.<表>` 全限定名。**不要**靠 `spark.sql.sources.useV1SourceList` 加 `paimon` 来绕：那会让 `CREATE TABLE ... USING paimon` 反而走 V1 路径而失败 |
| `When using Paimon, it is necessary to configure 'spark.sql.extensions' and ensure that it includes 'org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions'` | `spark.sql.extensions` 里少了 Paimon 的扩展 | 两个扩展都写上，见 1.2 |
| `ParseException`（无正文），语句里含 `PRIMARY KEY (...) NOT ENFORCED` | Spark 3.5 的 `CREATE TABLE` 语法不接受列定义里的主键 | 改用 `TBLPROPERTIES ('primary-key' = '<列>')` |
| `You should define a 'bucket-key' for bucketed append mode` | 追加表**显式**给了固定 `bucket` 却没给分桶键。不给 `bucket` 时 Paimon 用动态分桶，不需要它 | 加 `'bucket-key'`，或改成 `'bucket' = '-1'` |
| `ParseException: ... is a reserved table property` | `TBLPROPERTIES` 里写了 Spark 自己的保留属性（如 `owner`）。这条在客户端解析阶段就被拒，请求根本没到服务端 | 删掉该属性。`owner` 由服务端按提交者自己补，不是客户端该传的字段 |
| `Cannot get latest schema for table <表名>` | 引擎在仓库里读不到 `<table>/schema/schema-<n>`。服务端已负责物化它（见第 4 节），因此这条报错现在只意味着**物化那一步没成功** | 看服务端日志里 `TableMetadataService` 的 WARN：若提示缺对应 scheme 的 FileIO 实现，把 `paimon-s3` / `paimon-obs` / `paimon-oss` 加进服务端类路径；若服务端根本不该写仓库，把 `paimon.rest.table-metadata.enabled` 设为 `false` 并把 `fail-on-error` 打开，让它在建表时就明确失败，而不是拖到写入 |
| 服务端 WARN 里出现 `ServiceConfigurationError: java.net.spi.InetAddressResolverProvider: Provider org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider not found` | 服务端跑在 JDK 18+ 上，而 `paimon-s3` 插件包把 dnsjava 那个 provider 的实现类放在 `META-INF/versions/18/`，插件 loader 加载不到 → 该作用域内任何 `InetAddress` 解析都失败（触发点是 Hadoop 度量系统的 `getHostname`） | **把服务端运行时换成 JDK 17**，见 4.2。这是唯一可靠的处理；这条 WARN 会让对象存储上的 schema / 快照都写不出去，而 REST 读写看着一切正常 |
| `Cannot get latest schema for table <表名>`（服务端日志里**没有**对应 WARN） | 物化那一步压根没被触发：`paimon.rest.table-metadata.enabled=false`，或者表是 `register` 进来的 `external` 表 | 前者打开开关；后者是有意不物化的（位置不归本服务端所有），需要引擎侧自己保证目录里有 schema |
| `Content-Type 'text/plain;charset=UTF-8' is not supported` | 服务端未放宽 JSON 转换器。Paimon 客户端（Apache HttpClient 5）把 JSON 请求体标成 `text/plain`，早期版本会因此 415 | 已由 `JsonContentTypeConfig` 修复；若仍遇到，确认服务端版本 |

---

## 6. 怎么验证

一键跑完文档校验与全部端到端用例（自动起服务端、跑完自动停）：

```bash
./scripts/e2e-spark-sql.sh
```

它执行的三个测试类必须**同批运行**：一个 JVM 只能有一个 SparkContext，而
`spark.sql.extensions` 只在建会话时生效，因此它们共用 `LiveSparkSession` 的会话。
把用桩服务端的 `ManagementSqlExecutionTests` 拉进同一批会先建会话、把端点与扩展都错位，
`LiveSparkSession` 会就此直接报错（而不是静默跑错）。

只跑建表用例：

```bash
# 先起一个服务端
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=h2 --server.port=18080 \
  --paimon.rest.auth.enabled=true --paimon.rest.auth.tokens[0]=root \
  --paimon.rest.authorization.enabled=true \
  --paimon.rest.authorization.service-admins[0]=root \
  --paimon.rest.authorization.bootstrap-principal=root

# 对着它跑建表与写入用例
mvn -o -pl paimon-rest-spark test -Dtest=PaimonTableDdlTests \
  -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
  -De2e.management.token=root
```

`-De2e.paimon.url` 可显式指定 catalog API 基址；不指定时由
`-De2e.management.url` 去掉 `/api/management/v1` 得到——两者本来就挂在同一个服务端上。
