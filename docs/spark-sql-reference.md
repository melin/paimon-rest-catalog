# Spark SQL 管理语法参考

`paimon-rest-spark` 把 Paimon REST 的管理 API 接进 Spark SQL：主体（principal）、
角色（principal role / catalog role）与资源授权可以用 21 条 SQL 语句管理，
不必手写 HTTP 请求。

本文是**语句级参考**：每条语句的语法、参数、等价 REST 调用、结果集、错误与注意事项。
设计动机、语法扩展的实现方式、测试分层见 [`spark-sql-extension.md`](spark-sql-extension.md)；
管理 API 的端点与字段定义见 [`management-api-contract.md`](management-api-contract.md)。

```sql
CREATE PRINCIPAL IF NOT EXISTS svc_etl PROPERTIES ('owner' = 'data-platform');
CREATE PRINCIPAL ROLE etl_engineer;
CREATE CATALOG ROLE etl_reader IN CATALOG paimon;
GRANT PRINCIPAL ROLE etl_engineer TO PRINCIPAL svc_etl;
GRANT CATALOG ROLE etl_reader TO PRINCIPAL ROLE etl_engineer IN CATALOG paimon;
GRANT TABLE_READ_DATA ON TABLE default.orders IN CATALOG paimon TO CATALOG ROLE etl_reader;
SHOW GRANTS FOR CATALOG ROLE etl_reader IN CATALOG paimon;
```

---

## 1. 启用与配置

前置两件事：`paimon-rest-spark` 的 jar 在 Spark 的 classpath 上；管理服务可达。

### 1.1 配置方式

```bash
spark-sql \
  --conf spark.sql.extensions=io.github.melin.paimonrest.spark.ManagementSparkExtensions \
  --conf spark.paimon.rest.management.url=http://catalog-host:8080/api/management/v1 \
  --conf spark.paimon.rest.token=<执行这些语句的主体的令牌>
```

### 1.2 编程方式

```scala
val spark = PaimonRestManagement.install(SparkSession.builder())
  .config(PaimonRestManagement.MANAGEMENT_URL, "http://catalog-host:8080/api/management/v1")
  .config(PaimonRestManagement.TOKEN, token)
  .getOrCreate()
```

`install` 必须在 `getOrCreate()` 之前调用。

### 1.3 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `spark.paimon.rest.management.url` | `http://127.0.0.1:8080/api/management/v1` | 管理 API 基址，须含 `/api/management/v1` 前缀。末尾多余的 `/` 会被去掉 |
| `spark.paimon.rest.token` | 空 | 作为 `Authorization: Bearer <token>` 发出；为空时**不发送**该请求头 |
| `spark.paimon.rest.management.timeoutSeconds` | `30` | 单次请求超时秒数；非数字或非正数时退化为默认值，不会让会话启动失败 |

配置读取顺序是「会话 SQL 配置 → `SparkConf` → 默认值」，因此 `spark.conf.set(...)`、
`.config(...)` 与命令行 `--conf` 三种写法都有效。

> **令牌应当填「执行这些语句的主体」的令牌，而不是管理员令牌。**
> 管理服务按调用者身份判定权限，用管理员令牌会让所有 SQL 绕过权限判定，
> 也就失去了用 SQL 管理权限的意义。

> **配置必须在第一条管理语句之前设好。** 客户端在会话的第一条管理语句到来时创建、
> 之后整会话复用；之后再改 URL 或令牌不会生效，需要重建会话。

---

## 2. 语句总览

共 21 条语句。`[]` 表示可选，`|` 表示二选一。**每条语句对应一次或多次 REST 调用**，
下表列出全部映射。

