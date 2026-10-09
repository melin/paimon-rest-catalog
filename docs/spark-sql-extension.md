# Spark SQL 管理语法扩展

`paimon-rest-spark` 子模块把管理 API 的能力接进 Spark SQL：主体、角色与授权可以用 SQL 语句
管理，而不必手写 HTTP 请求。

```sql
CREATE PRINCIPAL svc_etl PROPERTIES ('owner' = 'data-platform');
GRANT CATALOG ROLE reader TO PRINCIPAL ROLE etl_reader IN CATALOG paimon;
GRANT TABLE_READ_DATA ON TABLE default.orders IN CATALOG paimon TO CATALOG ROLE reader;
SHOW GRANTS FOR CATALOG ROLE reader IN CATALOG paimon;
```

---

## 1. 启用

前置：`paimon-rest-spark` 的 jar 在 Spark 的 classpath 上，管理服务可达。

```bash
spark-sql \
  --conf spark.sql.extensions=io.github.melin.paimonrest.spark.ManagementSparkExtensions \
  --conf spark.paimon.rest.management.url=http://catalog-host:8080/api/management/v1 \
  --conf spark.paimon.rest.token=<执行这些语句的主体的令牌>
```

编程方式等价：

```scala
val spark = PaimonRestManagement.install(SparkSession.builder())
  .config(PaimonRestManagement.MANAGEMENT_URL, "http://catalog-host:8080/api/management/v1")
  .config(PaimonRestManagement.TOKEN, token)
  .getOrCreate()
```

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `spark.paimon.rest.management.url` | `http://127.0.0.1:8080/api/management/v1` | 管理 API 基址，须含 `/api/management/v1` 前缀 |
| `spark.paimon.rest.token` | 空 | 作为 `Authorization: Bearer <token>` 发出；为空时不发送该头 |
| `spark.paimon.rest.management.timeoutSeconds` | `30` | 单次请求超时；非数字或非正数时退化为默认值 |

> **令牌应当填「执行这些语句的主体」的令牌，而不是管理员令牌。** 管理服务按调用者身份判定
> 权限，用管理员令牌会让所有 SQL 绕过权限判定，也就失去了用 SQL 管理权限的意义。

> **配置必须在第一条管理语句之前设好。** 客户端在会话的第一条管理语句到来时创建，之后整会话
> 复用；之后再改 URL 或令牌不会生效，需要重建会话。这样做的原因是 `HttpClient` 自带连接池与
> 选择器线程，每条语句新建一个并不划算。

---

## 2. 语句清单

共 21 条语句，分四类。`[]` 表示可选，`|` 表示二选一。

本节只做索引；每条语句的完整语法、参数、结果列、错误与注意事项见
[`spark-sql-reference.md`](spark-sql-reference.md)。

### DDL：主体

| 语句 | 说明 |
| --- | --- |
| `CREATE PRINCIPAL [IF NOT EXISTS] <name> [PROPERTIES (k = v, ...)]` | 返回 `principal`、`client_id`、`client_secret`；**明文密钥只在这一行结果里出现** |
| `DROP PRINCIPAL [IF EXISTS] <name>` | 无结果行 |
| `ALTER PRINCIPAL <name> SET PROPERTIES (k = v, ...)` | 增量设置 |
| `ALTER PRINCIPAL <name> UNSET PROPERTIES (k, ...)` | 增量移除 |
| `RESET PRINCIPAL <name>` | 重签凭据，返回 `client_id` 与新的 `client_secret` |
| `ROTATE PRINCIPAL <name>` | 与 `RESET` 同义（对应规格的 `/rotate` 与 `/reset` 两个端点） |

### DDL：角色

| 语句 | 说明 |
| --- | --- |
| `CREATE PRINCIPAL ROLE [IF NOT EXISTS] <name> [PROPERTIES (...)]` | |
| `DROP PRINCIPAL ROLE [IF EXISTS] <name>` | |
| `ALTER PRINCIPAL ROLE <name> SET PROPERTIES (...)` / `UNSET PROPERTIES (...)` | |
| `CREATE CATALOG ROLE [IF NOT EXISTS] <name> IN CATALOG <catalog> [PROPERTIES (...)]` | catalog role 归属于具体 catalog |
| `DROP CATALOG ROLE [IF EXISTS] <name> IN CATALOG <catalog>` | |
| `ALTER CATALOG ROLE <name> IN CATALOG <catalog> SET\|UNSET PROPERTIES (...)` | |

