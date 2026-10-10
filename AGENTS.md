# AGENTS.md

给在本仓库里干活的编码代理看的操作说明。**人类读者请先看 [`README.md`](README.md)**——
它更完整；本文只写「代理必须先知道、且不知道就会做错」的东西。

> 本仓库所有文档、注释、提交信息都是**中文**，注释风格是
> **解释「为什么」而不是复述「做了什么」**（见第 7 节）。

---

## 0. 一句话

用 Spring Boot + JPA 实现 **Apache Paimon 的 REST Catalog API**、**Apache Polaris 的
REST Management API**（RBAC 管理面）与一个 **Vue 3 管理控制台**；另有 Spark SQL 语法扩展，
用 SQL 管理主体、角色与授权。

三个对外面 + 一个界面（记牢这四个基址，改动都要落在对应面上）：

| 面 | 基址 | 规格来源 | 鉴权 |
| --- | --- | --- | --- |
| Catalog API（数据面） | `/v1/{prefix}/...` + `/v1/config` | `spec/rest-catalog-open-api.yaml` | `paimon.rest.auth.*` |
| Management API | `/api/management/v1/...` | `spec/polaris-management-service.yml` | 同上 |
| Console API + OAuth | `/api/console/v1/...`、`/api/catalog/v1/oauth/tokens` | 自有（`docs/console-auth.md`） | 同上（登录端点豁免） |
| 控制台界面 | `/console/`（SPA 深链回退 `index.html`） | — | 开关 `paimon.rest.auth.console.required` |

---

## 1. 硬约束（违反会坏构建或坏部署）