| # | 语句 | REST 调用 |
| --- | --- | --- |
| 1 | `CREATE PRINCIPAL [IF NOT EXISTS] n [PROPERTIES (...)]` | `POST /principals` |
| 2 | `DROP PRINCIPAL [IF EXISTS] n` | `DELETE /principals/{n}` |
| 3 | `ALTER PRINCIPAL n SET PROPERTIES (...)` | `GET` + `PUT /principals/{n}` |
| 4 | `ALTER PRINCIPAL n UNSET PROPERTIES (...)` | `GET` + `PUT /principals/{n}` |
| 5 | `RESET PRINCIPAL n` | `POST /principals/{n}/reset` |
| 6 | `ROTATE PRINCIPAL n` | `POST /principals/{n}/rotate` |
| 7 | `CREATE PRINCIPAL ROLE [IF NOT EXISTS] n [PROPERTIES (...)]` | `POST /principal-roles` |
| 8 | `DROP PRINCIPAL ROLE [IF EXISTS] n` | `DELETE /principal-roles/{n}` |
| 9 | `ALTER PRINCIPAL ROLE n SET PROPERTIES (...)` | `GET` + `PUT /principal-roles/{n}` |
| 10 | `ALTER PRINCIPAL ROLE n UNSET PROPERTIES (...)` | `GET` + `PUT /principal-roles/{n}` |
| 11 | `CREATE CATALOG ROLE [IF NOT EXISTS] n IN CATALOG c [PROPERTIES (...)]` | `POST /catalogs/{c}/catalog-roles` |
| 12 | `DROP CATALOG ROLE [IF EXISTS] n IN CATALOG c` | `DELETE /catalogs/{c}/catalog-roles/{n}` |
| 13 | `ALTER CATALOG ROLE n IN CATALOG c SET PROPERTIES (...)` | `GET` + `PUT /catalogs/{c}/catalog-roles/{n}` |
| 14 | `ALTER CATALOG ROLE n IN CATALOG c UNSET PROPERTIES (...)` | `GET` + `PUT /catalogs/{c}/catalog-roles/{n}` |
| 15 | `GRANT PRINCIPAL ROLE r TO PRINCIPAL p` | `PUT /principals/{p}/principal-roles` |
| 16 | `REVOKE PRINCIPAL ROLE r FROM PRINCIPAL p` | `DELETE /principals/{p}/principal-roles/{r}` |
| 17 | `GRANT CATALOG ROLE r TO PRINCIPAL ROLE pr IN CATALOG c` | `PUT /principal-roles/{pr}/catalog-roles/{c}` |
| 18 | `REVOKE CATALOG ROLE r FROM PRINCIPAL ROLE pr IN CATALOG c` | `DELETE /principal-roles/{pr}/catalog-roles/{c}/{r}` |
| 19 | `GRANT <权限>[,...] ON <资源> TO CATALOG ROLE r` | `PUT /catalogs/{c}/catalog-roles/{r}/grants`，每项权限一次 |
| 20 | `REVOKE <权限>[,...] ON <资源> FROM CATALOG ROLE r` | `POST /catalogs/{c}/catalog-roles/{r}/grants`，每项权限一次 |
| 21 | `SHOW ...`（5 种形态） | 见第 9 节 |

两点容易看错的地方：

- **`REVOKE` 走 `POST /grants`，`GRANT` 走 `PUT /grants`。** 规格里撤销是
  `POST /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants`，两者都返回 `201`。
- **`ALTER` 是两次调用。** 规格的 `PUT` 整体替换 `properties`，而 SQL 的 `ALTER` 是增量语义，
  客户端先 `GET` 当前值与 `entityVersion`、合并后再 `PUT` 回写。

### 结果集形态

| 语句 | 结果 |
| --- | --- |
| `CREATE PRINCIPAL`、`RESET PRINCIPAL`、`ROTATE PRINCIPAL` | 1 行，3 列（含一次性明文密钥） |
| 5 种 `SHOW` 语句 | 0 行或多行，列定义见第 9 节 |
| 其余 13 条 | **0 行 0 列**，与 Spark 原生 `CREATE TABLE` 这类命令一致 |

---

## 3. 词法与标识符

### 3.1 大小写

关键字大小写不敏感（`create principal`、`Create Principal`、`CREATE PRINCIPAL` 等价）。
标识符（主体名、角色名、catalog 名）**保留大小写**，不做折叠。

### 3.2 标识符

| 形态 | 规则 | 示例 |
| --- | --- | --- |
| 裸标识符 | 首字符为字母或下划线，其后为字母、数字或下划线；不含 `-`、`.`、空格 | `svc_etl`、`reader_v2` |
| 反引号标识符 | 任意字符；反引号本身写成两个连续反引号 | `order-reader`、含点的对象名 |

反引号写法示例（表格里写不清楚，单独列出）：

