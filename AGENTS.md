# AGENTS.md

给在本仓库里干活的编码代理看的操作说明。**人类读者请先看 [`README.md`](README.md)**——
它更完整；本文只写「代理必须先知道、且不知道就会做错」的东西。

> 本仓库所有文档、注释、提交信息都是**中文**。写代码时注释也写中文，
> 风格是**解释「为什么」而不是复述「做了什么」**（见下面第 7 节）。

---

## 0. 一句话

用 Spring Boot + JPA 实现 **Apache Paimon 的 REST Catalog API**，外加
**Apache Polaris 的 REST Management API**（RBAC 管理面）与一个 **Vue 3 管理控制台**；
另有 Spark SQL 语法扩展，用 SQL 管理主体、角色与授权。

三个对外面 + 一个界面（记牢这四个基址，改动都要落在对应面上）：

| 面 | 基址 | 规格来源 | 鉴权 |
| --- | --- | --- | --- |
| Catalog API（数据面） | `/v1/{prefix}/...` + `/v1/config` | `spec/rest-catalog-open-api.yaml` | `paimon.rest.auth.*` |
| Management API | `/api/management/v1/...` | `spec/polaris-management-service.yml` | 同上 |
| Console API + OAuth | `/api/console/v1/...`、`/api/catalog/v1/oauth/tokens` | 自有（见 `docs/console-auth.md`） | 同上（登录端点豁免） |
| 控制台界面 | `/console/`（SPA 深链回退到 `index.html`） | — | 门禁开关 `paimon.rest.auth.console.required` |

---

## 1. 硬约束（先读这节；违反会直接坏构建或坏部署）

1. **JDK 必须是 17 或 21，不能用 24+。** Spark 3.5 建测试会话要经过 Hadoop 的
   `Subject.getSubject`，该方法自 JDK 24 永久移除（JEP 486）。报错是几十条
   `UnsupportedOperationException: getSubject is not supported`，堆栈指向 Hadoop 内部，
   **与本仓库代码无关，且没有任何 JVM 参数能绕过**。`-DskipTests` 打包不受影响。
   跑测试必须显式指定 JDK：
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test        # macOS
   JAVA_HOME=/path/to/jdk-21 mvn test                        # 通用
   ```
2. **仓库里没有 Maven Wrapper。** 没有 `mvnw`、没有 `.mvn/`（`git ls-files | grep mvnw` 为空）。
   README 与 `docs/console.md` 里多处写的 `./mvnw` 在本仓库**跑不起来**，
   请一律换成系统 `mvn`（需 3.9+）。在无 Maven 的机器上构建前要先补 wrapper，
   或者走 Dockerfile（镜像自带 Maven）。
3. **ANTLR 运行时版本必须精确等于目标 Spark 发行版自带的版本。**
   现在钉的是 `4.9.3`（对应 Spark 3.5.9）。写高了会把 Spark 自己的 `antlr4-runtime`
   顶掉，症状是**整个会话连 `SELECT 1` 都解析不了**
   （`Could not deserialize ATN with version 3 (expected 4)`）。
   改 `spark.version` 时必须同步核对根 `pom.xml` 的 `antlr.runtime.version`。
4. **聚合 POM 刻意不继承 `spring-boot-starter-parent`。** 根 `pom.xml` 只是聚合器
   （同时也是 `paimon-rest-spark` 的父 POM），`paimon-rest-server` 自己以
   `<relativePath/>` 继承 Boot 父 POM。理由是 Boot 的 `dependencyManagement`
   会把 Spark 侧的 Jackson 2.15.2 抬到 2.21.x、替换 netty/jersey。
   **不要把 Boot 提为聚合 POM 的父。**
5. **`sql/schema-mysql.sql` 不要手改**，它由 `scripts/gen-mysql-ddl.sh` 从 JPA 实体生成
   （18 张表 / 19 个唯一约束 / 15 个索引），下次生成会覆盖。
   **改了实体就必须重跑生成**，否则启动时 `ddl-auto=validate` 会直接失败。
6. **构建服务端 jar（含控制台）不要用裸 `package`。** 控制台产物按内容哈希命名，
   `maven-resources-plugin` 只覆写不删除，`target/classes/static/console` 会累积历次旧产物
   （表现为 jar 里有多个 `index-*.js` 入口）。服务端 POM 已把一次 `clean` 绑到
   `initialize` 兜底，但最稳的还是 `clean package`。
7. **测试要分模块跑，不要用 `-Dtest=` 一次性筛全仓。**
   `paimon-rest-spark` 里没有匹配的测试类时 surefire 会报
   `No tests matching pattern` 并让整个构建 **BUILD FAILURE**（看起来像回归，其实不是）。
   限定单模块 + 加 `-Dsurefire.failIfNoSpecifiedTests=false`：
   ```bash
   mvn -o test -pl paimon-rest-server -Dtest=StaticCredentialApiTests -Dsurefire.failIfNoSpecifiedTests=false
   ```
8. **Spark 的 `spark.sql.extensions` 扩展点只能「包装」，不能「改写」语法。**
   Spark 3.5 的 `SqlBase.g4` 没有兜底分支，所以本模块自带一份只描述管理语句的小语法，
   「能否整句匹配到 EOF」成功才接管，失败原样交回原生解析器。
   所以 **管理语句的识别依据是语法，不是字符串前缀**。

---

## 2. 命令速查

```bash
# ---- 构建 / 测试（JDK 17 或 21）----
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o -DskipTests clean package   # 打包两个 jar
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o test                        # 全量测试（334 例）