1. **JDK：测试可 17/21，服务端运行时只用 17。**
   - **测试用 17 或 21，不能 24+**：Spark 3.5 建测试会话要经过 Hadoop 的
     `Subject.getSubject`，该方法自 JDK 24 永久移除（JEP 486）→ 每个建会话的用例各报一条
     `UnsupportedOperationException: getSubject is not supported`，堆栈指向 Hadoop 内部，
     **与本仓库代码无关，且没有任何 JVM 参数能绕过**。`-DskipTests` 打包不受影响。
   - **服务端运行时必须 17，不能 18+**：JDK 18 的 `InetAddress` 解析器 SPI（JEP 418）
     撞上 `paimon-s3` 插件包的多版本 jar 布局 → `PluginFileIO` 切过类加载器后，那块作用域里
     **任何** `InetAddress` 解析都抛 `ServiceConfigurationError` → 对象存储的表写不出 schema /
     快照。症状：「S3 表建得出来、REST 读写也正常，桶里却没有 `schema/` 与 `snapshot/`」。
     `Dockerfile` 两阶段都钉 17 就是为了这条，机理详见其顶部注释与 README 第 6 节第 21 条。
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test   # 测试（macOS；通用写 /path/to/jdk-21）
   JAVA_HOME=/path/to/jdk-17 mvn -pl paimon-rest-server spring-boot:run   # 起服务端
   ```
2. **仓库里没有 Maven Wrapper。** 没有 `mvnw`、没有 `.mvn/`。README 与 `docs/console.md`
   里多处写的 `./mvnw` 在本仓库**跑不起来**，一律换成系统 `mvn`（3.9+）；
   无 Maven 的机器走 Dockerfile（镜像自带 Maven）。
3. **ANTLR 运行时版本必须精确等于目标 Spark 自带的版本**（现钉 `4.9.3`，对应 Spark 3.5.9）。
   写高了会把 Spark 的 `antlr4-runtime` 顶掉，症状是**整个会话连 `SELECT 1` 都解析不了**
   （`Could not deserialize ATN with version 3 (expected 4)`）。改 `spark.version` 时
   同步核对根 `pom.xml` 的 `antlr.runtime.version`。
4. **聚合 POM 刻意不继承 `spring-boot-starter-parent`。** 根 `pom.xml` 只是聚合器
   （同时是 `paimon-rest-spark` 的父 POM），`paimon-rest-server` 自己以 `<relativePath/>`
   继承 Boot 父 POM。理由：Boot 的 `dependencyManagement` 会把 Spark 侧的 Jackson 2.15.2
   抬到 2.21.x、替换 netty/jersey。**不要把 Boot 提为聚合 POM 的父。**
5. **`sql/schema-mysql.sql` 是生成物，不要手改**（`scripts/gen-mysql-ddl.sh` 从 JPA 实体生成，
   18 表 / 19 唯一约束 / 15 索引，下次生成会覆盖）。**改了实体就必须重跑生成**，
   否则启动时 `ddl-auto=validate` 直接失败。
6. **构建服务端 jar（含控制台）用 `clean package`，不要裸 `package`。** 控制台产物按内容哈希命名，
   `maven-resources-plugin` 只覆写不删除 → `target/classes/static/console` 累积历次旧产物
   （jar 里出现多个 `index-*.js`）。服务端 POM 已把一次 `clean` 绑到 `initialize` 兜底。
7. **测试分模块跑，不要用 `-Dtest=` 一次性筛全仓。** `paimon-rest-spark` 里没有匹配的测试类时，
   surefire 报 `No tests matching pattern` 并让整个构建 **BUILD FAILURE**（像回归，其实不是）。
   限单模块 + `-Dsurefire.failIfNoSpecifiedTests=false`：
   ```bash
   mvn -o test -pl paimon-rest-server -Dtest=StaticCredentialApiTests -Dsurefire.failIfNoSpecifiedTests=false
   ```
8. **Spark 的 `spark.sql.extensions` 只能「包装」，不能「改写」语法。** Spark 3.5 的
   `SqlBase.g4` 没有兜底分支，本模块自带一份只描述管理语句的小语法，「能否整句匹配到 EOF」
   成功才接管，失败原样交回原生解析器。**管理语句的识别依据是语法，不是字符串前缀。**
9. **Spark 模块里被 Scala 引用的 Java 类型不能写成 `record`。** IDE 混合编译让 scalac 直接
   解析 Java 源码取签名，而 Scala 2.12 的 Java 解析器不认识 `record` → 这些类型在 IDE 里
   整体消失（`value X is not a member of object ...`，连带 lambda 报 `missing parameter type`）；
   Maven 不受影响（`sendJavaToScalac=false`，scalac 读的是 class 文件），于是「Maven 绿、IDE 红」。
   被 Scala 摸到（**按名字或靠返回值推断都算**，如 `client.listCatalogs().asScala.map { c => c.name() }`）
   的类型一律写普通静态嵌套类，见 `ManagementApiClient` 里五个摘要类型及其上方注释。

---

## 2. 命令速查

```bash
# ---- 构建 / 测试（JDK 17 或 21）----
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o -DskipTests clean package   # 打包两个 jar
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o test                        # 全量测试

# ---- 本地起服务（h2 profile 免 MySQL）----
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=h2 --server.port=8080
open http://127.0.0.1:8080/console/        # 默认账号密码 admin/admin

# ---- 端到端验收（都要先有一个运行中的实例）----
BASE=http://127.0.0.1:8080 ./scripts/api-sweep.sh            # Catalog API 全部 60 个 operation
BASE=http://127.0.0.1:8080 ./scripts/management-sweep.sh     # Management API 全部 33 个 operation
./scripts/e2e-spark-sql.sh                                   # 自起 h2 实例，跑 Spark 三件套
BASE=... CLIENT_ID=... CLIENT_SECRET=... STATIC_TOKEN=... ./scripts/sweep-console-auth.sh
cd paimon-rest-console && BASE=... CLIENT_ID=... CLIENT_SECRET=... npm run check:login

