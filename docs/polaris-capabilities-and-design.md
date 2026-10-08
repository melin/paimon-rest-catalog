# Apache Polaris 核心能力与 Paimon REST Catalog 实现对照

本文先提炼 Apache Polaris 的核心能力，再说明这些能力如何映射到
Apache Paimon 的 REST Catalog 规格，以及本仓库（Spring Boot + JPA 实现）的落点与差异。

---

## 一、Apache Polaris 是什么

Apache Polaris 是 Apache Iceberg 的开源目录（catalog）实现，最初由 Snowflake 开发并于
2024 年 6 月开源，随后捐赠给 Apache 软件基金会。

它解决的是数据湖场景下最核心的一层：**表的当前元数据指针由谁保存、由谁鉴权、由谁下发访问凭证**。
Polaris 通过实现 Iceberg 的开放 REST Catalog 协议，让多个计算引擎在同一份数据上协同读写，
从而把「目录层」从具体计算引擎中解耦出来。

---

## 二、核心能力提炼

| # | 能力 | 具体含义 |
| --- | --- | --- |
| 1 | **标准 REST Catalog 契约** | 完整实现 Iceberg 开放 REST Catalog API，任何遵循该规范的引擎（Spark、Flink、Trino、Doris、StarRocks、Dremio OSS 等）无需专用连接器即可接入 |
| 2 | **集中式统一鉴权** | 鉴权在目录服务侧统一完成，与引擎无关；同一套策略对所有引擎一致生效 |
| 3 | **RBAC 权限模型** | 双层角色：principal roles（授予服务主体的角色）与 catalog roles（在目录资源上配置权限并被授予给 principal roles）；权限可作用于 catalog / namespace / table 各级 |
| 4 | **凭证下发（credential vending）** | 引擎不持有对象存储长期密钥；目录服务校验授权后下发短期、范围受限的存储凭证 |
| 5 | **多租户 / 多 catalog** | 单实例服务多个 catalog 与多个主体；catalog 是资源组织的第一层 |
| 6 | **catalog 类型区分** | Internal（由 Polaris 托管、可读写）与 External（外部目录同步而来、只读） |
| 7 | **存储配置抽象** | 创建 catalog 时统一配置对象存储（S3 / GCS / Azure）与位置、角色、租户信息 |
| 8 | **治理面 API** | REST Management API 提供实体标签、对象权限授予、行过滤与列脱敏等细粒度治理能力 |
| 9 | **不锁定部署形态** | 可自托管（Docker / Kubernetes Helm）、可跑在自有基础设施，也可使用托管服务 |
| 10 | **稳定的 API 演化策略** | 非实验性 API 按语义化版本维护；废弃接口保留过渡期；必要时通过新增路径（如 `v1` → `v2`）而非破坏性修改推进 |

**一句话概括 Polaris 的范式**：把「表格式规范 + 开放 REST 契约 + 集中鉴权 + 凭证下发 + 多租户 + 治理面」
做成一层与引擎无关的目录服务。

---

## 三、能力到 Paimon REST Catalog 的映射

Paimon 的 REST Catalog 规格遵循同一范式，但对象模型不同：Polaris 面向 Iceberg
（快照 + manifest 清单 + 分区规格），Paimon 的规格则额外覆盖分区登记与统计、分支、标签、
流式消费者、函数与语义视图，并自带数据访问授权端点。

| Polaris 能力 | Paimon REST Catalog 对应 | 本实现落点 |
| --- | --- | --- |
| 标准 REST Catalog 契约 | REST Catalog API v1，60 个端点 | `web/` 下 10 个控制器，路径与 operationId 一一对应 |
| 统一鉴权 | 规格声明 `bearerAuth` 安全方案 | `BearerAuthInterceptor` 挂在 `/v1/**`，业务层只读 `RequestContext.principal()` |
| 多租户 / 多 catalog | 路径前缀 `{prefix}` 即 catalog 标识 | `CatalogEntity` + `CatalogService.resolve()`，支持未登记 prefix 自动登记 |
| catalog 类型（internal / external） | `register` 进来的表标记 `isExternal` | `TableEntity.external` + `POST .../databases/{database}/register` |
| 存储配置抽象 | `warehouse` + 表路径约定 | `default-warehouse` 与 `path-template` 配置项；路径按 `warehouse/database.db/table` 推导 |
| 存储类型与凭据（S3 / Azure / GCS） | `StorageConfigInfo` 判别联合 + `polaris.storage.*` 配置 | 管理面四种 `storageType` 完整建模并落库（`StorageConfigs`）；服务端侧配置面见 `RestServerProperties.Storage`、`CredentialManager`、`FileIo`，下发规则见 `StorageCredentialManager` |
| 凭证下发 | `GET .../tables/{table}/token` | `CredentialService.token()`：按 catalog 的存储类型分派，经 `StorageCredentialCache` 复用未过期凭据并返回 `expiresAt` |
| 查询鉴权与策略下推 | `POST .../tables/{table}/auth` | `CredentialService.auth()`：返回行过滤表达式与列脱敏映射；请求列不在 schema 中时返回 403 |
| RBAC 细粒度权限 | 由 REST Management API 承担权限面 | 已实现：主体 → principal role → catalog role → 资源授权的完整链路，并作为 `/v1/**` 的判定依据。见第六节 |
| 治理面（实体标签 / 行过滤 / 列脱敏策略） | REST Management API（独立规格） | 实体标签未实现；行过滤与列脱敏未实现，`auth` 端点返回空规则。见第八节 |
| 元数据持久化可插拔 | 规格未约束 | JPA + MySQL 8.0（默认，DDL 见 `sql/schema-mysql.sql`）/ H2 内存库 / PostgreSQL profile |
| 版本化与向后兼容 | 规格 `version: "1.0"`，路径以 `/v1` 开头 | 错误响应严格按规格（`{message, resourceType, resourceName, code}`），未知变更 action 返回 400 |