# ---- 本地起服务（h2 profile 免 MySQL）----
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=h2 --server.port=8080
open http://127.0.0.1:8080/console/        # 默认账号密码 admin/admin

# ---- 端到端验收（都要先有一个运行中的实例）----
BASE=http://127.0.0.1:8080 ./scripts/api-sweep.sh            # Catalog API 全部 60 个 operation
BASE=http://127.0.0.1:8080 ./scripts/management-sweep.sh     # Management API 全部 33 个 operation
./scripts/e2e-spark-sql.sh                                   # 自己起 h2 实例、跑 Spark 三件套
BASE=... CLIENT_ID=... CLIENT_SECRET=... STATIC_TOKEN=... ./scripts/sweep-console-auth.sh
cd paimon-rest-console && BASE=... CLIENT_ID=... CLIENT_SECRET=... npm run check:login

# ---- 代码生成 / 文档校验（改规格或实体后）----
PYTHON=/path/to/python3 ./scripts/build-console.sh        # 装依赖→构建→校验（第 7 组查产物新鲜度）
python3 scripts/verify-console.py                         # 只校验不构建（多解释器时用 PYTHON=...）
python3 scripts/gen-management-contract.py                # 重生成 docs/management-api-contract.md
python3 scripts/verify-management-contract.py             # 反解文档与规格逐行比对
python3 scripts/verify-spark-sql-doc.py                   # Spark SQL 参考文档 ↔ 语法文件/规格/客户端
python3 scripts/verify-k8s-manifests.py                   # K8s 清单
./scripts/gen-mysql-ddl.sh                                # 重生成 sql/schema-mysql.sql
```

- 这几个 Python 脚本都解析 OpenAPI 规格，依赖 **PyYAML**
  （`python3 -m pip install --user PyYAML`）。宿主机常有多个解释器，
  装了 PyYAML 的未必是默认那个——用 `PYTHON=/path/to/python3 ./scripts/xxx.sh` 指定。
- `-o`（离线）在依赖已缓存时可用，本机实测可用。
- `api-sweep.sh` / `management-sweep.sh` 会建库建表再清理，**要求目标实例是初始状态**。

---

## 3. 仓库地图

```
paimon-rest-server/     Spring Boot 服务端（137 个 Java 文件）
  web/                  18 个控制器：Catalog API 按规格路径一一对应；
                        管理面 4 个（Principal / PrincipalRole / CatalogRole / ManagementCatalog）；
                        控制台 2 个（ConsoleAuth / ConsoleMeta）；OAuth 1 个；全局异常 1 个
  service/              33 个：目录解析、schema 变更、快照/版本、分区/分支/标签、
                        授权判定、凭据下发与缓存、控制台元数据、登录与令牌
  domain/entity/        20 个 JPA 实体（含 AuditedEntity 基类、JsonConverters）
  domain/repo/          18 个 Spring Data 仓储
  dto/                  17 个 DTO 文件，与两份 OpenAPI 规格的 schema 对应（多 record 合并成文件）
  support/              20 个公共设施：Json/Codecs、schema 字段树、分页、路径、摘要、
                        StorageConfigs（存储配置收口）、CredentialCipher（静态凭据加密）、
                        Jwt/Jwks、AuditPrincipal、ApiException
  config/               10 个：RestServerProperties、Bearer 鉴权、授权拦截器、CatalogAccessRules、
                        控制台 Web 配置、DataInitializer
  resources/static/     rest-catalog-open-api.yaml、polaris-management-service.yml、
                        console/（前端构建产物，见第 8 节陷阱）