# ---- 代码生成 / 文档校验（改规格或实体后）----
PYTHON=/path/to/python3 ./scripts/build-console.sh        # 装依赖→构建→校验（含产物新鲜度）
python3 scripts/verify-console.py                         # 只校验不构建（多解释器时用 PYTHON=...）
python3 scripts/gen-management-contract.py                # 重生成 docs/management-api-contract.md
python3 scripts/verify-management-contract.py             # 反解文档与规格逐行比对
python3 scripts/verify-spark-sql-doc.py                   # Spark SQL 文档 ↔ 语法文件/规格/客户端
python3 scripts/verify-k8s-manifests.py                   # K8s 清单
./scripts/gen-mysql-ddl.sh                                # 重生成 sql/schema-mysql.sql
```

- 这些 Python 脚本都解析 OpenAPI 规格，依赖 **PyYAML**（`python3 -m pip install --user PyYAML`）。
  宿主机常有多个解释器，装了 PyYAML 的未必是默认那个——用
  `PYTHON=/path/to/python3 ./scripts/xxx.sh` 指定。
- `-o`（离线）在依赖已缓存时可用。
- `api-sweep.sh` / `management-sweep.sh` 会建库建表再清理，**要求目标实例是初始状态**。

---

## 3. 仓库地图

```
paimon-rest-server/     Spring Boot 服务端
  web/                  Catalog API 按规格路径一一对应；管理面 4 个（Principal /
                        PrincipalRole / CatalogRole / ManagementCatalog）；控制台 2 个
                        （ConsoleAuth / ConsoleMeta）；OAuth 1 个；全局异常 1 个
  service/              目录解析、schema 变更、快照/版本、分区/分支/标签、授权判定、
                        凭据下发与缓存、控制台元数据、登录与令牌、表元数据物化
  domain/entity/        JPA 实体（含 AuditedEntity 基类、JsonConverters）
  domain/repo/          Spring Data 仓储
  dto/                  与两份 OpenAPI 规格的 schema 对应（多 record 合并成文件）
  support/              Json/Codecs、schema 字段树、分页、路径、摘要、StorageConfigs、
                        CredentialCipher、Jwt/Jwks、AuditPrincipal、ApiException
  config/               RestServerProperties、Bearer 鉴权、授权拦截器、CatalogAccessRules、
                        控制台 Web 配置、DataInitializer
  resources/static/     rest-catalog-open-api.yaml、polaris-management-service.yml、
                        console/（前端构建产物，见第 8 节陷阱 1）

paimon-rest-console/    Vue 3 + Vite（Element Plus），构建产物直接落进服务端 classpath
paimon-rest-spark/      Scala 2.12 + Spark 3.5 + ANTLR 的语法扩展（21 条管理语句）：
                        ManagementSql.g4（语法）→ PaimonRestSqlParser.scala（注入
                        ParserInterface）→ ManagementAstBuilder.scala（语法树 → 命令）→
                        ManagementCommands.scala（21 个 RunnableCommand → HTTP）；
                        client/ 为 JDK HttpClient + Jackson 2 的 REST 客户端
