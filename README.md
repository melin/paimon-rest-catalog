# Paimon Rest Catalog Server（Spring Boot + JPA）

使用 Spring Boot 与 JPA 实现 Apache Paimon 的 **REST Catalog API**（OpenAPI 规格 v1）。
服务扮演目录（catalog）角色：集中保存 database、表 schema、快照、分支、标签、分区、
视图与函数的元数据，供 Flink / Spark 等支持 Paimon Rest Catalog 协议的引擎通过 HTTP 读写。

同时实现了 Apache Polaris 的 **REST Management API**（`/api/management/v1`）：
主体、principal role、catalog role 与资源授权的完整 RBAC 管理面，
既是管理接口，也是 catalog API 上权限判定的依据。

- 规格来源：
  - Catalog API：[`docs/static/rest-catalog-open-api.yaml`](https://github.com/apache/paimon/blob/master/docs/static/rest-catalog-open-api.yaml)
    （随仓库副本：`spec/rest-catalog-open-api.yaml`，运行时也可从 `/rest-catalog-open-api.yaml` 下载）
  - Management API：`spec/polaris-management-service.yml`（随仓库副本，运行时可从
    `/polaris-management-service.yml` 下载）
- 规格规模：Catalog API **60 个端点、144 个 schema**
  （端点清单见 [`docs/catalog-api-endpoints.md`](docs/catalog-api-endpoints.md)）；
  Management API **33 个 operation、17 条路径**
  （概览见 [`docs/management-api-overview.md`](docs/management-api-overview.md)）
- 元数据存储：**MySQL 8.0**（建表语句由实体生成，见 `sql/schema-mysql.sql`；
  18 张表的划分与设计取舍见 [`docs/data-model.md`](docs/data-model.md)），
  通过 JPA 访问；另有 H2 内存库与 PostgreSQL 两个 profile 可选
- 模块：服务端（Spring Boot）+ Spark SQL 语法扩展（Scala）

---

## 1. 工程结构

```
paimon-rest/
├── Dockerfile               服务端镜像（多阶段构建，见第 8 节）
├── pom.xml                  聚合 POM（无父 POM，见「模块划分的取舍」）
├── paimon-rest-server/      Spring Boot 服务端：Catalog API + Management API
├── paimon-rest-console/     Web 管理控制台（Vue 3 + Vite，产物构建进服务端，见第 9 节）
├── paimon-rest-spark/       Spark SQL 语法扩展：用 SQL 管理主体、角色与授权
├── deploy/kubernetes/       Kubernetes 部署清单（kustomize 组装，见其目录下的 README）
├── examples/                可运行的示例：Spark 经 Paimon Rest Catalog 建表
├── spec/                    OpenAPI 规格副本
├── sql/                     MySQL 建表语句（由实体生成，见第 7 节「代码生成」）
├── docs/                    设计文档、契约基线与 SQL 语法参考
└── scripts/                 验收脚本、代码生成脚本与文档校验脚本
```

模块划分的取舍：聚合 POM **不继承** `spring-boot-starter-parent`。因为
`paimon-rest-spark` 依赖 Spark，而 Spring Boot 的 `dependencyManagement` 会把 Spark 自带的
Jackson 2.15.2 抬到 2.21.x、并替换 netty 与 jersey，导致 Spark 侧出现难以定位的版本冲突。
因此聚合 POM 只钉核心插件版本，由 `paimon-rest-server` 自己以 `<relativePath/>` 继承
Spring Boot 父 POM。

两个模块的依赖世界因此互不污染：服务端是 Spring Boot 4 / Jackson 3 / Hibernate 7，
Spark 侧是 Scala 2.12 / Spark 3.5 / Jackson 2 / ANTLR 4.9.3。

Maven 坐标与包名：`groupId` 为 `io.github.melin`，三个 artifact 分别是
`paimon-rest-parent`（聚合）、`paimon-rest-server`、`paimon-rest-spark`；
源码包前缀统一为 `io.github.melin.paimonrest`，Spark 模块在其下再用 `.spark`
子包（如 `io.github.melin.paimonrest.spark.ManagementSparkExtensions`）——
这个全限定名就是要填进 `spark.sql.extensions` 的值，改动它等于破坏所有既有部署，
因此与 groupId 一起固定下来。

`docs/` 里十二份文档的分工：

| 文档 | 读它的时机 |
| --- | --- |
| [`catalog-api-endpoints.md`](docs/catalog-api-endpoints.md) | 要查 Catalog API 有哪些端点——60 个端点按资源分组，含基址、前缀与授权映射说明 |
| [`management-api-overview.md`](docs/management-api-overview.md) | 要读 Management API——33 个 operation 落在哪几个控制器上、几处容易读错的语义 |
| [`management-api-contract.md`](docs/management-api-contract.md) | 要查管理 API 的端点、字段、权限枚举——由规格生成，逐字对照 |
| [`data-model.md`](docs/data-model.md) | 要改实体或看表结构——18 张表的划分、摘要列与审计列的设计理由 |
| [`spark-sql-reference.md`](docs/spark-sql-reference.md) | 要用 SQL 管理主体与授权——语句级参考，含完整示例 |
| [`spark-sql-extension.md`](docs/spark-sql-extension.md) | 要改这个 Spark 扩展——语法扩展的做法、两个必须知道的约束、测试分层 |
| [`spark-paimon-rest-e2e.md`](docs/spark-paimon-rest-e2e.md) | 要让 Spark 通过本服务端建 Paimon 表——连接配置、可执行的 SQL、报错对照表 |
| [`authorization.md`](docs/authorization.md) | 要理解权限怎么判——实体链、授权映射、失败关闭的取舍 |
| [`console.md`](docs/console.md) | 要改 Web 控制台或排查「页面空列表」——前端分层、端点契约表、机械校验 |
| [`console-table-detail.md`](docs/console-table-detail.md) | 要改表详情页——九个页签的数据来源、权限页签的授权模型、破坏性操作与边界 |
| [`console-auth.md`](docs/console-auth.md) | 要给浏览器访问控制台配登录——三种登录方式、两个开关的边界、令牌与认证链、安全考量 |
| [`polaris-capabilities-and-design.md`](docs/polaris-capabilities-and-design.md) | 要对比 Polaris 或看整体设计——核心能力提取、映射关系、已知缺口 |

---

## 2. 快速开始

前置条件：**JDK 17 或 21**；运行服务端需要一个 MySQL 8.0 实例。

> **构建与测试必须用 17 或 21，不能用 24 及以上。** Spring Boot 4 只要求 17+，
> 但 Spark 3.5 建测试会话时要经过 Hadoop 的 `UserGroupInformation.getCurrentUser()`，
> 而它依赖的 `Subject.getSubject` 自 JDK 24 起被永久移除（JEP 486，永久关闭 Security
> Manager）：每个建会话的用例都会各报一次
> `UnsupportedOperationException: getSubject is not supported`，堆栈指向 Hadoop 内部，
> 与本仓库代码无关。**这不是配置问题**——`-Djava.security.manager=allow` 在 JDK 24+
> 会让 JVM 启动阶段直接失败，没有任何参数能绕过。
> `-DskipTests` 打包不受影响；跑测试必须显式指定 JDK，例如
> `JAVA_HOME=/path/to/jdk-21 ./mvnw test`。测试侧由 `SparkJdkRequirement` 在建会话前
> 拦下这一情况并直接给出结论，不再让它以十几条 Hadoop 堆栈的形式出现。

### 2.1 准备数据库

元数据默认存放在 MySQL。首次部署先建库建表：

```bash
mysql -h 127.0.0.1 -u root -p < sql/schema-mysql.sql
```

`sql/schema-mysql.sql` 由 `scripts/gen-mysql-ddl.sh` 从 JPA 实体生成（18 张表、19 个唯一约束、
15 个索引），**不要手工修改**——下次生成会覆盖。它会建出 `paimon_catalog` 库并切过去，
库名与 `application.yml` 里的 `spring.datasource.url` 必须一致（用 `DB_NAME=... ` 重新生成可改）。

连接信息默认是 `127.0.0.1:3306`、库 `paimon_catalog`、账号 `paimon/paimon`，
可用 `SPRING_DATASOURCE_URL` / `--spring.datasource.username` 等覆盖。

启动时的 `ddl-auto` 是 **`validate`**：库结构与实体不一致会直接启动失败，
而不是悄悄改表。所以升级代码后如果实体有变，需要重新生成 DDL 并自行迁移。

### 2.2 构建与运行

```bash
# 全量构建
JAVA_HOME=/path/to/jdk-21 ./mvnw -DskipTests install

# 运行全部测试（服务端 258 + Spark 76，共 334 个用例）
JAVA_HOME=/path/to/jdk-21 ./mvnw test

# 启动服务端（默认 8080 端口，连 MySQL，预置 catalog prefix=paimon 与 database=default）
JAVA_HOME=/path/to/jdk-21 ./mvnw -pl paimon-rest-server spring-boot:run

# 打包后直接运行
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar
```

只需一个即用即丢、不依赖 MySQL 的实例时，用内存 H2：

```bash
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=h2
```

冒烟验证：

```bash
# 1) 目录发现，拿到 prefix
curl -s "http://localhost:8080/v1/config?warehouse=paimon"
# {"defaults":{"warehouse":"file:///tmp/paimon-warehouse","prefix":"paimon"},"overrides":{}}

# 2) 建库
curl -s -X POST http://localhost:8080/v1/paimon/databases \
  -H 'Content-Type: application/json' \
  -d '{"name":"sales","options":{"owner":"paimon"}}'

# 3) 建表
curl -s -X POST http://localhost:8080/v1/paimon/databases/sales/tables \
  -H 'Content-Type: application/json' \
  -d '{"identifier":{"database":"sales","object":"orders"},
       "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT NOT NULL"},
                           {"id":1,"name":"amount","type":"DECIMAL(10, 2)"},
                           {"id":2,"name":"dt","type":"VARCHAR(10)"}],
                 "primaryKeys":["id"],"partitionKeys":["dt"],
                 "options":{"bucket":"2"},"comment":"订单表"}}'

# 4) 查询
curl -s http://localhost:8080/v1/paimon/databases/sales/tables/orders

# 5) 管理面：列出现有 catalog（开启授权后需要 Bearer 令牌）
curl -s http://localhost:8080/api/management/v1/catalogs -H 'Authorization: Bearer root'
```

### 2.2.1 直接看界面

上面的操作都有对应的界面，不用写 curl：服务端自带 Web 管理控制台，
构建产物已经提交在仓库里，**启动后就能用，不需要 Node**。

```bash
open http://localhost:8080/console/
```

它覆盖 Catalog API（库表视图函数、快照标签分支、数据令牌与查询授权）与
Management API（catalog、主体、服务角色、catalog 角色与 grants）两个 API 面，
首页还有一个「连接自检」，逐个探测控制台依赖的端点——地址或令牌配错时，
它会把失败的那一个连同 HTTP 状态一起列出来，而不是让你对着空列表猜。

控制台**默认就要求登录**（`paimon.rest.auth.console.required=true`），
默认账号密码 `admin/admin` —— 装好即可进入，不需要先建主体。
登录方式有三种可同时启用（用户名密码 / 主体凭据 / 外部 IdP 的 SSO），
也可以直接在「连接设置」里填静态令牌。**登录页只列出服务端真的开启了的方式**：
关掉的、没配令牌的都不显示，一个都没开时页面会说明该开哪个配置项。
完整说明见第 9 节与
[`docs/console.md`](docs/console.md)（控制台总览）、
[`docs/console-table-detail.md`](docs/console-table-detail.md)（表详情页）、
[`docs/console-auth.md`](docs/console-auth.md)（登录与鉴权）。

> **这层门禁只挡住界面。** 默认 `paimon.rest.auth.enabled=false`，
> 因此 `curl /v1/config` 与管理 API 仍然匿名可调。要保护数据必须把 `auth.enabled`
> 设为 `true`；服务端在「门禁开着而鉴权关着」时会在启动日志里告警。

### 2.3 数据源 profile

| profile | 数据源 | 建表方式 | 用途 |
| --- | --- | --- | --- |
| （默认） | MySQL 8.0 | `sql/schema-mysql.sql` + `ddl-auto: validate` | 正常部署 |
| `h2` | 内存 H2 | `ddl-auto: update` | 本地调试、端到端脚本 |
| `postgresql` | PostgreSQL | `ddl-auto: update` | 备用；**未做端到端验证** |

```bash
./mvnw -pl paimon-rest-server spring-boot:run -Dspring-boot.run.profiles=h2
./mvnw -pl paimon-rest-server spring-boot:run -Dspring-boot.run.profiles=postgresql
```

---

## 3. 架构

```
paimon-rest-server/
  web/          控制器层：Catalog API 按规格路径一一对应；Management API 另设 4 个控制器
  service/      业务层：目录解析、schema 变更、快照与版本、分区分支标签、RBAC 判定
  domain/       JPA 实体与仓储：元数据与授权关系持久化
  dto/          请求/响应模型：与两份 OpenAPI 规格的 schema 一一对应
  support/      公共设施：JSON 编解码、schema 字段树、分页、路径、错误
  config/       服务端配置、Bearer 鉴权、授权拦截器、启动初始化

paimon-rest-spark/
  antlr4/…/ManagementSql.g4   管理语句语法（21 条语句）
  client/                     REST 客户端（JDK HttpClient + Jackson 2）
  …/ManagementCommands.scala  21 个 RunnableCommand：语句 → HTTP 调用
  …/ManagementAstBuilder.scala 语法树 → 命令
  …/PaimonRestSqlParser.scala  注入 Spark 的解析器：先试管理语法，失败即交回
```

关键设计：

- **前缀路由**：`{prefix}` 是 catalog 的对外标识，由 `GET /v1/config` 下发。未登记的 prefix
  在 `paimon.rest.auto-create-catalog=true`（默认）时自动登记，客户端可零配置接入。
- **认证与授权分离**：`BearerAuthInterceptor` 只回答「调用者是谁」，RBAC 判定由授权层完成。
  两者分别由 `paimon.rest.auth.*` 与 `paimon.rest.authorization.*` 控制，可以只开认证不开授权。
- **catalog API 的授权映射集中在一张表**：`CatalogAccessRules` 把 `/v1/**` 路径映射到所需权限，
  **未登记映射的端点直接拒绝而不是放行**；测试从运行时的请求映射里枚举全部端点核对覆盖率，
  新增端点若忘记登记会让构建失败。详见 `docs/authorization.md`。
- **JSON 文档列**：schema、函数定义、分区 spec 等结构以 JSON 文本存单列，实体字段保持强类型，
  避免为每个属性建表；复杂结构通过 `AttributeConverter` 转换。
- **多态变更分派**：规格中 `SchemaChange` / `ViewChange` / `FunctionChange` 是以 `action`
  判别的多态联合，统一按 `List<Map<String,Object>>` 接收后在服务层按 `action` 分派，
  不把多态解析绑死在具体 JSON 库上。
- **管理语句靠语法而非前缀识别**：Spark 3.5 的 SQL 语法没有兜底分支，无法用 visitor 拦截
  新关键字，因此 Spark 子模块自带一份只描述管理语句的语法，解析成功才接管，失败原样交回。

两份 API 与元数据的清单不在本文展开：Catalog API 的
[60 个端点](docs/catalog-api-endpoints.md)、Management API 的
[33 个 operation 概览](docs/management-api-overview.md)（逐字契约见
[`management-api-contract.md`](docs/management-api-contract.md)）、以及
[18 张表的数据模型与设计取舍](docs/data-model.md)。

---

## 4. Spark SQL 扩展

`paimon-rest-spark` 把管理 API 接进 Spark SQL，21 条语句覆盖主体、角色、授权与查询：

```sql
CREATE PRINCIPAL svc_etl PROPERTIES ('owner' = 'data-platform');
CREATE CATALOG ROLE reader IN CATALOG paimon;
GRANT CATALOG ROLE reader TO PRINCIPAL ROLE etl_reader IN CATALOG paimon;
GRANT TABLE_READ_DATA ON TABLE default.orders IN CATALOG paimon TO CATALOG ROLE reader;
SHOW GRANTS FOR CATALOG ROLE reader IN CATALOG paimon;
```

启用方式、逐条语句的语法 / 参数 / 结果列 / 错误处理见
[`docs/spark-sql-reference.md`](docs/spark-sql-reference.md)（语句级参考）；
实现动机、语法扩展的做法与测试分层见
[`docs/spark-sql-extension.md`](docs/spark-sql-extension.md)。

参考文档里的内容不是手写断言，而是有机械校验兜底：`scripts/verify-spark-sql-doc.py`
比对文档与语法文件、规格、客户端代码，参考文档第 11 节的示例还会被 `SparkSqlDocExamplesTests`
对着真实服务端执行一遍——**改坏示例会让构建失败**。

两个必须知道的约束：

- **ANTLR 版本必须与目标 Spark 发行版一致。** Spark 3.5.9 自带 `antlr4-runtime` 4.9.3，
  其 `SqlBaseLexer` 就是用 4.9.3 生成的。若本模块把运行时抬到 4.13.x，会把 Spark 自己的
  运行时顶掉，导致**整个会话的 SQL 解析**（包括 `SELECT 1`）在类初始化阶段失败。
  升级 `spark.version` 时必须同步核对根 `pom.xml` 里的 `antlr.runtime.version`。
- **`spark.sql.extensions` 的扩展点只能包装、不能改写语法。** Spark 3.5 的 `SqlBase.g4`
  没有兜底分支，因此本模块自带一份管理语句语法，先试自己的解析器，失败即把语句原样交回
  原生解析器——判断依据是「能否整句匹配到 EOF」，不是字符串前缀。

---

## 5. 配置项

Catalog 侧与鉴权：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `paimon.rest.default-prefix` | `paimon` | `GET /v1/config` 未指定 warehouse 时的 prefix |
| `paimon.rest.default-warehouse` | `file:///tmp/paimon-warehouse` | 默认仓库位置 |
| `paimon.rest.auto-create-catalog` | `true` | 未登记 prefix 是否自动登记 |
| `paimon.rest.path-template` | `{warehouse}/{database}.db/{table}` | 表路径模板 |
| `paimon.rest.default-page-size` | `100` | 默认分页大小 |
| `paimon.rest.max-page-size` | `1000` | 分页大小上限 |
| `paimon.rest.auth.enabled` | `false` | 是否要求数据接口带 Bearer 令牌（`/v1/**`、`/api/catalog/v1/**`、`/api/management/v1/**`） |
| `paimon.rest.auth.principal` | `anonymous` | 认证关闭或未带令牌时使用的主体名 |
| `paimon.rest.auth.tokens` | 空 | 允许的静态令牌列表（给机器用）；**配置了才会在登录页出现「静态令牌」页签** |
| `paimon.rest.auth.token-principals` | 空 | 静态令牌 → 主体名映射；未登记时退化为「令牌即主体名」（审计列放不下的长度会记摘要，见第 6 节第 19 条） |
| `paimon.rest.auth.access-token.ttl` | `1h` | 控制台签发的访问令牌有效期（唯一能限制令牌泄露窗口的参数） |
| `paimon.rest.auth.access-token.signing-key` | 空 | HS256 签名密钥，Base64 且解码后 ≥32 字节；**留空则每次启动随机生成**（多实例互不认、重启掉线），生产必须配 |
| `paimon.rest.auth.console.required` | `true` | 浏览器进控制台是否必须先登录。**只作用于控制台界面与 `/api/console/v1/**`，不是安全边界** |
| `paimon.rest.auth.console.password.enabled` | `true` | 允许用配置里的用户名 + 密码换访问令牌 |
| `paimon.rest.auth.console.password.users` | `admin: admin` | 控制台账号表（明文，可写 `${ENV_VAR}`）；**生产必须改**，启动日志会告警 |
| `paimon.rest.auth.console.password.principals` | 空 | 账号名 → 主体名映射；未登记时两者同名 |
| `paimon.rest.auth.console.password.rate-limit.max-failures` / `.window` | `5` / `1m` | 该方式的失败限速（比客户端凭据更严：猜的是人定的密码） |
| `paimon.rest.auth.console.client-credentials.enabled` | `true` | 允许用主体 `clientId` / `clientSecret` 换访问令牌 |
| `paimon.rest.auth.console.client-credentials.rate-limit.max-failures` / `.window` | `10` / `1m` | 该方式的失败限速 |
| `paimon.rest.auth.console.oidc.*` | 关闭 | 外部身份提供方（OIDC + PKCE）登录，见 [`console-auth.md`](docs/console-auth.md) |
| `paimon.rest.credential.ttl-seconds` | `3600` | 数据访问令牌有效期 |

管理面授权（RBAC）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `paimon.rest.authorization.enabled` | `false` | 是否对 `/api/management/v1/**` 与 `/v1/**` 启用权限判定 |
| `paimon.rest.authorization.service-admins` | `[root]` | 服务管理员主体名单：可管理主体与 principal role，并可在任意 catalog 上操作角色与授权 |
| `paimon.rest.authorization.bootstrap-principal` | `root` | 启动时预置的引导主体；留空则不预置 |
| `paimon.rest.authorization.bootstrap-principal-role` | `service_admin` | 启动时预置的引导角色链 |

管理面启动时会预置：引导主体、引导 principal role，以及各 catalog 的 `catalog_admin` 角色。
没有这条引导链，全新部署里没有任何主体能创建第一个主体。

存储与凭据，对应 Polaris 1.8.0 配置参考的
[Storage & Credentials](https://polaris.apache.org/releases/1.8.0/configuration/configuration-reference/#storage--credentials)
一节，键名逐项对应，只把前缀从 `polaris` 换成 `paimon.rest`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `paimon.rest.storage.aws.access-key` | 空 | S3 默认凭据；留空则退化为环境凭据链 |
| `paimon.rest.storage.aws.secret-key` | 空 | 同上 |
| `paimon.rest.storage.aws.storages.<name>.access-key` | 空 | 具名存储，键为 catalog 的 `storageConfigInfo.storageName` |
| `paimon.rest.storage.aws.storages.<name>.secret-key` | 空 | 同上 |
| `paimon.rest.storage.gcp.token` | 空 | GCS 访问令牌；留空则交给引擎自己的凭据链 |
| `paimon.rest.storage.gcp.lifespan` | 空 | GCS 令牌有效期；比 `credential.ttl-seconds` 短时收窄 `expiresAt` |
| `paimon.rest.storage.obs.access-key` | 空 | 华为云 OBS 默认凭据；三项全空则退化为环境凭据链 |
| `paimon.rest.storage.obs.secret-key` | 空 | 同上 |
| `paimon.rest.storage.obs.session-token` | 空 | OBS 临时凭据的安全令牌；长期凭据留空 |
| `paimon.rest.storage.obs.storages.<name>.access-key` | 空 | OBS 具名存储，另支持 `.secret-key` / `.session-token` |
| `paimon.rest.storage.oss.access-key` | 空 | 阿里云 OSS 默认凭据 |
| `paimon.rest.storage.oss.secret-key` | 空 | 同上 |
| `paimon.rest.storage.oss.session-token` | 空 | OSS 临时凭据的安全令牌（阿里侧键名为 `securityToken`） |
| `paimon.rest.storage.oss.storages.<name>.access-key` | 空 | OSS 具名存储，另支持 `.secret-key` / `.session-token` |
| `paimon.rest.storage.credential-secret-key` | 空 | catalog 自带静态凭据的落库加密密钥，Base64 且解码后**恰好 32 字节**；留空则关闭静态凭据，见下方说明 |
| `paimon.rest.storage.clients-cache-max-size` | 空 | 保留项，见下方说明 |
| `paimon.rest.storage.max-http-connections` | 空 | 保留项，见下方说明 |
| `paimon.rest.storage.read-timeout` | 空 | 读取超时；正数校验 |
| `paimon.rest.storage.connect-timeout` | 空 | 建连超时；正数校验 |
| `paimon.rest.storage.connection-acquisition-timeout` | 空 | 保留项，见下方说明 |
| `paimon.rest.storage.connection-max-idle-time` | 空 | 保留项，见下方说明 |
| `paimon.rest.storage.connection-time-to-live` | 空 | 保留项，见下方说明 |
| `paimon.rest.storage.expect-continue-enabled` | 空 | 保留项，见下方说明 |
| `paimon.rest.storage-credential-cache.max-entries` | `10000` | 已下发凭据的复用上限，条目按各自过期时间失效 |
| `paimon.rest.credential-manager.type` | `default` | `default` 下发凭据；`noop` 不下发，读表接口返回 501 |
| `paimon.rest.file-io.type` | `default` | 本部署接入的存储实现：`default`/`s3`/`azure`/`gcs`/`obs`/`oss`/`local` |

`paimon.rest.storage.obs.*` 与 `paimon.rest.storage.oss.*` 两组**没有 Polaris 侧的对应项**
——Polaris 没有这两个存储类型，它在 S3 兼容层上接入华为云与阿里云。
本工程单列，是为了让这两家的 AK/SK 独立于 S3 那一份，各按各自的节奏轮转与授权。

**catalog 自带的静态凭据**是另一处在 Polaris 规格之外的扩展：
`S3` / `OBS` / `OSS` 三种 `storageConfigInfo` 都多出 `accessKeyId` 与 `secretAccessKey`
两个字段，填写后该 catalog 就用这把钥匙访问对象存储，不必把密钥写进服务端配置。
参照 Polaris 的路径接入不提供 STS 的兼容 S3 存储（MinIO、Ceph RGW、Ozone、FlashBlade 等）
要写 `stsUnavailable: true`，此时服务端不再下发任何密钥，引擎只能靠自己的凭据链；
静态凭据补上的正是这一格——它表达的是「下发用户自己填进来的长期密钥」，
与 `stsUnavailable` 的「服务端不去向云申请临时凭据」是两件事，
因此**静态凭据排在 `stsUnavailable` 之前**，两者同时出现不冲突。

- `accessKeyId` 可读可写。它不是秘密，保留下来是为了让控制台能显示「配的是哪把钥匙」，
  也是判断这个 catalog「有没有配凭据」的依据。
- `secretAccessKey` **只写不读**。落库前用 AES-GCM（AES-256）加密，任何响应都不回显，
  因此 `PUT` 省略它表示**保持原值**而不是清空——这是规格 `PUT`「整体替换」语义的唯一例外，
  否则每次改 `endpoint` 都得重新贴一遍密钥。
- 想清除已配的凭据，显式提交一对空串（控制台表单里有「移除已保存的静态凭据」按钮），
  之后下发退回服务端配置那一级。只给其中一个会返回 400：有密钥没 ID、换 ID 不带新密钥、
  空 ID 配非空密钥，都无法区分是笔误还是有意为之。
- 静态凭据与 `storageName` **互斥**，两者都在回答「用哪组密钥」，同时出现返回 400。
- 未配 `credential-secret-key` 时**拒绝保存**静态凭据（400，错误信息给出生成命令
  `openssl rand -base64 32`），而不是明文落库；取值不是 Base64 或解码后不是 32 字节则启动即失败。
  轮换该密钥要注意：旧密文用新密钥解不开，读取该 catalog 的凭据会以 500 明确失败，
  不会静默退回服务端配置，需要重新提交一次凭据。

「保留项」指这些取值会被校验并在启动时打进日志，但当前实现没有出网的对象存储客户端，
JDK 的 `HttpClient` 也没有对应的调节项，因此它们不改变行为。保留是为了让配置面与 Polaris
一一对应，迁移时不必删配置。`read-timeout` 与 `connect-timeout` 之外的连接池参数属于此类。

`paimon.rest.file-io.type` 决定 catalog 允许的 `storageType`：`default` 接受全部六种，
`s3`/`azure`/`gcs`/`obs`/`oss` 只接受对应云存储加 `FILE`，`local` 只接受 `FILE`。
不接受本部署没有实现的存储类型时，创建或修改 catalog 直接返回 400，而不是让错误
推迟到引擎第一次读写时才以文件系统异常暴露。`FILE` 在所有取值下都保留，它是本地的退路。

`paimon.rest.credential-manager.type=noop` 适合引擎自带云凭据的部署。
关掉下发之后，引擎的凭据来源只剩一个，排查权限问题时不必再去猜
「到底用了服务端给的那份还是它自己那份」。

数据访问凭据下发按 catalog 的存储类型分派，各类型产出的键：

| storageType | 下发的键 | 密钥来源 |
| --- | --- | --- |
| `S3` | `s3.access-key-id`、`s3.secret-access-key`、`s3.region`、`s3.endpoint`、`s3.path-style-access` | catalog 静态凭据 → 具名存储 → 默认配置 → 环境凭据链 |
| `AZURE` | `azure.tenant-id`、`azure.account`、`azure.hierarchical`、`azure.multi-tenant-app-name`、`azure.consent-url` | 仅定位元数据，见下 |
| `GCS` | `gcs.oauth2.token`、`gcs.oauth2.token-expires-at`、`gcs.service-account` | 服务端配置 → 环境凭据链 |
| `OBS` | `fs.obs.access.key`、`fs.obs.secret.key`、`fs.obs.session.token`、`fs.obs.endpoint` | 具名存储 → 默认配置 → 环境凭据链 |
| `OSS` | `fs.oss.accessKeyId`、`fs.oss.accessKeySecret`、`fs.oss.securityToken`、`fs.oss.endpoint` | 同上 |
| `FILE` | 自包含令牌（`accessKeyId`/`securityToken`/`expiration`/`tablePath`） | 服务端自签，文件系统本身不校验 |

`OBS` 与 `OSS` 都是兼容 S3 协议的对象存储，但**键名族互不通用，也都不认 `s3.*`**：
Paimon 的 `paimon-obs` 与 `paimon-oss` 是两个独立的 FileIO。两家的命名风格还不一致——
华为是点分隔小写（`fs.obs.access.key`），阿里是驼峰（`fs.oss.accessKeyId`）；
连「临时凭据的令牌」都不同名（`session.token` 对 `securityToken`）。
这些差异来自各自的 Hadoop FileSystem（`hadoop-huaweicloud` 与 `hadoop-aliyun`），不是笔误，
改「统一」会让其中一侧静默失效：引擎只是拿不到凭据，然后在第一次读数据时以看似无关的文件系统异常失败。

`endpointInternal`、`stsEndpoint`、`roleArn`、`externalId`、`userArn` 一律不下发：
前者规格明确写了客户端看不到，后三者是服务端去换临时凭据的材料。
下发的键名写错不会报错，只会让引擎静默拿不到凭据，因此每种类型都有断言键名本身的测试。

仍可用 S3 类型接入华为云与阿里云：两者都提供 S3 兼容端点，把 `endpoint` 指向该端点即可。
代价是拿不到厂商原生的临时凭据键与凭据提供器配置，且多依赖一层协议转换。

Azure 只下发定位元数据是个明确的缺口：Polaris 的 `polaris.storage.*` 里没有 Azure 账户密钥这一项，
它靠服务进程自身的 Azure 标识签 SAS，而本工程没有那层标识。硬造一个签名错误的 SAS
比不给更糟，因此这里传租户与账户，由引擎用它自己的身份换取访问权。

---

## 6. 实现说明与已知边界

以下取舍在实现时已经明确，便于按需替换：

1. **`list-by-filter` 返回超集**。规格允许响应是匹配集合的超集，要求客户端复核谓词并继续翻页。
   当前实现不解析 `filter` 谓词表达式，直接返回全部（可选按名称前缀收窄）分区，属于合法的超集实现。
   若需精确下推，应在此处接入 Paimon 的 `Predicate` 反序列化与表达式求值。
2. **`pagesToken` 为无状态偏移量**。`base64url("offset:N")`，服务端不保存游标。
3. **可空性编码在类型名中**。规格的 `DataField` 没有独立可空字段，因此按 Paimon 惯例
   以 `"INT NOT NULL"` 形式表达，这也是 `updateColumnType` 需要 `keepNullability` 参数的原因；
   该变更只支持基本类型列。
4. **`drop database` 级联删除**。规格的删除接口没有 cascade 参数，这里按级联实现：
   先清理库内的表（含分区、消费者）、视图、函数、语义视图，再删除 database。
5. **表重命名不改数据位置**。只更新目录中的名称与所属 database，与 Paimon 服务端一致；
   因此磁盘上的目录名不会跟着变。目标名已被占用时返回 409——「改成与原名相同」也走这条，
   控制台在本地拦下并当作无操作（否则点一下确定就会收到一句莫名的「表已存在」）。
6. **`rollback-schema` 递增版本号**。回滚把历史内容写入新的 `schemaId`，而不是退回版本号，
   保证快照与 schema 的对应关系单调。
7. **`commit` 乐观并发**。`baseSnapshotUuid` 与当前最新快照不一致时返回 `success=false`，
   由客户端重试，而不是抛错。
8. **凭证下发为自包含令牌**。`token` 端点签发随机的短期凭据并带上过期时间；
   生产环境应替换为对接云 IAM 的 STS 调用。
9. **`auth` 的列校验**。请求列不在表 schema 中时返回 403；当前不配置行过滤与列脱敏策略，
   因此返回空的过滤表达式与脱敏映射。
10. **静态令牌的映射是静态配置**。`paimon.rest.auth.token-principals` 决定
    「静态令牌 → 主体名」，因此改这个映射要重启。**但这不再限制新建的主体**：
    创建主体时会一次性返回 `clientId` 与明文 `clientSecret`，用它们走
    `POST /api/catalog/v1/oauth/tokens`（OAuth 2.0 客户端凭据）换一个访问令牌，
    立刻就能调管理 API 或进控制台——不需要改配置、不需要重启。
    需要接入统一登录时用 `console.oidc`（外部身份提供方）。
    详见 [`console-auth.md`](docs/console-auth.md)。
11. **行过滤与列脱敏策略尚未实现**。Polaris 治理面中的这两项不在当前范围，
    `auth` 端点返回空的过滤表达式与脱敏映射，只做列存在性校验。
12. **多权限授权不是原子的**。规格没有批量授权端点，`GRANT a, b ON ...` 会顺序发出多次调用，
    中途失败时前面的已经生效。
13. **`{prefix}` 的校验语义由服务端自行约定**。规格把 `prefix` 声明为必填路径参数，
    但未定义「prefix 与服务端不一致时如何响应」。本实现把 prefix 视为 catalog 标识：
    已登记则解析到对应 catalog，未登记时按 `paimon.rest.auto-create-catalog` 决定
    自动登记或返回 404。默认值 `true` 使客户端可零配置接入，代价是写错 prefix 时
    会得到一个新的空 catalog 而不是报错；生产环境建议设为 `false`，
    由 `paimon.rest.initial-catalog.databases` 预置允许的 prefix。
14. **`ddl-auto` 是 `validate`，不做自动迁移**。库结构由 `sql/schema-mysql.sql` 管理，
    实体与库不一致时应用直接启动失败。这样避免 Hibernate 在生产库上执行隐式 `alter table`，
    代价是实体变更后必须重新生成 DDL 并自行迁移存量数据（本工程未内置迁移工具，
    若需要可接入 Flyway / Liquibase，把 `sql/schema-mysql.sql` 作为首个版本）。
15. **超长文本列的实际容量受 MySQL 的 TEXT 类型限制**。`length = 65535` 的列在 MySQL 上
    落为 `text`（上限 65535 **字节**，utf8mb4 下约 1.6 万个汉字），`length = 1048576` 以上
    落为 `mediumtext`（上限约 16 MB）。对存 JSON 元数据足够，但若某个 catalog 的
    schema 文本极大，需要留意这一上限。
16. **MySQL 上的布尔列是 `bit`**。Hibernate 把 Java `boolean` 映射为 MySQL 的 `bit`，
    功能正常但与多数人手写的 `tinyint(1)` 不同，写外部 SQL 时注意。
17. **本服务端只提供元数据，不做数据面写入**。建库建表、读元数据、删除都可用；
    `INSERT` / `CREATE TABLE ... AS SELECT` 不可用——Paimon 客户端会正常把数据文件写进仓库，
    但在提交快照时找不到 Paimon 自己维护的 `schema/schema-<n>` 文件
    （报 `Cannot get latest schema for table <表名>`）。补齐它需要在服务端引入
    Paimon 核心与 FileIO，在仓库里物化 schema 与 snapshot，当前不在范围内。
    边界与报错链见 [`docs/spark-paimon-rest-e2e.md`](docs/spark-paimon-rest-e2e.md) 第 4 节。
18. **存储配置的跨类型字段被静默忽略**。`storageType` 为 `FILE` 却带 `roleArn` 的请求
    不会报 400，而是丢掉该字段——全站都依赖 Spring 默认的宽松绑定，为存储配置单独收紧
    会造成「只有这个接口严格」的不一致。代价是拼错的字段名不会被发现。
19. **审计列存的是主体名，超过 255 字符时记摘要**。`owner` / `created_by` / `updated_by`
    是 `varchar(255)`，而主体名的长度没有上界：认证链认不出令牌时会退化为「令牌即主体名」，
    控制台签发的访问令牌就有 272 个字符，于是写入以
    `Data too long for column 'created_by'` 失败——现象是「建表/建库报 500」，
    从错误信息里看不出与认证有关。因此落库前统一在 `AuditedEntity` 归一化
    （`AuditPrincipal.of`）：放得下的原样存，放不下的记 `sha256:<前 12 位>`。
    **不截断**：截断会把凭据的前 255 个字符原样写进元数据库，而元数据是能被列表接口读出来的。
    超长时启动日志会告警一次，给出的解法是配 `paimon.rest.auth.token-principals`
    把令牌映射成真正的名字。授权判定读的是未归一化的主体名，两者互不影响。
    **刻意不通过加宽这三列来解决**：它们会被接口原样返回，加宽等于让凭据明文入库并可被读回；
    且主体名长度没有上界，加宽只是把溢出推后，还要对 14 张表共 42 个列做迁移。
20. **静态凭据的加密密钥不支持多代共存**。`paimon.rest.storage.credential-secret-key`
    换值后，库里已有的密文用新密钥解不开，读取那个 catalog 的凭据会以 **500** 明确失败
    （不是静默退回服务端配置），需要重新提交一次凭据。做成多代密钥要同时保存密钥列表与
    「这条密文是第几代」，当前规模下不值得；代价是**轮换密钥必须配合一次凭据重录**。

---

## 7. 测试

### 单元与集成测试（334 个用例）

```bash
JAVA_HOME=/path/to/jdk-21 ./mvnw test
```

测试连的是内存 H2（`test` profile，见 `src/test/resources/application-test.yml`），
**不依赖本机是否有 MySQL**。

服务端（258 个）：

| 测试类 | 覆盖 |
| --- | --- |
| `PaimonRestCatalogApiTests` | 配置发现、建表与 schema 变更（加列 / 列改名 / 改类型 / 改可空性 / 改属性）/ 回滚、快照提交与乐观并发、分区统计、表重命名（改名不动数据位置、跨库重命名、目标名被占用 409）、视图与函数、语义视图 1 MiB 上限、消费者位点、凭证下发与 403、各类 404 的 `resourceType` |
| `ManagementApiTests` | 管理 API 的主体、角色、装配与授权链路 |
| `StorageConfigApiTests` | `storageConfigInfo` 六种存储类型的建 / 读 / 改往返、`AZURE` 缺 `tenantId` 与空位置的 400、换存储后新库位置随之改变、OBS↔OSS 换类型时端点一起替换 |
| `StorageConfigDtosTests` | 判别联合的绑定位形：子类型注册名与枚举一致、判别字段只出现一次、跨类型字段不串、落库路径（`Json`）往返、静态凭据字段随实体往返 |
| `StorageCredentialApiTests` | 按存储类型下发凭据的 HTTP 链路：S3 的密钥来源（默认配置与具名存储）与不下发 `endpointInternal` / `stsEndpoint`、`stsUnavailable` 时不给密钥、Azure 只给定位元数据、GCS 的 `lifespan` 收窄 `expiresAt`、OBS / OSS 各自走对键族且不串族、FILE 保留自包含令牌、未过期凭据被复用（比对 `expiresAt` 而非密钥） |
| `StorageCredentialResolutionTests` | 凭据解析与缓存的单元行为：具名存储优先且找不到时明确失败而非退回默认凭据、服务端专用字段不泄露、Azure 账户名只在已知端点后缀上解析、GCS `lifespan` 与 `ttl` 取小、OBS / OSS 的键名族与临时凭据令牌（含两家不同拼写）、两家的凭据互不可见、LRU 淘汰与过期失效、两个策略枚举的取值校验与错误信息；**catalog 静态凭据压过一切服务端来源、在 `stsUnavailable` 下仍然下发、OBS / OSS 各走自己的键族、密文解不开或不是密文时明确失败而非静默退回** |
| `StaticCredentialApiTests` | catalog 静态凭据的 HTTP 语义（配了加密密钥）：创建响应不回显密钥而库内是密文且能解回原值、凭据下发优先用 catalog 这一份、`PUT` 省略密钥表示保持、完全不带凭据字段也表示保持、提交一对空串即清除并退回服务端配置、只给密钥 / 换 ID 不带新密钥 / 空 ID 配非空密钥各自 400、与 `storageName` 互斥 400、OBS 与 OSS 同样支持、meta 报告 `staticCredentialsEnabled` |
| `StaticCredentialDisabledApiTests` | 未配 `credential-secret-key` 时的 fail-closed：保存静态凭据返回 400 且错误信息点明配置项与 `openssl rand -base64 32`、**不带凭据的 catalog 照常可建**（对照组，防「顺手把整个存储配置关掉」）、meta 报告能力已关闭 |
| `CredentialCipherTests` | 落库加解密单元行为：往返、同一明文两次密文不同（随机 IV）、空值保持空、未配密钥时拒绝加密、换密钥后解不开、密文被篡改时认证失败、明文当密文传入被拒、密文载荷畸形被拒、密钥不是 Base64 或解码后不是 32 字节时启动即失败、留空的密钥视为关闭 |
| `StoragePolicyApiTests` | `file-io.type=s3` 时拒绝 Azure / GCS / OBS / OSS catalog 并在报错里点明原因、接受 S3 与 FILE；`credential-manager.type=noop` 时凭据下发返回 501 |
| `AuthorizationTests` | 权限蕴含与判定的单元行为 |
| `CatalogEndpointAuthorizationTests` | 从运行时请求映射枚举全部 `/v1/{prefix}/**` 端点，逐一核对授权映射是否已登记——新增端点若忘记登记映射会让构建失败 |
| `ConsoleApiTests` | 控制台的托管与元数据：`/console` 重定向、目录式路径转发入口页、深链回退、缺失 assets 仍 404、已提交入口页引用的产物都存在、meta 的枚举与权限分组跟着规格走、登录引导同时如实返回 `authEnabled` 与 `consoleRequired`、门禁开着时控制台端点拒匿名而数据面仍开放（见第 9 节） |
| `ConsoleAuthEndpointTests` | 控制台登录在 HTTP 层的端到端：豁免端点可达而受保护端点拒绝匿名、**用户名密码登录成功/失败/缺参/限速与默认 `admin/admin`**、令牌签发与使用、错误密钥 401 与 `WWW-Authenticate`、**clientId 与用户名两种「不存在」的报文都与「密码不对」一字不差**、`invalid_scope` / `unsupported_grant_type`、Basic 与 JSON 体、失败限速、静态令牌映射、伪造 JWT 与畸形头。这一层才会暴露接线错误——排除列表写错、响应字段名写成驼峰、令牌端点自己反被鉴权挡住 |
| `ConsoleAuthMethodVisibilityTests` | 登录方式的可见性：**没开启的方式不出现在登录引导里**——关掉 `console.client-credentials.enabled` 就不下发 `client-credentials`，`auth.tokens` 为空就不下发 `static-token`，而默认开启的用户名密码照旧（对照组，防「无脑返回空列表」） |
| `AccessTokenServiceTests` | 访问令牌的签发与验证：claims、TTL 边界、换钥、篡改、换 issuer、无 `exp`、`alg:none`、签名密钥格式错必须启动失败、随机钥不共享 |
| `JwtTests` | 纯 JDK 的 JWT 编解码与验签：HS256 / RS256 / ES256 往返、**ES256 的原始签名转 DER**、`alg:none` 与未知算法、结构拒绝、`aud` 单值与数组 |
| `JwksTests` | JWKS 解析：RSA 与 EC 可用性（实测验签）、混合类型、不可用键跳过而非整份失败、私钥材料忽略、无 `kid` 与重复 `kid`、畸形文档 |
| `OidcServiceTests` | 用 JDK `HttpServer` 起假 IdP：发现文档与 JWKS 的缓存与刷新、**未知 `kid` 强制重取**、刷新失败沿用旧值、换钥、`audience`、时钟偏移、发现文档 issuer 不符则拒绝、IdP 不可达时不影响已缓存的验证 |
| `LoginAttemptLimiterTests` | 失败限速：阈值、窗口重置、桶相互独立、关闭、桶数上限与过期清理 |
| `AuditPrincipalTests` | 审计主体名归一化的单元行为：短名原样、空值记 `anonymous`、恰好 255 字符仍原样、超长记 `sha256:<前 12 位>`（确定性、幂等、**结果里不含原文任何片段**，也**不是截断**），以及实体基类写 `owner` / `createdBy` / `updatedBy` 时一定不超列宽 |
| `AuditPrincipalEndpointTests` | 审计主体名走完整 HTTP 链路（拦截器 → 主体名 → 实体 → JDBC）：门禁模式下 272 字符的未知令牌**不再把建表打成 500** 而是记摘要、`Bearer alice` 仍以 `alice` 入库（本地调试约定不退）、服务端自己签发的令牌解析出真实主体名、匿名记 `anonymous` |
| `MysqlDdlGeneratorTests` | 由实体元数据生成 MySQL DDL，并断言方言被钉在 MySQL 8.0（见「代码生成」） |
| `PaimonRestServerApplicationTests` | 上下文加载 |

Spark 子模块（76 个，其中 14 个需要显式指向服务端）：

| 测试类 | 覆盖 |
| --- | --- |
| `ManagementSqlParsingTests` | 21 条语句的解析结果、GRANT 资源层级拆分、多权限展开、反引号与注释、非管理语句必须交回原生解析器 |
| `ManagementApiClientTests` | 请求方法与路径、请求体字段形状、路径段编码、`ALTER` 的读-合并-回写、单资源裸对象与状态码、错误映射 |
| `ManagementSqlExecutionTests` | 真实 `SparkSession` + 桩服务端：扩展是否真被加载、`spark.sql` 是否真执行命令、结果行列名、原生 SQL 不受影响 |
| `ManagementSqlLiveServerTests` | 对真实服务端跑完整 SQL 链路（默认跳过，见下） |
| `PaimonTableDdlTests` | 用 Spark SQL 经 Paimon Rest Catalog 建表：分区 append 表与主键表（含分区主键）、`LIKE`、`IF NOT EXISTS` 幂等与裸建冲突、内联主键约束与保留表属性被 Spark 拒绝、库不存在时报错，以及 `CTAS` 因数据面缺失而失败的守卫用例；两种断言并重——`DESCRIBE` / `SHOW CREATE TABLE` 的输出，以及直接读服务端元数据核对注释、分区键、主键与选项（默认跳过，见下） |
| `SparkSqlDocExamplesTests` | 抽出 `docs/spark-sql-reference.md` 第 11 节的示例并逐条执行，断言撤销语义与清理结果（默认跳过，见下） |
| `PaimonRestManagementTests` | 配置解析的容错 |

### 端到端验收脚本

```bash
# 需先有一个服务端实例；不指定 profile 时连 MySQL
# （想用内存库就在启动命令上加 --spring.profiles.active=h2）
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar &

# Catalog API：覆盖规格中全部 60 个 operation
# 脚本会创建并清理 database sales，因此要求目标实例为初始状态
BASE=http://127.0.0.1:8080 ./scripts/api-sweep.sh

# Management API：覆盖规格中全部 33 个 operation
BASE=http://127.0.0.1:8080 ./scripts/management-sweep.sh

# Spark SQL：先校验参考文档，再自动起服务端（用 h2 profile）、跑
# ManagementSqlLiveServerTests + PaimonTableDdlTests + SparkSqlDocExamplesTests、
# 收尾停服务端
./scripts/e2e-spark-sql.sh

# 控制台登录（HTTP 层）：登录引导、用户名密码登录、令牌端点的错误码、凭据校验、
# 签发令牌、静态令牌、静态资源深链——63 项。需以 --paimon.rest.auth.enabled=true 启动，
# 凭据取自启动日志里「created bootstrap principal」那行（仅在开了授权时才有；
# 没开时先用默认账号密码登录、再调管理 API 建一个主体，见 docs/console-auth.md §11）。
# 第 1 节断言「登记了静态令牌，登录页才会有这个页签」，因此实例要一并配
# --paimon.rest.auth.tokens[0]，否则那几条会如实报出「没出现」
BASE=http://127.0.0.1:8080 \
CLIENT_ID=<引导主体 clientId> CLIENT_SECRET=<明文密钥> \
STATIC_TOKEN=<需与启动参数 paimon.rest.auth.tokens[0] 一致，否则脚本会断言该方式不出现> \
  ./scripts/sweep-console-auth.sh

# 前端登录逻辑：直接跑 src/store/auth.js 与 src/api/* 的真实代码——59 项
# 用上面那个服务端实例即可
cd paimon-rest-console
BASE=http://127.0.0.1:8080 \
CLIENT_ID=<引导主体 clientId> CLIENT_SECRET=<明文密钥> \
  npm run check:login
```

`api-sweep.sh` 与 `management-sweep.sh` 每行输出
`<标记>[实际状态码/期望状态码] 说明 响应片段`，末尾汇总通过 / 失败项数，
存在失败时以非 0 退出；请求体形状与规格字段名一致，可直接作为接口调用示例。

`e2e-spark-sql.sh` 是唯一会启动服务端的脚本，也是验证「客户端与服务端契约一致」的那一层：
前几层测试用的是桩，只能证明客户端与自己的假设自洽。它一次跑三个类——
`ManagementSqlLiveServerTests`（管理 API 契约）、`PaimonTableDdlTests`（用 Spark SQL 建 Paimon 表）、
`SparkSqlDocExamplesTests`（参考文档示例）。三者**必须同批运行**：一个 JVM 只能有一个
SparkContext，而 `spark.sql.extensions` 只在建会话时生效，因此它们共用同一个会话；
把用桩的 `ManagementSqlExecutionTests` 拉进同一批会让端点与扩展错位，
`LiveSparkSession` 会就此直接报错，而不是静默跑错。

`sweep-console-auth.sh` 与 `npm run check:login` 是登录链路的另外两层，都对着运行中的实例跑：

- `sweep-console-auth.sh` 发**真实 HTTP 请求**，断言的是协议层——豁免端点可达而受保护端点
  拒绝匿名、哪种组合下控制台要登录、每种错误对应哪个 `error` 码、clientId 不存在与密钥不对
  的报文是否一字不差、用户名不存在与密码不对是否同样一字不差、失败是否被限速、
  静态令牌是否映射到主体。它在意的故障是「接线错误」：
  拦截器排除列表写错、令牌端点自己反被鉴权挡住、SPA 深链回退漏了 `/console/auth/callback`。
- `npm run check:login` 用 vite 的 `ssr` 构建把 `src/store/auth.js` 与 `src/api/*` **原样**打成
  Node 可执行产物（不是另写一份仿真），装好 `localStorage` / `sessionStorage` 替身，
  包住 `fetch` 记录前端发出的每个请求，再断言请求体形状（用户名密码那条是 JSON、
  客户端凭据那条是表单）、令牌落盘的 key、`Authorization` 的附加、登出、失败回滚。
  它证明的是**前端发出的请求形状与登录态流转**——
  服务端测试看不到这一层，`adopt()` 失败不回滚那个 bug（会把正在用的有效令牌一起弄丢）
  就是它抓出来的，而当时服务端 209 个用例与静态校验全绿。

两者都要 `BASE` + `CLIENT_ID` + `CLIENT_SECRET`，缺了会以退出码 2 明确拒绝而不是跑出一串假失败。
用户名密码那一段默认用 `admin/admin`，改过配置的部署用 `CONSOLE_USER` / `CONSOLE_PASSWORD`
覆盖；**一分钟内连跑超过 5 次**会被服务端自己的失败限速拦下（那不是回归）。

### 代码生成与文档校验

规格或实体变更后重新生成派生产物：

```bash
python3 scripts/gen-management-contract.py   # docs/management-api-contract.md
python3 scripts/gen-management-privileges.py # dto/Privilege.java

# 生成后校验：反解文档表格，与规格逐行比对
python3 scripts/verify-management-contract.py

# Spark SQL 参考文档：比对语法文件、规格枚举、客户端代码
python3 scripts/verify-spark-sql-doc.py

# MySQL 建表语句（改实体后必须重跑，否则启动时 validate 会失败）
./scripts/gen-mysql-ddl.sh                   # sql/schema-mysql.sql

# 控制台：构建产物落位 + 与服务端规格逐条比对端点
./scripts/build-console.sh                   # 需要 Node 20+，见第 9 节
python3 scripts/verify-console.py            # 只校验，不构建（多解释器时用 PYTHON=... 指定）
```

这几个 Python 脚本都要解析 OpenAPI 规格，因此需要 PyYAML：

```bash
python3 -m pip install --user PyYAML
```

`gen-mysql-ddl.sh` 内部跑 `MysqlDdlGeneratorTests`：DDL 由 Hibernate 依实体元数据生成，
**不手工维护**——18 张表、19 个唯一约束、15 个索引，手工维护必然漂移。生成器连的是 H2
但方言被钉在 MySQL 8.0（`MySql8DialectResolver`），因此生成过程不需要 MySQL，
产出则已用真实 MySQL 8.0.46 验证过可直接执行。`DB_NAME=...` 可改建库语句里的库名。

`verify-management-contract.py` 刻意不复用生成器的任何函数——它把 Markdown 表格反解回结构化
数据再独立比对，因此能发现生成器自身的推导错误（两者共用代码时错误会互相掩盖）。
校验覆盖 §1 端点清单、§4.1 成功响应形状、§4.4 成功码分布与 §4.5 错误码计数；
生成器另有一组断言，规格结构变化时会直接失败，而不是写出一份错位的基线。

---

## 8. 容器与 Kubernetes 部署

### 8.1 镜像

```bash
docker build -t paimon-rest-server:0.0.1 .
```

多阶段构建：构建阶段用 `maven:3.9-eclipse-temurin-21`，运行阶段只留
`eclipse-temurin:21-jre-noble` 加一个 jar，镜像里不含 Maven 仓库与源码。
运行时以 uid 10001 非 root 运行；`ENTRYPOINT` 用 exec 形式，让 SIGTERM 直达 JVM，
配合 Spring Boot 的优雅停机。

镜像里放两样东西：jar，以及 `sql/schema-mysql.sql`。后者是给 K8s 的 initContainer
用的——它把 DDL 投递给 MySQL 做首次初始化（见 8.2）。两样东西来自同一次构建，
因此 DDL 与 jar 里的实体定义天然同版本。

构建时踩到的两个坑已经落进 Dockerfile：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 解析 `spring-boot-starter-parent` 时失败，报 `Requests from open proxy and relay services are blocked` | 某些网络环境下 Maven Central 对特定出口 IP 返回 403，而本机 mvn 正常 | 默认仓库改为 `repo1.maven.org`；换企业镜像用 `--build-arg MAVEN_MIRROR_URL=...` |
| 容器内 `Permission denied`，报错指向 `/app/sql/schema-mysql.sql` | `COPY --chmod=0644` 会把权限也套到 Docker 自动创建的中间目录上，`/app/sql` 变成 `drw-r--r--`；目录没有 x 位就无法遍历，里面文件什么权限都读不到 | 先 `RUN mkdir -p /app/sql && chmod 0755`，再单独 `COPY` 文件 |

### 8.2 Kubernetes

`deploy/kubernetes/` 是一套用 kustomize 组装的清单：

```bash
kubectl -n paimon-rest create secret generic paimon-rest-credentials \
  --from-literal=DB_PASSWORD=... --from-literal=REST_TOKEN=...
kubectl apply -k deploy/kubernetes
```

包含服务端 Deployment、MySQL StatefulSet、warehouse 的 PVC、配置与凭据、
Service / Ingress / PDB。完整说明在其目录下的 `README.md`，三条比较关键的取舍：

- **副本数固定为 1**：warehouse 用 `file://` 加 ReadWriteOnce 卷，多副本会各挂一份
  不同的数据。要横向扩展需要先把仓库换成对象存储（服务端已支持 S3 / Azure Blob / GCS）。
- **探针用 exec + curl 而不是 httpGet**：`httpGet` 只把 200–399 视为成功，
  而开启鉴权后 `/v1/config` 无令牌返回 401，会让 Pod 永远不 Ready——
  表现是「容器在跑、日志正常，但 Service 没有后端」。
- **建表语句走镜像而不是 ConfigMap**：kustomize 不允许引用 kustomization 目录之外的
  文件，而把 DDL 复制进 `deploy/` 必然漂移。代价是改完 DDL 必须重新构建镜像。

清单的内部一致性有脚本检查，不需要集群：

```bash
python3 scripts/verify-k8s-manifests.py
```

它核对的是跨文件引用——selector 能否选中 Pod、卷名与卷定义是否对得上、
`secretKeyRef` 的 key 是否存在、**配置里的 `${...}` 是否都有对应环境变量**、
探针端口与 containerPort 是否一致、清单引用的镜像是否都在 Dockerfile 里有构建命令。
这些错误 `kubectl apply` 时都不会报，只在运行时表现成 Pod 一直 Pending
或启动时报「解析不了占位符」。

### 8.3 可运行的示例

`examples/spark-paimon-rest/` 是一个能直接跑通的示例：自动起服务端，
用 Spark SQL 经 Paimon Rest Catalog 建表，再把服务端侧的元数据打印出来。

```bash
./mvnw -o install -DskipTests      # 只需一次
cd examples/spark-paimon-rest && ./run-example.sh
```

不需要预先安装 Spark 发行版——classpath 由本仓库的 Maven 依赖提供。
要在自己的 Spark 集群上手工执行，用同目录的 `spark-defaults.conf`，
SQL 与报错对照见 [`spark-paimon-rest-e2e.md`](docs/spark-paimon-rest-e2e.md)。

---

## 9. Web 管理控制台

服务端自带一个 Vue 单页应用，挂在 `/console/` 下，覆盖 Catalog API 与 Management API 两个面：

```
http://localhost:8080/console/
```

| 路由 | 页面 |
| --- | --- |
| `/login`、`/auth/callback` | 登录与 SSO 回调 |
| `/` | 概览：统计块、服务端配置、**连接自检**、catalog 清单、`GET /v1/config` |
| `/browse` | 目录浏览：库/表/视图/函数/语义视图；建库建表、注册表、按表 ID 定位、重命名、删除 |
| `/tables/:prefix/:database/:table` | [表详情](docs/console-table-detail.md)：九个页签、快照与标签回滚、表级 grants |
| `/catalogs`、`/principals`、`/principal-roles`、`/catalog-roles` | 治理：catalog、主体、服务角色、catalog 角色与 grants |
| `/settings` | 连接设置：API 基址、访问令牌、登录状态、主题 |

三条约束值得先知道：

- **它是运维工具，不是写入入口。** 不提供 `commitTable`（控制台拼不出一个指向真实文件的快照）；
  对表的改动只有结构变更、回滚与授权。
- **构建产物提交在仓库里。** 产物落在 `paimon-rest-server/src/main/resources/static/console`，
  因此 `./mvnw package` 与 Dockerfile 都不需要 Node；代价是改了前端必须重建并提交产物，
  用 `./scripts/build-console.sh` 一条命令完成构建与校验。
- **路径不靠人工比对。** 全部端点收在 `paimon-rest-console/src/api/endpoints.js` 一张表里，
  由 `scripts/verify-console.py` 与两份 OpenAPI 规格机械比对（11 组 28 项：端点存在性、
  引用完整性、五处基址一致、产物新鲜度、请求体形状、对话框状态、下拉候选来源、
  静态凭据字段与服务端的一致性）；
  服务端侧 `ConsoleApiTests` 守住静态资源托管与 SPA 深链回退。

### 9.1 登录与鉴权

两个开关管两件不同的事：`paimon.rest.auth.console.required`（默认 `true`）只作用于控制台界面
与 `/api/console/v1/**`；`paimon.rest.auth.enabled`（默认 `false`）管 `/v1/**`、
`/api/catalog/v1/**`、`/api/management/v1/**` 是否要带令牌。

**默认部署是「要求登录但数据面敞开」**：浏览器被拦在登录页，而 `curl /v1/config` 返回 200。
这层门禁只避免「误入的人看到一堆管理表单」，**不是安全边界**——要保护数据必须开 `auth.enabled`。
浏览器登录支持用户名密码（默认 `admin/admin`，不需要先建主体）、OAuth 客户端凭据、
OIDC 授权码 + PKCE 三种方式；令牌是自包含 JWT，多实例互认但**无法即时撤销**。
完整设计见 [`docs/console-auth.md`](docs/console-auth.md)，三层测试（31 / 63 / 59）用法见第 7 节。

### 9.2 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/console.md`](docs/console.md) | 控制台总览：目录结构、服务端如何托管、页面清单、端点契约表与机械校验、构建与提交约定、测试分层 |
| [`docs/console-table-detail.md`](docs/console-table-detail.md) | 表详情页：九个页签逐个说明、权限页签的授权模型与 N+1 取舍、破坏性操作、已知边界 |
| [`docs/console-auth.md`](docs/console-auth.md) | 登录与鉴权：两个开关的边界、三种登录方式与令牌端点、错误码、安全考量、已知边界 |