```sql
CREATE PRINCIPAL `order-reader`;   -- 裸标识符不允许 -，用反引号包起来
SHOW CATALOG ROLES IN CATALOG `my.catalog`;
CREATE PRINCIPAL `a``b`;           -- 名称是 a`b：反引号写成两个反引号
```

### 3.3 关键字是保留字

管理关键字不能作为裸标识符使用。`CATALOG`、`CATALOGS`、`PRINCIPAL`、`PRINCIPALS`、
`ROLE`、`ROLES`、`TABLE`、`VIEW`、`POLICY`、`SEMANTIC`、`MODEL`、`NAMESPACE`、`SHOW`、
`GRANT`、`GRANTS`、`REVOKE`、`SET`、`UNSET`、`PROPERTIES`、`ON`、`IN`、`FOR`、`TO`、`FROM`、
`IF`、`NOT`、`EXISTS`、`ALTER`、`CREATE`、`DROP`、`RESET`、`ROTATE`
这 32 个词出现在名称位置时必须加反引号：

```sql
CREATE PRINCIPAL `table`;                           -- 裸写 TABLE 会解析失败
GRANT TABLE_READ_DATA ON TABLE `default`.`policy` IN CATALOG paimon TO CATALOG ROLE reader;
```

### 3.4 字面量与注释

| 元素 | 写法 |
| --- | --- |
| 字符串 | 单引号或双引号；内部同类引号写成两个：`'it''s'`、`"say ""hi"""` |
| 数字 | 可选负号 + 数字，可带小数：`2`、`-1`、`0.5` |
| 行注释 | `-- 到行尾` |
| 块注释 | `/* ... */` |
| 语句结尾 | 分号可选，允许重复（`;;`） |

一次调用只解析**一条**语句。把多条语句用 `;` 串在一个字符串里会失败——
这与 Spark 原生行为一致。

---

## 4. DDL：主体

主体是调用管理 API 的身份载体，创建时返回一次性的 `client_id` 与明文 `client_secret`。

### 4.1 `CREATE PRINCIPAL`

```sql
CREATE PRINCIPAL [IF NOT EXISTS] <name> [PROPERTIES (<key> = <value>, ...)]
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `<name>` | 是 | 主体名；已存在时返回 `409` |
| `IF NOT EXISTS` | 否 | 已存在时静默跳过（吞掉 `409`），不返回结果行 |
| `PROPERTIES` | 否 | 自定义属性键值对；键须为字符串字面量或标识符 |

**结果列**：`principal`、`client_id`、`client_secret`。属性不出现在结果里。

```sql
CREATE PRINCIPAL svc_etl PROPERTIES ('owner' = 'data-platform', 'tier' = silver);
```

```text
+-----------+---------------------+---------------------+
|principal  |client_id            |client_secret        |
+-----------+---------------------+---------------------+
|svc_etl    |3fcb2c8e-...         |rY8sK...             |
+-----------+---------------------+---------------------+
```

> **明文密钥只出现在这一行结果里。** 服务端不保存明文，错过这一行只能靠
> `ROTATE PRINCIPAL` 重新签发。

属性值可以只写键不写值，此时等价于设置成空串：

```sql
CREATE PRINCIPAL svc_etl PROPERTIES (owner);   -- owner = ""
```

### 4.2 `DROP PRINCIPAL`

```sql
DROP PRINCIPAL [IF EXISTS] <name>
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `<name>` | 是 | 主体名；不存在时返回 `404` |
| `IF EXISTS` | 否 | 不存在时静默跳过（吞掉 `404`） |

无结果行。删除主体不会自动撤销它持有的角色装配，需要先
`REVOKE PRINCIPAL ROLE ... FROM PRINCIPAL ...`。

### 4.3 `ALTER PRINCIPAL`

```sql
ALTER PRINCIPAL <name> SET   PROPERTIES (<key> = <value>, ...)
ALTER PRINCIPAL <name> UNSET PROPERTIES (<key>, ...)
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `SET PROPERTIES` | 二选一 | 增量设置：列出的键被写入或覆盖，未列出的键保持不变 |
| `UNSET PROPERTIES` | 二选一 | 增量移除：列出的键被删除，未列出的键保持不变 |

无结果行。`UNSET` 的列表中只写键名，不写 `= 值`。

```sql
ALTER PRINCIPAL svc_etl SET PROPERTIES ('tier' = 'gold');
ALTER PRINCIPAL svc_etl SET PROPERTIES ('owner' = 'risk');   -- tier 仍在
ALTER PRINCIPAL svc_etl UNSET PROPERTIES (tier);             -- owner 仍在
```

一次语句只能 `SET` 或 `UNSET`，不能同时写两者。

### 4.4 `RESET` / `ROTATE PRINCIPAL`

```sql
RESET  PRINCIPAL <name>
ROTATE PRINCIPAL <name>
```

| 语句 | 端点 | 结果列 |
| --- | --- | --- |
| `RESET PRINCIPAL` | `POST /principals/{name}/reset` | `principal`、`client_id`、`client_secret` |
| `ROTATE PRINCIPAL` | `POST /principals/{name}/rotate` | 同上 |

两条语句重新签发凭据并返回新的明文密钥，结果形态与 `CREATE PRINCIPAL` 相同。
规格未定义二者在语义上的差异（[待确认]），本实现把它们当作同义操作，
只对应不同的端点。

```sql
ROTATE PRINCIPAL svc_etl;
```

规格允许 `reset` 接受调用方指定的 `clientId` / `clientSecret`（是否接受由服务端配置决定），
**SQL 层不暴露这个能力**，始终请求服务端生成随机凭据。

---

## 5. DDL：principal role

principal role 是跨 catalog 的权限集合，通过「主体 → principal role → catalog role」两级装配
挂到具体资源上。

### 5.1 `CREATE PRINCIPAL ROLE`

```sql
CREATE PRINCIPAL ROLE [IF NOT EXISTS] <name> [PROPERTIES (<key> = <value>, ...)]
```

