# 访问控制：模型、判定与 catalog API 授权映射

本文说明本服务如何实现 Polaris Management API 的 RBAC 模型，以及这套模型如何作用到
Paimon Rest Catalog API（`/v1/**`）上。

- 规格基线：`spec/polaris-management-service.yml`
- 权限语义出处：<https://polaris.apache.org/in-dev/unreleased/managing-security/access-control/>
- 契约细节（端点、权限枚举、请求体形状）：`docs/management-api-contract.md`

---

## 1. 认证与授权是两件事

服务端把它们分成两层，分别由两组配置控制：

| 层 | 回答的问题 | 实现 | 配置前缀 |
| --- | --- | --- | --- |
| 认证 | 调用者是谁 | `BearerAuthInterceptor` 取 `Authorization: Bearer <token>`，交给 `TokenAuthenticationService` 认证成主体名，写入 `RequestContext` | `paimon.rest.auth.*` |
| 授权 | 这个主体能做什么 | 管理 API 在控制器内判定；catalog API 在 `AuthorizationInterceptor` 统一判定 | `paimon.rest.authorization.*` |

认证是**一条链**，令牌来自三个来源之一（顺序即尝试顺序）：

1. **静态令牌**（`paimon.rest.auth.tokens`）——服务端配置的长期凭据，给机器用。
   `paimon.rest.auth.token-principals` 给出「令牌 → 主体名」映射；
   **未登记的令牌退化为「令牌本身即主体名」**。
2. **控制台访问令牌**——本服务端签发（HS256）的 JWT，有两条路换来：
   主体用 `clientId` / `clientSecret` 走 OAuth 2.0 客户端凭据，
   或者用 `paimon.rest.auth.console.password.users` 里配置的**控制台账号 + 密码**。
   **两条路签发的令牌完全一样**，对授权链路没有区别。
3. **OIDC 令牌**——外部身份提供方签发的 JWT（RS256 / ES256），本服务端拉 JWKS 验签。

三种来源在 `TokenAuthenticationService` 合流，**下游只看主体名**：
授权判定与审计字段都不需要知道令牌是配置里的字符串、控制台签的 JWT，还是 IdP 签的 JWT。
加一种认证方式只改那一个类。完整设计见 [`console-auth.md`](console-auth.md)。