paimon-rest-console/    Vue 3 + Vite（Element Plus）。构建产物直接落进服务端 classpath
paimon-rest-spark/      Scala 2.12 + Spark 3.5 + ANTLR 的自带语法扩展（21 条管理语句）
                          antlr4/…/ManagementSql.g4   语法
                          PaimonRestSqlParser.scala   注入 Spark 的 ParserInterface
                          ManagementAstBuilder.scala  语法树 → 命令
                          ManagementCommands.scala    21 个 RunnableCommand（→ HTTP）
                          client/                     JDK HttpClient + Jackson 2 的 REST 客户端
deploy/kubernetes/      kustomize 清单（有独立 README）
examples/spark-paimon-rest/  可运行示例
spec/                   两份 OpenAPI 规格的仓库副本（运行时可从 /<name> 下载）
sql/                    MySQL 建表语句（生成物）
scripts/                验收 / 生成 / 校验脚本（见上）
docs/                   12 份设计文档（见下）
```

`docs/` 里读哪份：

| 文档 | 什么时候读 |
| --- | --- |
| `catalog-api-endpoints.md` | 查 Catalog API 的 60 个端点、基址、前缀、授权映射 |
| `management-api-overview.md` | 33 个 operation 落在哪几个控制器、容易读错的语义 |
| `management-api-contract.md` | 管理 API 字段与权限枚举**逐字契约**（由规格生成，勿手改） |
| `data-model.md` | 改实体 / 看 18 张表划分、摘要列与审计列的理由 |
| `spark-sql-reference.md` | 语句级参考（含完整示例，第 11 节示例会被测试真跑） |
| `spark-sql-extension.md` | 改 Spark 扩展：做法、两个约束、测试分层 |
| `spark-paimon-rest-e2e.md` | Spark 经本服务端建表：连接配置、可执行 SQL、报错对照 |
| `authorization.md` | 权限实体链、授权映射、失败关闭的取舍 |
| `console.md` | 改控制台 / 排查「页面空列表」：分层、端点契约表、机械校验、加页面清单 |
| `console-table-detail.md` | 改表详情页：九个页签的数据来源、权限页签模型、破坏性操作 |
| `console-auth.md` | 配控制台登录：三种登录方式、两个开关的边界、令牌链、安全考量 |
| `polaris-capabilities-and-design.md` | 对比 Polaris、看整体设计与已知缺口 |

---

## 4. 核心设计不变量（改代码前先理解这几条）

- **前缀路由**：`{prefix}` 是 catalog 的对外标识，由 `GET /v1/config` 下发。
  未登记的 prefix 在 `paimon.rest.auto-create-catalog=true`（默认）时自动登记 →
  客户端可零配置接入，但**写错 prefix 会得到一个新的空 catalog 而不是报错**。
- **认证与授权分离**：`BearerAuthInterceptor` 只回答「调用者是谁」，
  RBAC 判定在授权层。两者分别由 `paimon.rest.auth.*` 与 `paimon.rest.authorization.*`
  控制，可以只开认证不开授权。
- **Catalog API 的授权映射集中在一张表**（`config/CatalogAccessRules.java`）：
  `/v1/**` 路径 → 所需权限，**未登记映射的端点直接拒绝而不是放行**。
  `CatalogEndpointAuthorizationTests` 从运行时请求映射枚举全部端点核对覆盖率——
  **新增端点若忘了登记映射，构建会失败**（这是有意设计的）。
- **多态变更不绑死在某个 JSON 库上**：`SchemaChange` / `ViewChange` / `FunctionChange`
  统一按 `List<Map<String,Object>>` 接收，服务层按 `action` 分派。
- **JSON 文档列**：schema、函数定义、分区 spec 等以 JSON 文本存单列，实体字段保持强类型；
  复杂结构走 `AttributeConverter`（`JsonConverters`）。
- **审计主体名归一化**（`support/AuditPrincipal.java`）：`owner` / `created_by` /
  `updated_by` 是 `varchar(255)`，主体名无长度上界（未映射的令牌本身就 272 字符）→
  超长时记 `sha256:<前 12 位>`，**绝不截断**（截断等于把凭据片段明文入库）。
  改这三个列相关逻辑时别绕过它。
- **存储配置收口在 `support/StorageConfigs.java`**：`validate` / `blank` / `copyWith` /
  `of` / `withoutSecrets` / `mergeStaticCredentials`。新增存储类型或字段时
  这几处都要同步，漏一处就是「读得出来写不进去」。
- **catalog 静态凭据**（本工程超出 Polaris 规格的扩展，见 `README.md` §5）：
  `secretAccessKey` **只写不读**（AES-GCM 加密落库，响应永不回显）→
  `PUT` 省略它表示**保持**，这是规格「整体替换」语义的**唯一例外**；
  清除要显式提交一对空串；与 `storageName` 互斥；下发优先级**高于一切服务端来源，
  且排在 `stsUnavailable` 判断之前**。

---

## 5. 按改动类型走的清单

**加一个 Catalog API 端点**
1. 控制器（`web/`）与 DTO（`dto/`）——字段名与规格逐字一致。
2. `config/CatalogAccessRules.java` 登记路径 → 权限（**漏了构建会红**）。
3. 测试：`PaimonRestCatalogApiTests` 之类；跑 `CatalogEndpointAuthorizationTests`。
4. `docs/catalog-api-endpoints.md` 更新端点清单。

**改 JPA 实体 / 加表**
1. 改 `domain/entity`（继承 `AuditedEntity` 才能拿到审计列）。
2. `./scripts/gen-mysql-ddl.sh` 重生成 `sql/schema-mysql.sql`（**必须**）。
3. 若有摘要列（SHA-256）或唯一约束变化，同步 `docs/data-model.md`。

**改管理 API / 权限**
1. 改规格副本与实现 → `python3 scripts/gen-management-contract.py` 重生成契约文档。
2. 若权限枚举变了：`python3 scripts/gen-management-privileges.py` 重生成
   `dto/Privilege.java`，再跑 `verify-spark-sql-doc.py`（它比对语法/规格/客户端三方）。
3. 管理语句要跟着改时进 `paimon-rest-spark`（语法 + AstBuilder + Command 三处）。

**改控制台**
1. 按 `docs/console.md` §5.3 的清单做（endpoints.js → api/*.js → views/ → router → 构建）。
2. `./scripts/build-console.sh` 重新构建（**产物必须一起提交**，见第 8 节陷阱）。
3. 页面渲染不写在服务端测试里；服务端侧只覆盖托管机制（`ConsoleApiTests`）。

**改配置项**
1. `config/RestServerProperties.java` 加字段 + javadoc（说明默认值、留空含义、非法取值行为）。
2. `paimon-rest-server/src/main/resources/application.yml` 加注释示例。
3. `README.md` §5 的配置表加一行。若涉及存储凭据，`docs/polaris-capabilities-and-design.md`
   里那份「下发优先级」表也要加一级。

---

## 6. 验证阶梯（改完按这个顺序自检，别只跑单元测试）

| 层 | 命令 | 抓什么 |
| --- | --- | --- |
| 单元/集成 | `mvn -o test`（334 例：服务端 258 + Spark 76） | 行为回归 |
| 端点多面 | `verify-console.py`（11 组 28 项）、`verify-management-contract.py`、`verify-spark-sql-doc.py`、`verify-k8s-manifests.py` | **跨文件的机械一致性**：前端字段名 vs 服务端 record、文档表格 vs 规格、语法文件 vs 规格 vs 客户端 |
| 契约（桩） | `ManagementSqlExecutionTests`（真实 SparkSession + 桩服务端） | 扩展真被加载、真执行 |
| 契约（真服务端） | `./scripts/e2e-spark-sql.sh` | 客户端与服务端的**实际**契约（桩只能证明客户端与自己的假设自洽） |
| HTTP 冒烟 | `api-sweep.sh` / `management-sweep.sh` / `sweep-console-auth.sh` | 规格端点全覆盖、真实报文与错误码 |
| 前端登录逻辑 | `npm run check:login` | 前端**发出的请求形状**与登录态流转（服务端测试看不到这一层） |

两条经验：

- **写了机械校验就要做变异测试**：故意把被校验的东西改错（比如 DTO 字段改名、
  前端类型列表少一项），确认校验脚本**报得出来**。不做的校验往往是空跑。
- 新增的校验组要顺手把「总数」写进 `README.md` / `docs/console.md`
  （脚本有组数与项数断言，文档里的数字也会被别的校验盯上）。

---

## 7. 代码与文档约定

- **注释写「为什么」**，特别是「为什么不选另一种做法」——本仓库的注释密度就是这个风格，
  见 `pom.xml` 顶部、`CredentialCipher`、`StorageConfigs`、`AuditPrincipal`。
- **Java**：Java 17（record / sealed）；javadoc 里引用代码用 `{@code ...}`；
  服务端是 Spring Boot 4 / Jackson 3 / Hibernate 7；**Spark 模块是 Jackson 2**，
  两边的 JSON 工具不要互串。
- **Strict 分层**：`web → service → domain/repo`；DTO 不泄漏实体，实体不外传（对外一律过 DTO 转换，
  且存储配置对外必须先 `withoutSecrets`）。
- **错误走 `ApiException`**，由 `GlobalExceptionHandler` 统一成规格要的报错形状；
  不要在控制器里手写响应体。
- **文档里的代码块要与代码同步**——`SparkSqlDocExamplesTests` 会真跑
  `docs/spark-sql-reference.md` 第 11 节的示例，**改坏示例会让构建失败**。
- **生成物一律不要手改**：`sql/schema-mysql.sql`、`docs/management-api-contract.md`、
  `dto/Privilege.java`。要改就改生成器或规格，然后重跑。
- **没有 CI**。上面所有校验都是手工/脚本触发的，所以「我没跑」= 「没人跑」。

---

## 8. 陷阱清单（都实测踩过）

1. **控制台产物目录当前被 `.gitignore` 忽略，但文档说它应当被跟踪。**
   `.gitignore` 末尾那行 `paimon-rest-server/src/main/resources/static/console` 让该目录
   `git ls-files` 为空，而 `docs/console.md` §8.2 与 `README.md` §9 都写着「产物提交在仓库里」。
   后果：**fresh clone + `docker build` 会得到一个没有控制台的服务端 jar**。
   在这个不一致被修掉之前，别假定控制台产物存在于干净检出中。
2. **控制台改了源码没重建** → 服务端打包出来的是上一版控制台，而且**不报错**，
   只是安静地少一个按钮。`verify-console.py` 第 7 组会查产物新鲜度，但要有人跑它。
3. **增量 `package` 会累积旧产物**（见第 1 节第 6 条）；判断是否干净：
   `ls paimon-rest-server/target/classes/static/console/assets | wc -l` 应与源码目录一致。
4. **`e2e-spark-sql.sh` 里三个测试类必须同批运行**：一个 JVM 只能有一个 SparkContext，
   而 `spark.sql.extensions` 只在建会话时生效。把用桩的 `ManagementSqlExecutionTests`
   混进同一批会让 `LiveSparkSession` 直接报错（这是有意的守卫，不是 bug）。
5. **`sweep-console-auth.sh` / `check:login` 一分钟跑超过 5 次会被服务端自己的失败限速拦下**
   ——那不是回归。两者缺 `BASE` / `CLIENT_ID` / `CLIENT_SECRET` 时不跑假失败，直接退出码 2。
6. **`api-sweep.sh` / `management-sweep.sh` 要求实例是初始状态**（脚本会建 `database sales` 再清理）。
7. **shell 里可能有 `http_proxy`** 指向本地代理，`curl http://127.0.0.1:...` 会失败——
   本地探测加 `--noproxy '*'`。
8. **`cdp.mjs`（若有）的 `eval` 只支持同步表达式**；浏览器自动化加
   `--no-sandbox --disable-dev-shm-usage`。
9. **长驻实例不要用裸 `nohup`**：会随 shell 回收。用后台任务方式启动，
   停止用 `pkill -9 -f paimon-rest-server`（8080 上出现过「任务失败但进程还活着」的幽灵实例）。

---

## 9. 安全红线

- **不要在输出、日志、提交信息、测试用例里回显真实的数据库口令或 AK/SK。**
  `paimon-rest-server/src/main/resources/application.yml` 的 `spring.datasource`
  里带着一套可直接连的 MySQL 地址与明文口令（本地调试配置，已被 git 跟踪）。
  要演示配置就写占位符（`jdbc:mysql://127.0.0.1:3306/paimon_catalog`、`paimon/paimon`），
  不要把原值复制到别处，也不要贴进回复。
- **静态凭据（`secretAccessKey`）是只写字段**：任何响应、日志、控制台展示都不许回显明文；
  读路径一律走 `StorageConfigs.withoutSecrets(...)`。新增读路径时检查这一条。
- **未配置 `paimon.rest.storage.credential-secret-key` 时必须 fail-closed**：
  保存静态凭据返回 400，而**不是**明文落库。
- 控制台的 `admin/admin` 默认口令在启动时会有告警；不要把默认口令写进示例截图或文档正文
  （`docs/console-auth.md` 里是成文的例外，它本来就是讲这个的）。

---

## 10. Git 协作约定

- **改动做完先汇报，不要自动 `git commit` / `git push`**，等明确指示。
- 不要提交 `target/`、`node_modules/`、`.idea/`、`.DS_Store`。
- 提交信息用中文，说清「改了什么 + 为什么」；跨模块的品牌串/配置键改名要在一个提交里做完，
  并跑第 6 节的校验阶梯。

---

## 参考

- 全局说明：[`README.md`](README.md)（工程结构、配置项、测试清单、部署、已知边界 20 条）
- 设计对照：[`docs/polaris-capabilities-and-design.md`](docs/polaris-capabilities-and-design.md)
- 数据模型：[`docs/data-model.md`](docs/data-model.md)
- 控制台：[`docs/console.md`](docs/console.md)、[`docs/console-auth.md`](docs/console-auth.md)