无结果行。`PROPERTIES` 的位置在名称之后。

```sql
CREATE PRINCIPAL ROLE etl_engineer PROPERTIES ('purpose' = 'etl');
```

### 5.2 `DROP PRINCIPAL ROLE`

```sql
DROP PRINCIPAL ROLE [IF EXISTS] <name>
```

无结果行。

### 5.3 `ALTER PRINCIPAL ROLE`

```sql
ALTER PRINCIPAL ROLE <name> SET   PROPERTIES (<key> = <value>, ...)
ALTER PRINCIPAL ROLE <name> UNSET PROPERTIES (<key>, ...)
```

无结果行。增量语义与 `ALTER PRINCIPAL` 相同。

---

## 6. DDL：catalog role

catalog role 归属于**具体 catalog**，承载该 catalog 内的资源授权，
因此每条语句都要写 `IN CATALOG <catalog>`。

### 6.1 `CREATE CATALOG ROLE`

```sql
CREATE CATALOG ROLE [IF NOT EXISTS] <name> IN CATALOG <catalog> [PROPERTIES (<key> = <value>, ...)]
```

无结果行。**注意 `PROPERTIES` 在 `IN CATALOG` 之后**：

```sql
CREATE CATALOG ROLE etl_reader IN CATALOG paimon PROPERTIES ('purpose' = 'read-only');
```

### 6.2 `DROP CATALOG ROLE`

```sql
DROP CATALOG ROLE [IF EXISTS] <name> IN CATALOG <catalog>
```

无结果行。

### 6.3 `ALTER CATALOG ROLE`

```sql
ALTER CATALOG ROLE <name> IN CATALOG <catalog> SET   PROPERTIES (<key> = <value>, ...)
ALTER CATALOG ROLE <name> IN CATALOG <catalog> UNSET PROPERTIES (<key>, ...)
```

无结果行。**`IN CATALOG` 在名称之后、`SET` / `UNSET` 之前。**

---

## 7. DCL：角色装配

装配是两级多对多关系，两条边各有自己的 `GRANT` / `REVOKE`。

### 7.1 主体 ↔ principal role

```sql
GRANT  PRINCIPAL ROLE <role> TO   PRINCIPAL <principal>
REVOKE PRINCIPAL ROLE <role> FROM PRINCIPAL <principal>
```

无结果行。重复授予同一条边是幂等的（服务端接受）。

```sql
GRANT PRINCIPAL ROLE etl_engineer TO PRINCIPAL svc_etl;
REVOKE PRINCIPAL ROLE etl_engineer FROM PRINCIPAL svc_etl;
```

### 7.2 principal role ↔ catalog role

```sql
GRANT  CATALOG ROLE <role> TO   PRINCIPAL ROLE <principalRole> IN CATALOG <catalog>
REVOKE CATALOG ROLE <role> FROM PRINCIPAL ROLE <principalRole> IN CATALOG <catalog>
```

无结果行。`IN CATALOG <catalog>` 必填——catalog role 只在它所属的 catalog 内有意义。

```sql
GRANT CATALOG ROLE etl_reader TO PRINCIPAL ROLE etl_engineer IN CATALOG paimon;
REVOKE CATALOG ROLE etl_reader FROM PRINCIPAL ROLE etl_engineer IN CATALOG paimon;
```

---

## 8. DCL：资源授权

资源授权把权限挂到 catalog role 上，是按资源层级细分的。

### 8.1 语法

```sql
GRANT  <权限>[, <权限>...] ON <资源> TO   CATALOG ROLE <role>
REVOKE <权限>[, <权限>...] ON <资源> FROM CATALOG ROLE <role>
```

无结果行。目标 catalog role 所属的 catalog **不单独书写**，而是从资源描述中取得——
`ON TABLE ns.t IN CATALOG paimon` 已经写明 catalog 是 `paimon`，避免同一语句里重复两次。

### 8.2 六种资源写法

| 资源写法 | `type` | 作用层级 |
| --- | --- | --- |
| `CATALOG <catalog>` | `catalog` | 整个 catalog |
| `NAMESPACE <ns>[.<ns>...] IN CATALOG <catalog>` | `namespace` | 一个命名空间 |
| `TABLE <ns>[.<ns>...].<对象名> IN CATALOG <catalog>` | `table` | 一张表 |
| `VIEW <ns>[.<ns>...].<对象名> IN CATALOG <catalog>` | `view` | 一个视图 |
| `POLICY <ns>[.<ns>...].<对象名> IN CATALOG <catalog>` | `policy` | 一条策略 |
| `SEMANTIC MODEL <ns>[.<ns>...].<对象名> IN CATALOG <catalog>` | `semantic-model` | 一个语义模型 |