---

## 四、本实现的结构选择

### 1. 前缀即租户边界

客户端先调 `GET /v1/config?warehouse=...`，服务端在 `defaults.prefix` 中回传 prefix，
之后所有资源路径都以该 prefix 开头。这与 Polaris 用 catalog 做资源组织第一层的思路一致，
差别只是 Paimon 规格把这个标识放在 URL 前缀位置。

服务端不要求预先登记：`paimon.rest.auto-create-catalog=true` 时，任意 warehouse 值都会
自动登记为一个 catalog，便于引擎零配置接入；生产环境可关闭该开关，改为显式管理。

### 2. 鉴权与业务解耦

鉴权在拦截器统一完成，业务代码只从 `RequestContext` 读取主体名写入
`owner` / `createdBy` / `updatedBy` 审计字段。这对应 Polaris「策略统一、引擎无关」的定位：
换认证方式不需要改业务逻辑。

`auth.enabled=false`（默认）时所有请求以 `anonymous` 通过，便于本地开发。

### 3. 凭证下发按存储类型分派

下发的键由 catalog 的 `storageConfigInfo.storageType` 决定，而不是所有类型共用一个形状：

| storageType | 下发的键 | 密钥来源 |
| --- | --- | --- |
| `S3` | `s3.access-key-id`、`s3.secret-access-key`、`s3.region`、`s3.endpoint`、`s3.path-style-access` | 具名存储 → 默认配置 → 环境凭据链 |
| `AZURE` | `azure.tenant-id`、`azure.account`、`azure.hierarchical`、`azure.multi-tenant-app-name`、`azure.consent-url` | 仅定位元数据 |
| `GCS` | `gcs.oauth2.token`、`gcs.oauth2.token-expires-at`、`gcs.service-account` | 服务端配置 → 环境凭据链 |
| `FILE` | 自包含令牌（`accessKeyId` / `securityToken` / `expiration` / `tablePath`） | 服务端自签 |

选定的实现由 `paimon.rest.credential-manager.type` 决定：`default` 走上面的分派，
`noop` 不下发（读表接口返回 501）。策略在 `StorageRuntimePolicy` 里于启动期解析，
取值非法直接启动失败。

范围限定到单表、时效由 `credential.ttl-seconds` 控制（GCS 的 `lifespan` 更短时收窄），
对应 Polaris 的短时效、按需授权模型。未过期的凭据在
`paimon.rest.storage-credential-cache.max-entries` 的容量内被复用，同一张表反复读拿到的是同一份凭据。

**边界。** `S3` 与 `GCS` 下发的是服务端长期密钥或配置令牌，不是 STS / OAuth 换取的临时凭据——
这需要服务端持有云侧信任关系。`AZURE` 只传定位元数据：Polaris 的 `polaris.storage.*` 里没有
Azure 账户密钥，它靠服务进程自身的 Azure 标识签 SAS，本工程没有那层标识。
接入真实临时凭据时应替换 `DefaultStorageCredentialManager` 的对应分支，这是本实现预留的外部集成点。

### 4. 元数据模型按「当前值 + 历史版本」组织

- 表 schema：实体上保存当前版本，`paimon_table_schema` 保存全部历史版本，支撑 `rollback-schema`。
- 快照：按 `snapshot_id` 单调存放，`rollback` 丢弃目标之后的记录并回移表指针。
- 分区：以规范化 spec 键唯一标识，统计遵循规格的「负值为未测量、覆盖与累加语义不同」约定。
  唯一约束落在 spec 键的**摘要**上而非原文上，原因见第八节第 11 点。