### DCL：装配与授权

| 语句 | 说明 |
| --- | --- |
| `GRANT PRINCIPAL ROLE <role> TO PRINCIPAL <principal>` | |
| `REVOKE PRINCIPAL ROLE <role> FROM PRINCIPAL <principal>` | |
| `GRANT CATALOG ROLE <role> TO PRINCIPAL ROLE <principalRole> IN CATALOG <catalog>` | |
| `REVOKE CATALOG ROLE <role> FROM PRINCIPAL ROLE <principalRole> IN CATALOG <catalog>` | |
| `GRANT <priv>[, <priv>...] ON <resource> TO CATALOG ROLE <role>` | 一项权限展开成一次授权请求 |
| `REVOKE <priv>[, <priv>...] ON <resource> FROM CATALOG ROLE <role>` | |

`<resource>` 的六种写法：

```sql
CATALOG paimon
NAMESPACE default IN CATALOG paimon
TABLE default.orders IN CATALOG paimon
VIEW default.v1 IN CATALOG paimon
POLICY default.mask_pii IN CATALOG paimon
SEMANTIC MODEL default.revenue IN CATALOG paimon
```

对象级资源（`TABLE` / `VIEW` / `POLICY` / `SEMANTIC MODEL`）必须写成
`<namespace>.<对象名>`。命名空间是授权作用域的一部分，单段名字无法确定作用域，
因此解析期直接报错，不做「猜默认命名空间」的处理。

目标 catalog role 所属的 catalog 不单独书写，而是从资源描述中取得——`ON TABLE ns.t IN CATALOG paimon`
已经写明 catalog 是 `paimon`，避免同一语句里重复两次。

### SHOW

| 语句 | 结果列 |
| --- | --- |
| `SHOW CATALOGS` | `name`、`type` |
| `SHOW PRINCIPALS [FOR PRINCIPAL ROLE <role>]` | `name`、`client_id`；带 `FOR` 时为 `principal` |
| `SHOW PRINCIPAL ROLES [FOR PRINCIPAL <principal>]` | `name`、`federated`；带 `FOR` 时为 `principal_role` |
| `SHOW CATALOG ROLES IN CATALOG <catalog> [FOR PRINCIPAL ROLE <role>]` | `name`、`catalog`；带 `FOR` 时为 `catalog_role` |
| `SHOW GRANTS FOR CATALOG ROLE <role> IN CATALOG <catalog>` | `catalog`、`catalog_role`、`type`、`resource`、`namespace`、`object_name`、`privilege` |

`SHOW GRANTS` 的 `resource` 列把多级命名空间与对象名拼成可读的完整名，同时保留
`namespace` 与 `object_name` 两列以便筛选：

| 授权类型 | `type` | `resource` | `namespace` | `object_name` |
| --- | --- | --- | --- | --- |
| catalog 级 | `catalog` | `paimon` | `null` | `null` |
| namespace 级 | `namespace` | `default` | `default` | `null` |
| 表级 | `table` | `default.orders` | `default` | `orders` |

### 写入语句不返回结果行

除 `CREATE PRINCIPAL`、`RESET` / `ROTATE PRINCIPAL` 与 `SHOW` 之外，其余语句返回 0 行，
与 Spark 原生 `CREATE TABLE` 这类命令一致，便于脚本编排：

```python
spark.sql("CREATE PRINCIPAL ROLE etl_reader").collect()   # []
spark.sql("SHOW PRINCIPALS").show()                        # 有结果
```

`CREATE PRINCIPAL` 返回的一次性明文密钥必须在这一行结果里取走：

```python
row = spark.sql("CREATE PRINCIPAL svc_etl").collect()[0]
print(row["client_id"], row["client_secret"])
# 服务端不保存明文；错过这一行只能靠 ROTATE PRINCIPAL 重新签发
```