```sql
GRANT CATALOG_MANAGE_CONTENT  ON CATALOG paimon              TO CATALOG ROLE reader;
GRANT NAMESPACE_LIST          ON NAMESPACE default           IN CATALOG paimon TO CATALOG ROLE reader;
GRANT TABLE_READ_DATA         ON TABLE default.orders        IN CATALOG paimon TO CATALOG ROLE reader;
GRANT TABLE_READ_DATA         ON TABLE raw.events.orders     IN CATALOG paimon TO CATALOG ROLE reader;
GRANT VIEW_READ_PROPERTIES    ON VIEW  default.v_orders      IN CATALOG paimon TO CATALOG ROLE reader;
GRANT POLICY_READ             ON POLICY default.mask_pii     IN CATALOG paimon TO CATALOG ROLE reader;
GRANT SEMANTIC_MODEL_READ     ON SEMANTIC MODEL default.revenue IN CATALOG paimon TO CATALOG ROLE reader;
```

`NAMESPACE` 可以是多级（`raw.events`）。对象级资源（`TABLE` / `VIEW` / `POLICY` /
`SEMANTIC MODEL`）**必须写成 `<namespace>.<对象名>`，至少两段**：命名空间是授权作用域的
一部分，单段名字无法确定作用域，解析期直接报错，不做「猜默认命名空间」的处理。

```text
ManagementSqlException: table grants require a qualified name of the form
<namespace>.<name>, got 'orders'
```

### 8.3 权限清单

权限按「在哪些层级可用」归组，共 64 项。同一项权限可在多个层级授予，
**`ON` 后面写哪种资源就按哪个层级的权限表校验**。

| 可用层级 | 项数 |
| --- | --- |
| catalog、namespace、table、view、policy、semantic model | 1 |
| catalog、namespace、table | 25 |
| catalog、namespace | 13 |
| catalog、namespace、view | 5 |
| catalog、namespace、policy | 5 |
| catalog、namespace、semantic model | 5 |
| catalog | 4 |
| namespace | 2 |
| table | 2 |
| policy | 2 |

**六个层级都可用**（1 项）：

`CATALOG_MANAGE_ACCESS`

**catalog、namespace、table 三层可用**（25 项）：

`TABLE_DROP`、`TABLE_LIST`、`TABLE_READ_PROPERTIES`、`TABLE_WRITE_PROPERTIES`、
`TABLE_READ_DATA`、`TABLE_WRITE_DATA`、`TABLE_FULL_METADATA`、`TABLE_ASSIGN_UUID`、
`TABLE_UPGRADE_FORMAT_VERSION`、`TABLE_ADD_SCHEMA`、`TABLE_SET_CURRENT_SCHEMA`、
`TABLE_ADD_PARTITION_SPEC`、`TABLE_REMOVE_PARTITION_SPECS`、`TABLE_ADD_SORT_ORDER`、
`TABLE_SET_DEFAULT_SORT_ORDER`、`TABLE_ADD_SNAPSHOT`、`TABLE_SET_SNAPSHOT_REF`、
`TABLE_REMOVE_SNAPSHOTS`、`TABLE_REMOVE_SNAPSHOT_REF`、`TABLE_SET_LOCATION`、
`TABLE_SET_PROPERTIES`、`TABLE_REMOVE_PROPERTIES`、`TABLE_SET_STATISTICS`、
`TABLE_REMOVE_STATISTICS`、`TABLE_MANAGE_STRUCTURE`

**catalog、namespace 两层可用**（13 项）：

`CATALOG_MANAGE_CONTENT`、`CATALOG_MANAGE_METADATA`、`NAMESPACE_CREATE`、`NAMESPACE_DROP`、
`NAMESPACE_LIST`、`NAMESPACE_READ_PROPERTIES`、`NAMESPACE_WRITE_PROPERTIES`、
`NAMESPACE_FULL_METADATA`、`TABLE_CREATE`、`VIEW_CREATE`、`POLICY_CREATE`、
`SEMANTIC_MODEL_CREATE`、`SEMANTIC_MODEL_LIST`

**catalog、namespace、view 三层可用**（5 项）：

`VIEW_DROP`、`VIEW_LIST`、`VIEW_READ_PROPERTIES`、`VIEW_WRITE_PROPERTIES`、`VIEW_FULL_METADATA`

**catalog、namespace、policy 三层可用**（5 项）：

`POLICY_READ`、`POLICY_WRITE`、`POLICY_DROP`、`POLICY_LIST`、`POLICY_FULL_METADATA`

**catalog、namespace、semantic model 三层可用**（5 项）：

`SEMANTIC_MODEL_READ`、`SEMANTIC_MODEL_WRITE`、`SEMANTIC_MODEL_DROP`、
`SEMANTIC_MODEL_FULL_METADATA`、`SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE`

**仅 catalog 层可用**（4 项）：

`CATALOG_READ_PROPERTIES`、`CATALOG_WRITE_PROPERTIES`、`CATALOG_ATTACH_POLICY`、`CATALOG_DETACH_POLICY`