### 5. 多态变更在服务层分派

规格中 `SchemaChange`（12 种）、`ViewChange`（6 种）、`FunctionChange`（6 种）都是以
`action` 判别的多态联合。实现上统一按 `List<Map<String,Object>>` 接收，在
`SchemaChangeService` 等处按 `action` 分派，未知 action 返回 400。
这样既保持了校验的严格性，也避免把多态解析绑死在特定 JSON 库的注解体系上。

---

## 五、Paimon 规格超出 Polaris 模型的部分

以下能力在 Iceberg / Polaris 的目录模型中没有直接对应物，属于 Paimon 表格式特有的目录职责：

| 能力 | 端点 | 说明 |
| --- | --- | --- |
| 分区登记与统计 | `.../partitions`、`/drop`、`/mark`、`/list-by-names`、`/list-by-filter` | 除分区本身外还维护记录数、文件数、体积、桶数，并支持标记分区完成 |
| 快照清单与版本快照 | `.../snapshot`、`.../snapshots`、`.../snapshots/{version}` | 直接暴露 base / delta / changelog / index manifest 指针 |
| 分支 | `.../branches` | 命名历史线，支持从标签派生、重命名、推进 |
| 标签 | `.../tags` | 快照的稳定别名 + 保留时长（`1d` / `12h` / `30m`） |
| 流式消费者 | `.../consumers`、`/reset` | 保存下游消费位点（下一个待消费快照） |
| 函数 | `.../functions` | `sql` / `file` / `lambda` 三类定义 |
| 语义视图 | `.../semantic-views` | 实验性；整篇保存模型文档，与 SQL 视图分开命名空间 |

---

## 六、管理面实现：把 RBAC 落到关系模型

管理 API 的端点契约见 `docs/management-api-contract.md`，判定细节与配置见
`docs/authorization.md`。这里只说明结构取向。

### 1. 授权只挂在 catalog role 上

链路是「主体 → principal role → catalog role → 资源授权」，两级关联都是多对多。
**权限不直接授予主体**，这是把权限的最小管理单元收在一个可复用的角色上：同一个
principal role 可以在多个 catalog 上挂不同的 catalog role，权限集合由角色链推导。

### 2. 权限蕴含关系按「有出处」编码

规格只列出权限取值，不描述蕴含（哪项权限包含哪几项）。实现上把 Polaris 访问控制文档里
**明文列举**的规则逐条编码进 `PrivilegeModel`，每条标注文档出处，文档没有明说的不做推断
（例如 `CATALOG_MANAGE_METADATA` 只说「full management」，未逐项列举，就不再向下展开）。

其中一个反直觉的取舍值得记录：`*_FULL_METADATA` 按**权限名前缀**推导，而不是取规格里
该层级的枚举全集。因为规格的层级枚举表达的是「该层级可以授予哪些权限」，而不是
「FULL_METADATA 蕴含哪些权限」——每个层级枚举里都列有 `CATALOG_MANAGE_ACCESS`，
若直接取全集，一个只应写表的角色就会获得授权管理能力，属于权限提升。

### 3. 授权判定集中在拦截器，映射表 fail-closed

管理 API 的路径不含被操作对象（主体名、角色名在请求体里），因此判定必须放在控制器内；
catalog API 的路径已含全部定位信息，判定统一放在拦截器。catalog API 的
「路径 → 所需权限」映射集中在 `CatalogAccessRules` 一张表里，**未登记映射的端点直接拒绝
而不是放行**——忘记登记会在第一次调用时立刻暴露为 403，而不是静默放行。
测试从运行时的请求映射里枚举全部端点核对覆盖率，新增端点若忘记登记会让构建失败。

### 4. 自举入口

权限判定需要一个「能创建第一个主体」的入口，否则全新部署无法启动授权体系。
实现上用配置表达：`authorization.service-admins` 给出服务管理员名单（管理规格的权限枚举里
没有「管理主体」这一层，说明 Polaris 把它交给服务管理员而非权限判定），
`bootstrap-principal` / `bootstrap-principal-role` 在启动时预置引导主体与该主体到各 catalog
`catalog_admin` 的角色链。

### 5. 乐观并发用 `entityVersion`

主体、principal role、catalog role 都带 `entityVersion`，`PUT` 请求携带
`currentEntityVersion`，不符时返回 409 由调用方重新读取。三个实体的版本字段各自独立递增。

---

## 七、Spark SQL 扩展：把管理 API 接到 SQL 里

完整说明见 `docs/spark-sql-extension.md`。这里记录两个影响架构的结论。

### 1. Spark 3.5 的 SQL 语法是封闭的，只能自备语法