---

## 3. 实现方式与两个必须知道的约束

### 3.1 为什么自带一份语法

Spark 3.5 的 `SqlBase.g4` 是**封闭**的：它的 `statement` 规则没有类似 `.*? #unsupported` 的
兜底分支，因此形如 `CREATE PRINCIPAL x` 的语句在词法 / 语法阶段就会失败，无法靠 visitor 拦下来；
官方扩展点 `ParserInterface` 也不提供「往已有语法里加规则」的能力。

因此本模块自带一份只描述管理语句的小语法
（`paimon-rest-spark/src/main/antlr4/io/github/melin/paimonrest/spark/parser/ManagementSql.g4`），
在 `ParserInterface.parsePlan` 里**先试自己的解析器**：

- 解析成功 → 执行管理操作；
- 解析失败（`ParseCancellationException`）→ 原样交回 Spark 原生解析器。

判断依据是「整条语句能否匹配到 EOF」而不是字符串前缀猜测，因此不会误吞
`SHOW TABLES`、`SHOW DATABASES`、`CREATE TABLE ...` 这类原生语句，也不会因为残篇
（如 `CREATE PRINCIPAL`）而给出两套错误信息。

### 3.2 ANTLR 版本必须与目标 Spark 发行版一致

**这是本模块最容易踩、后果最重的一个约束。** ANTLR 的 ATN 序列化格式随版本变化，
代码生成版本与运行时版本不一致时，序列化版本号会对不上：

```
java.io.InvalidClassException: org.antlr.v4.runtime.atn.ATN;
  Could not deserialize ATN with version 3 (expected 4)
    at org.apache.spark.sql.catalyst.parser.SqlBaseLexer.<clinit>
```

Spark 3.5.9 的 `spark-sql-api` 依赖 `antlr4-runtime` **4.9.3**，其自带的
`SqlBaseLexer` / `SqlBaseParser` 就是用 4.9.3 生成的。如果本模块把 `antlr4-runtime` 抬到
4.13.x（ATN 序列化版本 4），运行时会**把 Spark 自己的 antlr4-runtime 顶掉**，于是受损的不是
本扩展，而是整个会话的 SQL 解析——连 `SELECT 1` 都会失败。

因此 `antlr.runtime.version` 与 `antlr4-maven-plugin` 的版本都钉在 4.9.3，
**升级 `spark.version` 时必须同步核对这个值**（见根 `pom.xml` 的注释）。

### 3.3 客户端的响应体形状与状态码取自规格

管理规格对「单个资源」的响应**大多不套外壳**，只有主体相关的接口例外：

| 端点 | 响应 |
| --- | --- |
| `POST /principals`、`POST /principals/{n}/reset\|rotate` | `{"principal":{…},"credentials":{…}}` |
| `GET` / `PUT /principals/{n}` | 裸 `Principal` |
| `POST` / `GET` / `PUT /principal-roles/{n}` | 裸 `PrincipalRole` |
| `POST` / `GET` / `PUT /catalogs/{c}/catalog-roles/{n}` | 裸 `CatalogRole` |
| 列表类接口 | 具名数组：`{"catalogs":[…]} `、`{"principals":[…]} `、`{"roles":[…]} `、`{"grants":[…]} ` |

状态码同样以规格为准，其中两条容易写错：

- `POST /principals/{n}/reset|rotate` 成功返回 **200**（更新凭据），不是 201；
- `POST /principals`、`POST /principal-roles`、`POST .../catalog-roles`、授予类 `PUT` 返回 **201**；
  `DELETE` 返回 **204**。

读错响应形状的后果不是报错而是**静默取到空值**：例如从 `GET /principals/{n}` 上多读一层
`principal`，拿到的 `entityVersion` 会变成 0，下一次 `PUT` 就会因为版本不符得到 409
（消息形如 `expected 0, actual 1`）。这个缺陷正是由第 4 节第 4 层的真实服务端验收发现的。

---

## 4. 测试分层

四层测试，各自负责不同的问题；前两层便宜，第四层最贵但最能发现跨实现的契约偏差。