**仅 namespace 层可用**（2 项）：

`NAMESPACE_ATTACH_POLICY`、`NAMESPACE_DETACH_POLICY`

**仅 table 层可用**（2 项）：

`TABLE_ATTACH_POLICY`、`TABLE_DETACH_POLICY`

**仅 policy 层可用**（2 项）：

`POLICY_ATTACH`、`POLICY_DETACH`

一份按资源层级（含重复）的完整枚举见
[`management-api-contract.md`](management-api-contract.md) 第 2 节。

### 8.4 一次授予多项权限

权限之间用逗号分隔，会**展开成多次授权请求**：

```sql
GRANT TABLE_READ_DATA, TABLE_LIST, TABLE_WRITE_DATA
  ON TABLE default.orders IN CATALOG paimon TO CATALOG ROLE etl_reader;
```

上例发出 3 次 `PUT .../grants`。规格没有批量授权端点，因此这件事**不是原子的**：
中途失败时前面的已经生效，SQL 层不做伪原子化，失败直接抛出，由调用方按需补偿。

### 8.5 撤销

```sql
REVOKE TABLE_LIST ON TABLE default.orders IN CATALOG paimon FROM CATALOG ROLE etl_reader;
```

撤销走 `POST .../grants`，请求体与 `GRANT` 相同（都带 `grant` 对象）。
规格为该端点提供 `cascade` 查询参数，**SQL 层不暴露它**，因此撤销始终是
规格的默认行为：只影响指定资源，不下沉到子资源。

---

## 9. SHOW

5 种 `SHOW` 形态，其中 3 种带 `FOR` 变体，结果列随之变化。

### 9.1 `SHOW CATALOGS`

```sql
SHOW CATALOGS
```

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string，非空 | catalog 名 |
| `type` | string | catalog 类型，如 `INTERNAL` |

### 9.2 `SHOW PRINCIPALS`

```sql
SHOW PRINCIPALS
SHOW PRINCIPALS FOR PRINCIPAL ROLE <principalRole>
```

| 形态 | 列 | 说明 |
| --- | --- | --- |
| 无 `FOR` | `name`、`client_id` | 列出全部主体 |
| `FOR PRINCIPAL ROLE` | `principal` | 列出持有该 principal role 的主体 |

### 9.3 `SHOW PRINCIPAL ROLES`

```sql
SHOW PRINCIPAL ROLES
SHOW PRINCIPAL ROLES FOR PRINCIPAL <principal>
```

| 形态 | 列 | 说明 |
| --- | --- | --- |
| 无 `FOR` | `name`、`federated` | 列出全部 principal role；`federated` 为 `true` / `false` / `null` |
| `FOR PRINCIPAL` | `principal_role` | 列出该主体持有的 principal role |

### 9.4 `SHOW CATALOG ROLES`

```sql
SHOW CATALOG ROLES IN CATALOG <catalog>
SHOW CATALOG ROLES FOR PRINCIPAL ROLE <principalRole> IN CATALOG <catalog>
```

| 形态 | 列 | 说明 |
| --- | --- | --- |
| 无 `FOR` | `name`、`catalog` | 列出该 catalog 下的全部 catalog role；`catalog` 列回显语句里的 catalog |
| `FOR PRINCIPAL ROLE` | `catalog_role` | 列出该 principal role 在该 catalog 下持有的 catalog role |

### 9.5 `SHOW GRANTS`

```sql
SHOW GRANTS FOR CATALOG ROLE <catalogRole> IN CATALOG <catalog>
```

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `catalog` | string，非空 | catalog 名 |
| `catalog_role` | string，非空 | catalog role 名 |
| `type` | string，非空 | 资源类型：`catalog` / `namespace` / `table` / `view` / `policy` / `semantic-model` |
| `resource` | string | 拼好的完整资源名，便于直接读 |
| `namespace` | string | 多级命名空间用点号连接；catalog 级为 `null` |
| `object_name` | string | 对象名；catalog 级与 namespace 级为 `null` |
| `privilege` | string，非空 | 权限取值 |

`resource` 列的拼法：

| 授权类型 | `type` | `resource` | `namespace` | `object_name` |
| --- | --- | --- | --- | --- |
| catalog 级 | `catalog` | `paimon` | `null` | `null` |
| namespace 级 | `namespace` | `raw.events` | `raw.events` | `null` |
| 表级 | `table` | `raw.events.orders` | `raw.events` | `orders` |

```text
+--------+-------------+------------+--------------------+------------+-------------+------------------+
|catalog |catalog_role |type        |resource            |namespace   |object_name  |privilege         |
+--------+-------------+------------+--------------------+------------+-------------+------------------+
|paimon  |etl_reader   |catalog     |paimon              |null        |null         |CATALOG_MANAGE... |
|paimon  |etl_reader   |namespace   |raw.events          |raw.events  |null         |NAMESPACE_LIST    |
|paimon  |etl_reader   |table       |raw.events.orders   |raw.events  |orders       |TABLE_READ_DATA   |
+--------+-------------+------------+--------------------+------------+-------------+------------------+
```