`SqlBase.g4` 的 `statement` 规则没有类似 `.*? #unsupported` 的兜底分支，因此
`CREATE PRINCIPAL x` 在词法 / 语法阶段就会失败，无法用 visitor 拦下；扩展点
`ParserInterface` 也不提供「往已有语法里加规则」的能力。因此子模块自带一份只描述
管理语句的语法，在 `parsePlan` 里先试自己的解析器，解析失败（`ParseCancellationException`）
即把语句原样交回原生解析器。

判断依据是「整条语句能否匹配到 EOF」，而不是字符串前缀猜测。前者不会误吞 `SHOW TABLES`
这类原生语句，也不会因为残篇而产出两套错误信息。

### 2. ANTLR 版本必须与目标 Spark 发行版一致

这是实测踩到、后果最重的一条：ANTLR 的 ATN 序列化格式随版本变化。Spark 3.5.9 自带的
`SqlBaseLexer` 由 ANTLR 4.9.3 生成（ATN 序列化版本 3），若子模块把 `antlr4-runtime`
抬到 4.13.x（版本 4），运行时会**把 Spark 自己的 antlr4-runtime 顶掉**，于是
`SqlBaseLexer` 在类初始化阶段就失败，受损的是**整个会话的 SQL 解析**，而不只是本扩展。

因此 `antlr.runtime.version` 与 `antlr4-maven-plugin` 的版本都钉在与 Spark 一致的值上，
升级 `spark.version` 时必须同步核对。

### 3. 两个模块的依赖世界不能混

聚合 POM 不继承 `spring-boot-starter-parent`：其 `dependencyManagement` 会把 Spark 自带的
Jackson 2.15.2 抬到 2.21.x，并替换 netty 与 jersey，导致 Spark 侧出现难以定位的版本冲突。
改由 `paimon-rest-server` 自己继承 Spring Boot 父 POM，聚合 POM 只钉核心插件版本。

---

## 八、缺口与后续可做项

按优先级排列，均为当前实现有意留出的边界：

1. **管理面的令牌映射是静态配置**。`auth.token-principals` 决定「令牌 → 主体名」，
   新建主体无法立即取到可用令牌，需要登记配置并重启，或接入外部身份提供方。
   若要让管理面真正可用，这是优先要补的一处。
2. **行过滤与列脱敏策略未实现**。`auth` 端点返回空的过滤表达式与脱敏映射，
   仅做列存在性校验；实体标签（entity tags）同样未实现。
3. **多权限授权不是原子的**。规格没有批量授权端点，`GRANT a, b ON ...` 会顺序发出多次调用，
   中途失败时前面的已经生效。
4. **`list-by-filter` 未下推谓词**。规格允许返回超集，当前直接返回全部（可按名称前缀收窄）分区，
   由客户端复核；精确实现需接入 Paimon `Predicate` 反序列化与表达式求值。
5. **分页 token 为无状态偏移量**。大结果集下的深分页效率一般，可改为基于
   `(name, id)` 的游标。
6. **凭证下发未对接云 IAM**。见第四节第 3 点。
7. **表路径未随重命名迁移**。与 Paimon 服务端一致，重命名只改目录中的名称；
   若产品上要求迁移数据，需要在服务端编排对象存储操作。
8. **快照提交只有乐观并发，无跨实例悲观锁**。多实例部署下的写冲突依赖客户端重试。
9. **`CATALOG_FULL_METADATA` 与语义模型的延迟授权未实现**。前者在规格的权限 enum 中不存在，
   后者被 Polaris 文档标注为 deferred。
10. **规格对两个只读端点漏写了错误响应**。`GET /catalogs/{catalogName}/catalog-roles` 与
    `GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` 只声明了 `200`，
    没有 `403` / `404`（33 个 operation 中 31 个声明 `403`，27 个声明 `404`）。
    本实现仍对这两个端点鉴权，即按规格未声明也校验的失败关闭方向处理，
    因此调用者在无权限时拿到的是 `403` 而非规格里不存在的 `200`。
    逐行对照见 `docs/management-api-contract.md` §4.5。
11. **无上界的关键字段以摘要参与唯一约束**。MySQL 的索引键上限是 3072 字节
    （utf8mb4 下 768 字符），而 `paimon_partition.spec_key`（1024 字符，与 `table_id` 相加）
    与 `paimon_resource_grant.namespace_key`（命名空间路径拼接，规格未限制层级数）
    放不进唯一索引，建表会被 `ERROR 1071: Specified key was too long` 拒绝。
    做法是原文入库供等值查询、SHA-256 定长摘要（`spec_hash` / `namespace_hash`）参与唯一约束，
    索引长度与值的实际长度解耦而唯一性语义不变。摘要在 `@PrePersist` / `@PreUpdate`
    里由原文重算，不依赖调用方是否记得规范化。
    注意：这是**面向 MySQL 的必要改动**，不是可选的优化。