| 层 | 测试类 | 覆盖 |
| --- | --- | --- |
| 1 解析 | `ManagementSqlParsingTests`（21 个用例） | 21 条语句的解析结果、GRANT 的资源层级拆分、多权限展开、反引号与注释、**非管理语句必须交回原生解析器** |
| 2 客户端 | `ManagementApiClientTests`（25 个用例） | 请求方法与路径、请求体字段形状、路径段编码、`ALTER` 的读-合并-回写、**单资源裸对象与状态码**、错误映射（403/404/409） |
| 3 桩端到端 | `ManagementSqlExecutionTests`（12 个用例） | 真实 `SparkSession` + 进程内桩：扩展是否真被加载、`spark.sql` 是否真执行命令、结果行列名、原生 SQL 不受影响 |
| 4 真实服务端 | `ManagementSqlLiveServerTests`（4 个用例） | 对真实 Paimon Rest Catalog Server 跑完整 SQL 链路，验证两侧实现的契约一致性 |
| 4 真实服务端 | `PaimonTableDdlTests`（9 个用例） | 用 Spark SQL 经 Paimon Rest Catalog 建表（分区 / 主键 / `LIKE` / 幂等 / 冲突），并直接读服务端元数据核对 schema、注释、分区键、主键与表选项——建表路径的契约一致性，详见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) |
| 4 真实服务端 | `SparkSqlDocExamplesTests`（1 个用例） | 抽出参考文档第 11 节的示例逐条执行，断言撤销语义与清理结果——**让文档里的 SQL 与代码同生共死** |

第 1、2 层不建 `SparkSession`（解析只依赖客户端实例，不依赖会话），第 3 层起本地会话，
第 4 层默认跳过、需要显式指向服务端：

```bash
./scripts/e2e-spark-sql.sh      # 先校验参考文档，再自动起服务端并跑第 4 层
```

参考文档另有一层不需要服务端的静态校验（`scripts/verify-spark-sql-doc.py`）：
比对文档与语法文件的语句数、规格的 64 项权限枚举与分组归属、实现声明的结果列与配置键。
它刻意不复用任何生成逻辑，因此能发现「文档写得很像但不成立」这类人工读稿看不出的问题。

---

## 5. 已知边界

1. **管理关键字是保留字。** `CATALOG`、`TABLE`、`VIEW`、`POLICY`、`NAMESPACE`、`ROLE`、`SHOW`、
   `SET`、`ON`、`IN`、`FOR`、`TO`、`IF`、`NOT`、`EXISTS`、`MODEL`、`SEMANTIC`、`ALTER`、`CREATE`、
   `DROP`、`RESET`、`ROTATE`、`GRANT`、`REVOKE`、`PROPERTIES`、`UNSET`、`FROM` 等词在
   命名空间与对象名位置必须加反引号，例如 ``ON TABLE `catalog`.orders``。
   这与 Spark 原生 SQL 的保留字行为一致，但需要在使用时留意。
2. **多权限授权不是原子的。** 规格没有批量授权端点，每条授权是一个独立请求。
   `GRANT a, b, c ON ...` 会顺序发出三次调用，中途失败时前面的已经生效；
   SQL 层不做伪原子化，失败直接抛出，由调用方按需补偿。
3. **`ALTER ... SET PROPERTIES` 是「读-合并-回写」。** 规格的 `PUT` 是整体替换 `properties`，
   而 SQL 的语义是增量，因此客户端先读当前值与 `entityVersion`、合并后再回写。
   并发修改下会有一次请求拿到 409，需要重试——这是乐观并发的正常表现，不是缺陷。
4. **每个会话一个 REST 客户端。** 见第 1 节的说明。
5. **权限取值的合法性由服务端判定。** SQL 层只做形状校验（例如对象级授权必须是
   `<namespace>.<对象名>`），不复制一份 64 项权限表——那只会多一处会漂移的副本。
   写错权限名会得到服务端的错误响应，而不是在解析期被拦下。
6. **令牌映射是服务端的静态配置。** 用 SQL 新建的主体不能立即取到可用的调用令牌，
   详见 `docs/authorization.md` 的「已知边界」。