> **控制台账号与主体的边界。** 配置里的控制台账号**不是主体**：它是「开门的钥匙」，
> 账号名默认直接当主体名用（可用 `console.password.principals` 映射成别的主体名）。
> 因此用 `admin/admin` 登录成功之后**什么也看不到是正常的**——除非 `admin`
> 被列进 `service-admins` 或被授予了某个 principal role。
> 服务端会为这种「账号在授权链路里查不到」的情况在启动日志里告警。
> 另外控制台门禁（`console.required`）只挡住浏览器界面，
> **不改变本文件描述的授权判定**，也不是安全边界（见
> [`console-auth.md` §2.1](console-auth.md#21-两个开关门禁不是安全边界)）。

`paimon.rest.auth.enabled=false`（默认）时，数据面（`/v1/**`、`/api/catalog/v1/**`、
`/api/management/v1/**`）的请求都以 `paimon.rest.auth.principal`（默认 `anonymous`）通过，
便于本地开发与既有部署平滑升级。

**一个例外**：控制台自己另有一层门禁 `console.required`（默认 `true`），
它让 `/api/console/v1/**` 在 `auth.enabled=false` 时**也**要求令牌。
这一层不参与授权判定——它只是把浏览器拦在登录页，`/api/management/v1/**` 依旧匿名可调。

---

## 2. 授权模型

### 2.1 实体链路

```
principal ──(N:M)── principal role ──(N:M)── catalog role ──(1:N)── 资源授权
                                   （catalog role 归属于某个具体 catalog）
```

- **principal**：服务主体。创建时一次性返回 `clientId` 与明文 `clientSecret`，服务端只保存摘要。
- **principal role**：服务级角色，与 catalog 无关，可跨 catalog 复用。
- **catalog role**：归属于某个 catalog 的角色（`/catalogs/{catalogName}/catalog-roles`）。
- 两处都是**多对多**：一个主体可持有多个 principal role；一个 principal role 可绑定多个
  catalog role；一个 catalog role 也可被多个 principal role 绑定。

授权只加在 catalog role 上，这是关键取舍：权限的最小管理单元是 catalog role，
主体的权限集合由它持有的角色链推导而来，而不是直接挂在主体上。

### 2.2 授权的载体

每条授权记录形如「某个 catalog role 在某个资源上拥有某项权限」：

| `type` | 作用域 | 定位字段 |
| --- | --- | --- |
| `catalog` | 该 catalog 下的全部资源 | 无 |
| `namespace` | 该命名空间及其子树 | `namespace[]` |
| `table` / `view` / `policy` / `semantic-model` | 仅该对象 | `namespace[]` + `{tableName,viewName,policyName,semanticModelName}` |

对象级定位字段名按类型不同，这是规格的设计（见 `management-api-contract.md` 第 3 节）。

### 2.3 判定过程

对一个「主体 + 资源 + 要求的权限」三元组：

1. 由主体解析出全部 principal role，再由这些角色解析出**目标 catalog 下**的全部 catalog role；
2. 取这些 catalog role 持有的全部授权记录；
3. 按资源层级筛选与目标资源相关的授权——catalog 级覆盖其下所有资源，namespace 级覆盖该
   命名空间子树，对象级只覆盖该对象；
4. 把筛选出的权限做**蕴含展开**后判断是否覆盖所需权限。

第 4 步的蕴含关系由 `PrivilegeModel` 编码，规则逐条标注了 Polaris 文档原文出处；
**文档没有明说的不做推断**。

其中两点值得单独说明：

- **`*_FULL_METADATA` 按权限名前缀推导，不按规格的层级枚举取全集。**
  规格的 6 个权限 enum 表达的是「该层级*可以授予*哪些权限」，而不是「该层级的
  FULL_METADATA *蕴含*哪些权限」。规格的每个层级枚举里都列有 `CATALOG_MANAGE_ACCESS`，
  Namespace 级还列有 `CATALOG_MANAGE_CONTENT` 与 `CATALOG_MANAGE_METADATA`。若把
  `TABLE_FULL_METADATA` 直接展开成 Table 级枚举全集，一个只应写表的角色就会获得授权管理
  能力，属于权限提升。因此按「`TABLE_FULL_METADATA` ⇒ 名称以 `TABLE_` 开头的权限」推导，
  从而自然排除全部 `CATALOG_*` 权限。
- **`TABLE_FULL_METADATA` 不含数据权限。** 文档明确 `TABLE_READ_DATA` 与
  `TABLE_WRITE_DATA` 需单独授予，因此这两项从展开结果中排除。

### 2.4 服务管理员

管理规格的权限枚举里没有「管理主体与 principal role」这一层权限，说明 Polaris 把这类操作
交给服务管理员而不是权限判定。因此本实现用名单表达：

- `paimon.rest.authorization.service-admins`（默认 `[root]`）：名单内的主体可管理 principal 与
  principal role，并可在任意 catalog 上操作角色与授权；
- `paimon.rest.authorization.bootstrap-principal`（默认 `root`）与
  `bootstrap-principal-role`（默认 `service_admin`）：启动时预置的引导链
  「主体 → 服务角色 → 各 catalog 的 `catalog_admin`」。

没有这条引导链，全新部署里没有任何主体能创建第一个主体，授权体系无法自举。
`credential-rotation-required` 的语义由客户端在首次登录后轮换凭据来配合，服务端只负责
在创建时标记。

---

## 3. catalog API 的授权映射

管理 API 的路径本身不含被操作对象（主体名、角色名都在请求体里），而 catalog API 的路径
已经包含全部定位信息。因此两处的判定位置不同：

| API | 判定位置 | 原因 |
| --- | --- | --- |
| `/api/management/v1/**` | 控制器内 | 实体名来自请求体，拦截器在此时拿不到 |
| `/v1/**` | `AuthorizationInterceptor` 统一判定 | 路径中已含 prefix、database、table 等全部定位信息 |

catalog API 的「路径 → 所需权限」映射集中在 `CatalogAccessRules` 一张表里，而不是分散到
各控制器的注解上。这样做的理由是**未登记的路径会被拒绝，而不是被放行**：

- 拦截器解析出 `Requirement` 后调用授权判定；解析不出映射时直接返回 403，并在消息中说明
  「该端点没有登记授权规则」；
- 忘记给新端点登记映射，会在第一次调用时立刻暴露为 403，而不是静默放行；
- 测试 `CatalogEndpointAuthorizationTests` 从运行时的 `RequestMappingHandlerMapping` 里枚举
  全部 `/v1/{prefix}/**` 端点，逐一核对是否已登记映射，新增端点若未登记会让构建失败。

映射的粒度选择（摘要）：

| 端点形态 | 要求的权限 |
| --- | --- |
| `GET /v1/config` | 无（唯一的公开端点） |
| `GET /databases` | `NAMESPACE_LIST` |
| `POST /databases` | `NAMESPACE_CREATE` |
| `GET /databases/{db}` | `NAMESPACE_READ_PROPERTIES` |
| `DELETE /databases/{db}` | `NAMESPACE_DROP` |
| `POST /databases/{db}` | `NAMESPACE_WRITE_PROPERTIES` |
| `GET .../tables` | `TABLE_LIST` |
| `POST .../tables`、`.../register` | `TABLE_CREATE` |
| `GET .../tables/{t}` | `TABLE_READ_PROPERTIES` |
| `POST .../tables/{t}` | `TABLE_WRITE_PROPERTIES` |
| `DELETE .../tables/{t}` | `TABLE_DROP` |
| `.../tables/{t}/commit`、`/rollback` | `TABLE_WRITE_DATA` |
| `.../tables/{t}/token`、`/auth` | `TABLE_READ_DATA` |
| `.../partitions/list*` | `TABLE_READ_DATA` |
| `.../partitions/drop`、`/mark` | `TABLE_WRITE_DATA` |
| `.../branches`、`.../tags`、`.../snapshot(s)` | 读 `TABLE_READ_PROPERTIES`、写 `TABLE_WRITE_PROPERTIES` |
| `.../views/**` | `VIEW_*` |
| `.../semantic-views/**` | `SEMANTIC_MODEL_*` |
| `GET /tables`（跨命名空间列举） | `TABLE_LIST`，作用域为 catalog 级 |
| `POST /tables/rename` | `TABLE_WRITE_PROPERTIES` |
| `GET /tables/id/{id}` | `TABLE_READ_PROPERTIES`，作用域为 catalog 级 |

两处需要说明的取舍：

- **函数端点使用 `NAMESPACE_*` 权限。** 规格没有为函数单独定义权限层级，因此不发明新的权限
  取值，按命名空间级权限判定。
- **按 id 查表只认 catalog 级授权。** 该端点跨命名空间定位表，路径里没有 `{database}`，
  无法确定命名空间作用域，因此只接受 catalog 级（或更宽）的授权。

### 3.1 403 与 404 的优先级

授权判定在 catalog 不存在时**先行放行**，让后续流程返回 404。理由是：如果把「资源不存在」
也判成 403，调用者将无法区分「我没有权限」和「这个 catalog 根本不存在」，排查成本会显著上升。
catalog 存在但主体无权时仍返回 403。

---

## 4. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `paimon.rest.auth.enabled` | `false` | 是否要求 `Authorization: Bearer <token>` |
| `paimon.rest.auth.principal` | `anonymous` | 认证关闭或未带令牌时使用的主体名 |
| `paimon.rest.auth.tokens` | 空 | 允许的静态令牌列表 |
| `paimon.rest.auth.token-principals` | 空 | 静态令牌 → 主体名映射；未登记的令牌退化为「令牌即主体名」（审计列超长的记摘要，见 §6 第 8 条） |
| `paimon.rest.auth.access-token.ttl` | `1h` | 控制台签发的访问令牌有效期 |
| `paimon.rest.auth.access-token.issuer` | `paimon-rest` | 访问令牌的 `iss` |
| `paimon.rest.auth.access-token.signing-key` | 空 | HS256 签名密钥（Base64，≥32 字节）；留空则每次启动随机生成 |
| `paimon.rest.auth.console.required` | `true` | 浏览器进控制台是否必须先登录；**只作用于界面，不是安全边界** |
| `paimon.rest.auth.console.password.enabled` | `true` | 是否允许用配置里的账号密码换令牌 |
| `paimon.rest.auth.console.password.users` | `admin: admin` | 控制台账号表（明文）；**生产必须改** |
| `paimon.rest.auth.console.password.principals` | 空 | 账号名 → 主体名映射；不填则两者同名 |
| `paimon.rest.auth.console.client-credentials.enabled` | `true` | 是否允许用主体凭据换令牌 |
| `paimon.rest.auth.console.oidc.*` | 关闭 | 外部身份提供方登录；见 [`console-auth.md`](console-auth.md) |
| `paimon.rest.authorization.enabled` | `false` | 是否对管理 API 与 catalog API 启用 RBAC 判定 |
| `paimon.rest.authorization.service-admins` | `[root]` | 服务管理员主体名单 |
| `paimon.rest.authorization.bootstrap-principal` | `root` | 启动时预置的引导主体；留空则不预置 |
| `paimon.rest.authorization.bootstrap-principal-role` | `service_admin` | 启动时预置的引导角色链 |

`authorization.enabled=false` 时不做 RBAC 判定，认证仍然生效——即所有请求以读取到的
主体名通过，便于在既有部署上先只做认证。

---

## 5. 走通一次授权

以下流程基于 `scripts/e2e-spark-sql.sh` 与 `scripts/management-sweep.sh` 使用的配置，
服务端以 `root` 为服务管理员启动。

```bash
M=http://127.0.0.1:8080/api/management/v1
AUTH='Authorization: Bearer root'
J='Content-Type: application/json'

# 1) 建主体（返回的一次性 clientSecret 只出现这一次）
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"principal":{"name":"etl","properties":{"team":"data"}}}' "$M/principals"

# 2) 建 principal role 与 catalog role
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"principalRole":{"name":"etl_reader"}}' "$M/principal-roles"
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"catalogRole":{"name":"reader"}}' "$M/catalogs/paimon/catalog-roles"

# 3) 装角色：主体 → principal role → catalog role
curl -s -X PUT -H "$AUTH" -H "$J" \
  -d '{"principalRole":{"name":"etl_reader"}}' "$M/principals/etl/principal-roles"
curl -s -X PUT -H "$AUTH" -H "$J" \
  -d '{"catalogRole":{"name":"reader"}}' \
  "$M/principal-roles/etl_reader/catalog-roles/paimon"

# 4) 授权：命名空间级读 + 表级读数据
curl -s -X PUT -H "$AUTH" -H "$J" \
  -d '{"grant":{"type":"namespace","namespace":["default"],"privilege":"NAMESPACE_LIST"}}' \
  "$M/catalogs/paimon/catalog-roles/reader/grants"
curl -s -X PUT -H "$AUTH" -H "$J" \
  -d '{"grant":{"type":"table","namespace":["default"],"tableName":"orders",
                "privilege":"TABLE_READ_DATA"}}' \
  "$M/catalogs/paimon/catalog-roles/reader/grants"

# 5) 核对
curl -s -H "$AUTH" "$M/catalogs/paimon/catalog-roles/reader/grants"

# 6) 用该主体的凭据访问 catalog API。两条路都行：
#    a) 刚建主体时拿到的一次性 clientId / clientSecret → 换一个访问令牌（推荐）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/catalog/v1/oauth/tokens \
  -d grant_type=client_credentials \
  -d client_id="$CLIENT_ID" -d client_secret="$CLIENT_SECRET" | jq -r .access_token)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "$J" \
  -d '{"name":"sales","options":{}}' "http://127.0.0.1:8080/v1/paimon/databases"
#    b) 或者把长期令牌登记进 paimon.rest.auth.tokens / token-principals 再用（给机器用）
curl -s -X POST -H "Authorization: Bearer etl-token" -H "$J" \
  -d '{"name":"sales","options":{}}' "http://127.0.0.1:8080/v1/paimon/databases"
# -> 403：reader 只有 NAMESPACE_LIST，没有 NAMESPACE_CREATE
```

用 Spark SQL 管理语句完成同样的装配见 `docs/spark-sql-extension.md` 与 `docs/spark-sql-reference.md` 第 8 节。

### 5.1 从引擎侧验一遍：两个身份跑真 SQL

上面的 curl 验的是**服务端自己的判定**。同一件事在引擎侧还有一层要验：请求是 Paimon 的
`SparkCatalog` 发出来的，403 会不会被客户端改写成别的错、被拒绝的写入有没有落数据，
只有真 SQL 才知道。`SparkSqlAuthorizationTests` 做的就是这件事——同一个 catalog、同一张表，
令牌 `alice` 建库建表加读写，令牌 `bob` 只能读，且断言拒绝报文点名了**缺失的那一项权限**
（`TABLE_CREATE` / `TABLE_WRITE_DATA` / `NAMESPACE_CREATE`）、被拒之后数据没变。

跑法与所需的两个令牌见 `docs/spark-paimon-rest-e2e.md` §6.1。两个身份共用一份 SparkContext、
只换 `spark.sql.catalog.<catalog>.token`；另外只读身份也必须有 `NAMESPACE_READ_PROPERTIES`
——`SparkCatalog` 懒加载时会去读默认命名空间 `default` 的属性，这不是「读数据」的权限。

---

## 6. 已知边界

按优先级排列，均为当前实现有意留出的边界：

1. **静态令牌的映射是静态配置。** `token-principals` 决定「静态令牌 → 主体名」，
   改映射要重启。但**新建主体不受此限**：创建时会一次性返回 `clientId` 与明文
   `clientSecret`，用它们走 `POST /api/catalog/v1/oauth/tokens` 换一个访问令牌即可立即
   调用管理 API（见 [`console-auth.md`](console-auth.md)）。静态令牌这条路才是给
   「凭据写死在引擎配置里、之后不再变更」的机器用的。
2. **访问令牌无法即时撤销。** 服务端不保存会话，令牌在 `access-token.ttl` 内一直有效；
   要立刻失效只能轮换 `access-token.signing-key`（会作废所有人的令牌）。
3. **`CATALOG_MANAGE_METADATA` 的蕴含未展开。** Polaris 文档只说它「enables full management of
   the catalog, catalog roles, namespaces, and tables」，未像 `CATALOG_MANAGE_CONTENT` 那样逐项
   列举，因此只把它作为被蕴含项，不再向下展开。
4. **`CATALOG_FULL_METADATA` 未实现。** 文档提到该权限，但规格的权限 enum 中没有这个取值。
   规格补上后在此处加规则即可。
5. **行过滤与列脱敏策略未实现。** `auth` 端点返回空的过滤表达式与脱敏映射，只做列存在性校验。
6. **授权不校验被授权对象是否存在。** 可以对尚未创建的表授予 `TABLE_READ_DATA`，授权记录先于
   对象存在是允许的（这也是先建授权再把表接管的正常顺序）。
7. **无权限缓存。** 每次请求按主体查授权链路，权限变更即时生效，代价是热点路径上的若干次查询。
8. **审计列里超过 255 字符的主体名记的是摘要。** 授权判定用的是原始主体名（长度不限），
   落库到 `owner` / `created_by` / `updated_by` 时才归一化：放得下的原样存，
   放不下的记 `sha256:<前 12 位>`。这条边界的起因是「认不出的令牌会退化为令牌即主体名」，
   而控制台访问令牌长 272 个字符，直接入库会让写入以
   `Data too long for column 'created_by'` 失败。配 `token-principals`
   把令牌映射成真正的名字才是正解，见 [`console-auth.md`](console-auth.md)。
8. **语义模型授权的延迟语义未实现。** 文档标注为 deferred 的源表 / 视图权限校验与读时传播
   检查不在本层表达。