---

## 10. 错误处理

### 10.1 两类失败

| 阶段 | 异常 | 触发条件 |
| --- | --- | --- |
| 解析 | `ManagementSqlException` | 语句是管理语句，但形状非法（如对象级授权缺命名空间）。**不发起任何请求** |
| 解析 | `org.apache.spark.sql.catalyst.parser.ParseException` | 语句以管理关键字开头但语法不完整（如 `CREATE PRINCIPAL`）。由 Spark 原生解析器给出信息 |
| 执行 | `ManagementApiException` | 服务端返回非预期状态码，或网络不可达 |

### 10.2 `ManagementApiException` 的判定方法

| 方法 | 含义 |
| --- | --- |
| `status()` | HTTP 状态码；网络层失败（连不上、超时）时为 `0` |
| `method()` / `path()` | 失败请求的方法与路径 |
| `notFound()` | `status == 404` |
| `alreadyExists()` | `status == 409` |
| `forbidden()` | `status == 403` 或 `401` |
| `resourceType()` / `resourceName()` | 服务端 `ErrorResponse` 的 `type` / `name`，可能为 `null` |
| `describe()` | 拼好的可读描述：状态码 + 方法 + 路径 + 服务端消息 |
| `getMessage()` | 服务端 `ErrorResponse` 的 `message`；无法解析时是原始响应文本 |

### 10.3 `IF EXISTS` / `IF NOT EXISTS` 只吞一种错误

`IF EXISTS` 只吞 `404`，`IF NOT EXISTS` 只吞 `409`。**`403` 不会被吞掉**：

```python
# 无权限时即使写了 IF NOT EXISTS 也会抛异常
spark.sql("CREATE PRINCIPAL IF NOT EXISTS svc_etl").collect()
# ManagementApiException: 403 ... svc_etl is not allowed to create principals
```

这是刻意的：把权限不足当成「已存在」会让权限配置错误被静默掩盖。

### 10.4 常见状态码

| 状态码 | 典型原因 |
| --- | --- |
| `403` / `401` | 调用者令牌对应的主体没有管理该类资源的权限 |
| `404` | 目标主体 / 角色 / catalog 不存在；`DROP` 时未加 `IF EXISTS` |
| `409` | 名称已存在；或 `ALTER` 的 `entityVersion` 不匹配（并发修改） |
| `0` | 管理服务不可达：URL 写错、服务未启动、网络不通 |

---

## 11. 完整示例

下面这段按顺序执行即可跑通全部 21 条语句，可直接粘贴进 `spark-sql`。
前置：catalog `paimon` 存在（服务端默认配置会预置）。

<!-- doc-example:begin -->
```sql
-- ① 主体：创建、改属性、查、重置、轮换、删除
DROP PRINCIPAL IF EXISTS doc_demo_etl;
CREATE PRINCIPAL doc_demo_etl PROPERTIES ('owner' = 'data-platform', 'tier' = 'silver');
ALTER PRINCIPAL doc_demo_etl SET PROPERTIES ('tier' = 'gold');
ALTER PRINCIPAL doc_demo_etl UNSET PROPERTIES ('tier');
SHOW PRINCIPALS;
RESET PRINCIPAL doc_demo_etl;
ROTATE PRINCIPAL doc_demo_etl;

-- ② 角色：principal role 与 catalog role
CREATE PRINCIPAL ROLE IF NOT EXISTS doc_demo_engineer PROPERTIES ('purpose' = 'docs');
CREATE CATALOG ROLE IF NOT EXISTS doc_demo_reader IN CATALOG paimon PROPERTIES ('purpose' = 'docs');
ALTER PRINCIPAL ROLE doc_demo_engineer SET PROPERTIES ('tier' = 'gold');
ALTER PRINCIPAL ROLE doc_demo_engineer UNSET PROPERTIES (tier);
ALTER CATALOG ROLE doc_demo_reader IN CATALOG paimon SET PROPERTIES ('tier' = 'gold');
ALTER CATALOG ROLE doc_demo_reader IN CATALOG paimon UNSET PROPERTIES (tier);
SHOW PRINCIPAL ROLES;
SHOW CATALOG ROLES IN CATALOG paimon;

-- ③ 装配：主体 → principal role → catalog role
GRANT PRINCIPAL ROLE doc_demo_engineer TO PRINCIPAL doc_demo_etl;
GRANT CATALOG ROLE doc_demo_reader TO PRINCIPAL ROLE doc_demo_engineer IN CATALOG paimon;
SHOW PRINCIPAL ROLES FOR PRINCIPAL doc_demo_etl;
SHOW PRINCIPALS FOR PRINCIPAL ROLE doc_demo_engineer;
SHOW CATALOG ROLES FOR PRINCIPAL ROLE doc_demo_engineer IN CATALOG paimon;

-- ④ 资源授权：三个层级 + 多权限展开 + 撤销
GRANT CATALOG_READ_PROPERTIES ON CATALOG paimon TO CATALOG ROLE doc_demo_reader;
GRANT NAMESPACE_LIST ON NAMESPACE default IN CATALOG paimon TO CATALOG ROLE doc_demo_reader;
GRANT TABLE_READ_DATA, TABLE_LIST ON TABLE default.orders IN CATALOG paimon TO CATALOG ROLE doc_demo_reader;
SHOW GRANTS FOR CATALOG ROLE doc_demo_reader IN CATALOG paimon;
REVOKE TABLE_LIST ON TABLE default.orders IN CATALOG paimon FROM CATALOG ROLE doc_demo_reader;
SHOW GRANTS FOR CATALOG ROLE doc_demo_reader IN CATALOG paimon;

-- ⑤ 清理：先撤装配，再删角色与主体
REVOKE CATALOG ROLE doc_demo_reader FROM PRINCIPAL ROLE doc_demo_engineer IN CATALOG paimon;
REVOKE PRINCIPAL ROLE doc_demo_engineer FROM PRINCIPAL doc_demo_etl;
DROP CATALOG ROLE doc_demo_reader IN CATALOG paimon;
DROP PRINCIPAL ROLE doc_demo_engineer;
DROP PRINCIPAL doc_demo_etl;
SHOW CATALOGS;
```
<!-- doc-example:end -->