deploy/kubernetes/      kustomize 清单（有独立 README）
examples/spark-paimon-rest/  可运行示例
spec/                   两份 OpenAPI 规格的仓库副本（运行时可从 /<name> 下载）
sql/                    MySQL 建表语句（生成物）
scripts/                验收 / 生成 / 校验脚本
docs/                   12 份设计文档
```

`docs/` 读哪份：

| 文档 | 什么时候读 |
| --- | --- |
| `catalog-api-endpoints.md` | Catalog API 的 60 个端点、基址、前缀、授权映射 |
| `management-api-overview.md` | 33 个 operation 落在哪几个控制器、易读错的语义 |
| `management-api-contract.md` | 管理 API 字段与权限枚举**逐字契约**（生成物，勿手改） |
| `data-model.md` | 改实体 / 看 18 张表划分、摘要列与审计列的理由 |
| `spark-sql-reference.md` | 语句级参考（第 11 节示例会被测试真跑） |
| `spark-sql-extension.md` | 改 Spark 扩展：做法、两个约束、测试分层 |
| `spark-paimon-rest-e2e.md` | Spark 经本服务端建表：连接配置、可执行 SQL、报错对照 |
| `authorization.md` | 权限实体链、授权映射、失败关闭的取舍 |
| `console.md` | 改控制台 / 排查「页面空列表」：分层、端点契约表、机械校验、加页面清单 |
| `console-table-detail.md` | 改表详情页：九个页签的数据来源、权限页签模型、破坏性操作 |
| `console-auth.md` | 配控制台登录：三种登录方式、两个开关的边界、令牌链、安全考量 |
| `polaris-capabilities-and-design.md` | 对比 Polaris、看整体设计与已知缺口 |

---

## 4. 核心设计不变量（改代码前先理解）

- **前缀路由**：`{prefix}` 是 catalog 的对外标识，由 `GET /v1/config` 下发。未登记的 prefix 在
  `paimon.rest.auto-create-catalog=true`（默认）时自动登记 → 客户端可零配置接入，但
  **写错 prefix 会得到一个新的空 catalog 而不是报错**。
- **认证与授权分离**：`BearerAuthInterceptor` 只回答「调用者是谁」，RBAC 判定在授权层；
  分别由 `paimon.rest.auth.*` 与 `paimon.rest.authorization.*` 控制，可以只开认证不开授权。
- **Catalog API 的授权映射集中在一张表**（`config/CatalogAccessRules.java`）：`/v1/**` 路径 →
  所需权限，**未登记映射的端点直接拒绝而不是放行**。`CatalogEndpointAuthorizationTests`
  从运行时请求映射枚举全部端点核对覆盖率——**新增端点若忘了登记映射，构建会失败**（有意设计）。
- **多态变更不绑死在某个 JSON 库上**：`SchemaChange` / `ViewChange` / `FunctionChange`
  统一按 `List<Map<String,Object>>` 接收，服务层按 `action` 分派。
- **JSON 文档列**：schema、函数定义、分区 spec 等以 JSON 文本存单列，实体字段保持强类型；
  复杂结构走 `AttributeConverter`（`JsonConverters`）。
- **审计主体名归一化**（`support/AuditPrincipal.java`）：`owner` / `created_by` / `updated_by`
  是 `varchar(255)`，主体名无长度上界（未映射令牌本身 272 字符）→ 超长记 `sha256:<前 12 位>`，
  **绝不截断**（截断等于把凭据片段明文入库）。改这三个列的逻辑别绕过它。
- **存储配置收口在 `support/StorageConfigs.java`**：`validate` / `blank` / `copyWith` / `of` /
  `withoutSecrets` / `mergeStaticCredentials`。新增存储类型或字段时这几处都要同步，
  漏一处就是「读得出来写不进去」。
- **catalog 静态凭据**（超出 Polaris 规格的扩展，见 `README.md` §5）：`secretAccessKey`
  **只写不读**（AES-GCM 加密落库，响应永不回显）→ `PUT` 省略它表示**保持**，这是规格
  「整体替换」语义的**唯一例外**；清除要显式提交一对空串；与 `storageName` 互斥；
  下发优先级**高于一切服务端来源，且排在 `stsUnavailable` 判断之前**。
- **schema 与快照都要物化进仓库**（`TableMetadataService`）：`RESTCatalog.supportsVersionManagement()`
  恒返回 `true`，客户端因此走 `CatalogSnapshotCommit`（把快照 POST 给服务端），**自己那份写
  `snapshot-<n>` 的代码根本不会被实例化**；服务端必须替它写 `snapshot-<id>` + `LATEST`
  （回滚反向清理）。写的是**客户端原文**——规格未建模的字段（如 `properties` 序列号水位）
  丢了不报错，但会悄悄改变去重语义。`version` 列存的是**快照文件格式版本**
  （恒为 `Snapshot.CURRENT_VERSION`＝3），**不是自增序号**；`snapshots/{version}` 按
  `RESTApi.loadSnapshot` 契约解析：`EARLIEST` / `LATEST` / **数字即快照 id** / 其余当标签名。

---

## 5. 按改动类型走的清单

| 改动 | 步骤 |
| --- | --- |
| **加 Catalog API 端点** | ① 控制器（`web/`）+ DTO（`dto/`），字段名与规格逐字一致；② `config/CatalogAccessRules.java` 登记路径 → 权限（漏了构建红）；③ 测试 `PaimonRestCatalogApiTests` 之类 + `CatalogEndpointAuthorizationTests`；④ 更新 `docs/catalog-api-endpoints.md` |
| **改 JPA 实体 / 加表** | ① 改 `domain/entity`（继承 `AuditedEntity` 才有审计列）；② `./scripts/gen-mysql-ddl.sh` 重生成 `sql/schema-mysql.sql`（**必须**）；③ 摘要列（SHA-256）或唯一约束变化时同步 `docs/data-model.md` |
| **改管理 API / 权限** | ① 改规格副本与实现 → `python3 scripts/gen-management-contract.py`；② 权限枚举变了 → `scripts/gen-management-privileges.py` 重生成 `dto/Privilege.java`，再跑 `verify-spark-sql-doc.py`（比对语法/规格/客户端三方）；③ 管理语句跟随改动时进 `paimon-rest-spark`（语法 + AstBuilder + Command 三处） |
| **改控制台** | ① 按 `docs/console.md` §5.3 清单做（endpoints.js → api/*.js → views/ → router → 构建）；② `./scripts/build-console.sh` 重新构建（**产物必须一起提交**，见第 8 节陷阱 1）；③ 页面渲染不写进服务端测试，服务端侧只覆盖托管机制（`ConsoleApiTests`） |
| **改配置项** | ① `config/RestServerProperties.java` 加字段 + javadoc（默认值、留空含义、非法取值行为）；② `application.yml` 加注释示例；③ `README.md` §5 配置表加一行。涉及存储凭据时，`docs/polaris-capabilities-and-design.md` 的「下发优先级」表也加一级 |

---

## 6. 验证阶梯（按序自检，别只跑单元测试）

| 层 | 命令 | 抓什么 |
| --- | --- | --- |
| 单元/集成 | `mvn -o test`（服务端 267 + Spark 79，共 346） | 行为回归 |
| 端点多面 | `verify-console.py`、`verify-management-contract.py`、`verify-spark-sql-doc.py`、`verify-k8s-manifests.py` | **跨文件的机械一致性**：前端字段名 vs 服务端 record、文档表格 vs 规格、语法文件 vs 规格 vs 客户端 |
| 契约（桩） | `ManagementSqlExecutionTests`（真实 SparkSession + 桩服务端） | 扩展真被加载、真执行 |
| 契约（真服务端） | `./scripts/e2e-spark-sql.sh` | 客户端与服务端的**实际**契约（桩只能证明客户端与自己的假设自洽） |
| HTTP 冒烟 | `api-sweep.sh` / `management-sweep.sh` / `sweep-console-auth.sh` | 规格端点全覆盖、真实报文与错误码 |
| 前端登录逻辑 | `npm run check:login` | 前端**发出的请求形状**与登录态流转（服务端测试看不到） |

两条经验：

- **写了机械校验就要做变异测试**：故意把被校验的东西改错（DTO 字段改名、前端类型列表少一项），
  确认校验脚本**报得出来**。不做的校验往往是空跑。
- 新增校验组要顺手把「总数」写进 `README.md` / `docs/console.md`（脚本有组数与项数断言，
  文档里的数字也会被别的校验盯上）。

---

## 7. 代码与文档约定

- **注释写「为什么」**，尤其「为什么不选另一种做法」——本仓库注释密度就是这个风格，
  见 `pom.xml` 顶部、`CredentialCipher`、`StorageConfigs`、`AuditPrincipal`。
- **Java**：Java 17（record / sealed）；javadoc 引用代码用 `{@code ...}`；服务端是
  Spring Boot 4 / Jackson 3 / Hibernate 7，**Spark 模块是 Jackson 2**，两边 JSON 工具不要互串。
- **Strict 分层**：`web → service → domain/repo`；DTO 不泄漏实体，实体不外传
  （对外一律过 DTO，存储配置对外必须先 `withoutSecrets`）。
- **错误走 `ApiException`**，由 `GlobalExceptionHandler` 统一成规格要的报错形状；
  不要在控制器里手写响应体。
- **文档里的代码块要与代码同步**——`SparkSqlDocExamplesTests` 会真跑
  `docs/spark-sql-reference.md` 第 11 节示例，**改坏示例会让构建失败**。
- **生成物一律不要手改**：`sql/schema-mysql.sql`、`docs/management-api-contract.md`、
  `dto/Privilege.java`。要改就改生成器或规格再重跑。
- **没有 CI**：上面所有校验都是手工/脚本触发的，「我没跑」＝「没人跑」。

---

## 8. 陷阱清单（都实测踩过）

1. **控制台产物目录被 `.gitignore` 忽略，但文档说它应当被跟踪**（`docs/console.md` §8.2、
   `README.md` §9 都写着「产物提交在仓库里」）→ **fresh clone + `docker build` 会得到
   一个没有控制台的服务端 jar**。这个不一致修掉之前，别假定干净检出里有控制台产物。
2. **改了控制台源码没重建** → 打包的是上一版控制台，**不报错**，只是安静地少一个按钮。
   `verify-console.py` 第 7 组查产物新鲜度，但要有人跑它。判是否干净：
   `ls paimon-rest-server/target/classes/static/console/assets | wc -l` 应与源码目录一致。
3. **`e2e-spark-sql.sh` 里三个测试类必须同批运行**：一个 JVM 只能有一个 SparkContext，
   而 `spark.sql.extensions` 只在建会话时生效。把用桩的 `ManagementSqlExecutionTests`
   混进同一批会让 `LiveSparkSession` 报错（有意守卫，不是 bug）。
4. **`sweep-console-auth.sh` / `check:login` 一分钟跑超 5 次会被服务端自己的失败限速拦下**
   ——不是回归。两者缺 `BASE` / `CLIENT_ID` / `CLIENT_SECRET` 时不跑假失败，直接退出码 2。
5. **shell 里可能有 `http_proxy`** 指向本地代理，`curl http://127.0.0.1:...` 会失败——
   本地探测加 `--noproxy '*'`。
6. **`cdp.mjs`（若有）的 `eval` 只支持同步表达式**；浏览器自动化加
   `--no-sandbox --disable-dev-shm-usage`。
7. **长驻实例不要用裸 `nohup`**（会随 shell 回收）。用后台任务方式启动，停止用
   `pkill -9 -f paimon-rest-server`（8080 上出现过「任务失败但进程还活着」的幽灵实例）。

---

## 9. 安全红线

- **不要在输出、日志、提交信息、测试用例里回显真实的数据库口令或 AK/SK。**
  `application.yml` 的 `spring.datasource` 里带着一套可直接连的 MySQL 地址与明文口令
  （本地调试配置，已被 git 跟踪）。演示配置就写占位符
  （`jdbc:mysql://127.0.0.1:3306/paimon_catalog`、`paimon/paimon`），不要复制原值，也不要贴进回复。
- **静态凭据（`secretAccessKey`）只写**：任何响应、日志、控制台展示都不许回显明文；
  读路径一律走 `StorageConfigs.withoutSecrets(...)`。新增读路径时检查这条。
- **未配置 `paimon.rest.storage.credential-secret-key` 时必须 fail-closed**：
  保存静态凭据返回 400，而**不是**明文落库。
- 控制台的 `admin/admin` 默认口令在启动时会有告警；不要把默认口令写进示例截图或文档正文
  （`docs/console-auth.md` 是成文例外，它本来就讲这个）。

---

## 10. Git 协作约定

- **改动做完先汇报，不要自动 `git commit` / `git push`**，等明确指示。
- 不要提交 `target/`、`node_modules/`、`.idea/`、`.DS_Store`。
- 提交信息用中文，说清「改了什么 + 为什么」；跨模块的品牌串/配置键改名要在一个提交里做完，
  并跑第 6 节的校验阶梯。

---

## 参考

- 全局说明：[`README.md`](README.md)（工程结构、配置项、测试清单、部署、已知边界 21 条）
- 设计对照：[`docs/polaris-capabilities-and-design.md`](docs/polaris-capabilities-and-design.md)
- 数据模型：[`docs/data-model.md`](docs/data-model.md)
- 控制台：[`docs/console.md`](docs/console.md)、[`docs/console-auth.md`](docs/console-auth.md)