按 step 取用：

```sql
-- 用 SQL 授予只读权限
CREATE CATALOG ROLE IF NOT EXISTS analyst_reader IN CATALOG paimon;
GRANT NAMESPACE_LIST ON NAMESPACE sales IN CATALOG paimon TO CATALOG ROLE analyst_reader;
GRANT TABLE_READ_DATA ON TABLE sales.orders IN CATALOG paimon TO CATALOG ROLE analyst_reader;
GRANT CATALOG ROLE analyst_reader TO PRINCIPAL ROLE analyst IN CATALOG paimon;
GRANT PRINCIPAL ROLE analyst TO PRINCIPAL svc_analyst;

-- 审计某个角色现在有什么
SHOW GRANTS FOR CATALOG ROLE analyst_reader IN CATALOG paimon;

-- 收权
REVOKE TABLE_READ_DATA ON TABLE sales.orders IN CATALOG paimon FROM CATALOG ROLE analyst_reader;
```

Python 取值：

```python
row = spark.sql("CREATE PRINCIPAL svc_etl").collect()[0]
print(row["principal"], row["client_id"], row["client_secret"])

# 写类语句返回空列表，与 Spark 原生 DDL 一致
assert spark.sql("CREATE PRINCIPAL ROLE data_engineer").collect() == []

spark.sql("SHOW GRANTS FOR CATALOG ROLE analyst_reader IN CATALOG paimon").show(truncate=False)
```

---

## 12. 已知边界

1. **管理关键字在名称位置必须加反引号。** 见第 3.3 节的完整保留字清单。
   这与 Spark 原生 SQL 的保留字行为一致，但需要在使用时留意。
2. **`ALTER ... SET PROPERTIES` 是「读-合并-回写」。** 规格的 `PUT` 整体替换属性，
   而 SQL 语义是增量，因此客户端先读当前值与 `entityVersion`、合并后再回写。
   并发修改下会有一次请求拿到 `409`，需要重试——这是乐观并发的正常表现，不是缺陷。
3. **多权限授权不是原子的。** 见第 8.4 节。
4. **`REVOKE` 不支持 `cascade`。** 见第 8.5 节；撤销不下沉到子资源。
5. **`RESET` 不支持调用方指定凭据。** 见第 4.4 节；始终由服务端生成随机凭据。
6. **权限取值的合法性由服务端判定。** SQL 层只做形状校验（例如对象级授权必须是
   `<namespace>.<对象名>`），不复制一份 64 项权限表——那只会多一处会漂移的副本。
   写错权限名会得到服务端的错误响应，而不是在解析期被拦下。
7. **每个会话一个 REST 客户端。** 配置必须在第一条管理语句之前设好，见第 1.3 节。
8. **一次调用一条语句。** 不支持在一个字符串里用 `;` 串联多条。
9. **令牌映射是服务端的静态配置。** 用 SQL 新建的主体不能立即取到可用的调用令牌，
   详见 [`authorization.md`](authorization.md) 的「已知边界」。
10. **`IF EXISTS` / `IF NOT EXISTS` 不吞 `403`。** 见第 10.3 节。
