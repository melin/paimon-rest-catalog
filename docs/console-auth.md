# 控制台登录与令牌验证

浏览器访问 `/console/` 的登录方案：**三种登录方式**（默认要求登录），一条认证链，
一个 Bearer 令牌通道。客户端凭据与 OIDC 两种对标
[Apache Polaris Console](https://github.com/apache/polaris-tools/blob/main/console/README.md)。

- 面向「怎么配、怎么调」→ 看 [§4](#4-方式一用户名--密码默认) 与 [§5](#5-方式二oauth-20-客户端凭据)
- 面向「为什么这么设计」→ 看 [§2](#2-结论与取舍) 与 [§10](#10-安全考量)
- 面向「出问题了查哪儿」→ 看 [§8](#8-认证链一个令牌来自哪里) 与 [§12](#12-已知边界)

> **一句话说清两个开关。** `paimon.rest.auth.console.required`（默认 `true`）要求**浏览器**
> 先登录才能打开控制台，只作用于控制台界面与 `/api/console/v1/**`；
> `paimon.rest.auth.enabled`（默认 `false`）要求 `/v1/**`、`/api/catalog/v1/**`、
> `/api/management/v1/**` 带令牌。**只开门禁时数据接口仍然匿名可调，它不是安全边界**——
> 详见 [§2.1](#21-两个开关门禁不是安全边界)。

---

## 1. 这份文档解决什么

控制台的登录方式换过两轮，这里把每一轮解决什么、留下什么说清楚：

| | 账号从哪来 | 令牌 | 问题 |
| --- | --- | --- | --- |
| 第一版 | 服务端配置的用户名 + 密码 | 存在服务端内存里的**会话** | 内存会话多实例不互认、重启全体掉线；与 Polaris 生态接不上 |
| 第二版 | 主体（`paimon_principal`）的 `client_id` / `client_secret`，或外部 IdP | **自包含 JWT** | 装好之后进不去：控制台默认要求登录，而一个主体都还没有 |
| 本版 | 上面两种都保留，**另加**服务端配置的账号密码（默认 `admin/admin`） | 同上，一个 Bearer 令牌 | 默认密码是明文配置，必须改（启动时告警） |

第三行是这次改动的全部：**把「装好就能进控制台」这条路补回来，但不恢复任何服务端会话**。
具体地说：

- 用户名 + 密码的账号来自**配置文件**（`paimon.rest.auth.console.password.users`），
  不是数据库。它回答「**谁能打开控制台**」，授权链路回答「**进来之后能做什么**」——
  两者可以同名，也可以用 `password.principals` 映射成不同名。
- 换回来的仍然是 `AccessTokenService` 签发的自包含 JWT，与客户端凭据那条**完全一样**。
  服务端依旧不保存任何会话，因此多实例互认、重启不掉线这两条性质没有被破坏。
- 它排在登录页第一个页签：不需要先建主体，也不依赖外部 IdP。

**为什么可以「恢复」而不算回退。** 上一版删掉的是「配置账号 + 内存会话」这个组合，
问题出在会话上而不是账号上。只把账号接回来、令牌仍然自包含，就没有把那三个问题带回来。

---

## 2. 结论与取舍

| | 方式一：用户名 + 密码 | 方式二：客户端凭据 | 方式三：OIDC |
| --- | --- | --- | --- |
| 凭据 | 服务端配置里的账号，默认 `admin/admin` | 主体的 `client_id` / `client_secret` | 外部身份提供方（Keycloak / Auth0 / Okta…） |
| 令牌由谁签发 | 本服务端（HS256） | 本服务端（HS256） | IdP（RS256 / ES256） |
| 本服务端怎么验 | 自己的签名密钥 | 自己的签名密钥 | 拉 IdP 的 JWKS 验签 |
| 默认 | **开启** | **开启** | 关闭 |
| 需要先建主体 | **不需要** | 需要 | 不需要（但需要 IdP 侧登记） |
| 适合 | 装好就想进控制台；机器只有几个人用 | 让控制台与引擎用同一份凭据 | 已有统一登录（SSO），人员账号不该散落成主体 |
| 配置开关 | `...console.password.enabled` | `...console.client-credentials.enabled` | `...console.oidc.enabled` |

三者**可以同时开**：登录页会出现三个页签，顺序由服务端下发
（见 [§7](#7-登录引导服务端说了算)）。三条路径最终产出的都是「一个 Bearer 令牌」，
此后走同一条通道——控制台的其余代码不知道、也不需要知道令牌是怎么来的。

第四个降级入口是 `paimon.rest.auth.tokens` 里的**静态令牌**（一直存在，给机器用）。
它永远排在登录页最后一页签，不鼓励人用。

### 2.1 两个开关：门禁不是安全边界

这是本次改动最容易误读的一点，因此单独写在这里。

| 配置项 | 默认 | 管什么 | 关掉会怎样 |
| --- | --- | --- | --- |
| `paimon.rest.auth.console.required` | `true` | **控制台界面**与 `/api/console/v1/**` | 浏览器不用登录就能打开控制台；数据接口是否受保护不受它影响 |
| `paimon.rest.auth.enabled` | `false` | `/v1/**`、`/api/catalog/v1/**`、`/api/management/v1/**` | 数据接口匿名可调 |

因此**默认部署（`console.required=true` + `auth.enabled=false`）的真实状态**是：

```bash
# 浏览器：被拦在登录页，必须先登录
open http://localhost:8080/console/

# 但数据面完全敞开：
curl -s http://localhost:8080/v1/config?warehouse=paimon       # 200
curl -s http://localhost:8080/api/management/v1/catalogs       # 200
```

**这层门禁只挡住界面。** 它解决的问题是「误入的人看到一堆管理表单」，
不是「数据被保护」。要保护数据必须把 `auth.enabled` 设为 `true`：

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/v1/config?warehouse=paimon'  # 200
```

这句话在**四处**如实写着：本节、`RestServerProperties.Auth.Console` 的 javadoc、
`application.yml` 的注释、以及登录页与「连接设置」页的界面文案。
服务端在「门禁开着而 `auth.enabled=false`」时还会在启动日志里告警——
默认配置必然触发这条告警，这是有意的：**默认值应当是「装好能用」，但用的人必须知道边界在哪**。

### 关键决定：自包含令牌，而不是服务端会话

令牌是 JWT，服务端不保存它。代价与收益都很明确：

| | 自包含 JWT | 服务端会话表 |
| --- | --- | --- |
| 多实例 | 任一实例签发的令牌其余实例都认 | 需要共享存储，否则不互认 |
| 重启 | 已登录的人不掉线 | 全体掉线 |
| 撤销 | **做不到**，只能等过期或轮换签名密钥（会作废所有人的令牌） | 删一行即可 |
| 暴露窗口 | 等于 TTL（默认 1 小时） | 等于会话超时 |

选前者：控制台是运维工具，**令牌泄露的窗口用 TTL 兜住**（默认 1 小时，配置项就是给它调的），
而多实例互认与重启不掉线是每天都在发生的事。这个取舍必须写进运维认知里，
因此登录页与设置页都会明确提示「退出登录只是忘掉本机令牌」。

---

## 3. 对标 Polaris Console 的哪几点

Polaris Console 的 `README.md` 定义了两种模式，本工程逐条对齐：

| Polaris Console | 本工程 | 说明 |
| --- | --- | --- |
| `VITE_OAUTH_TOKEN_URL` 默认 `${API}/api/catalog/v1/oauth/tokens` | 同路径 | **路径一致**，因此按 OAuth 2.0 写的客户端与 Polaris 的 console 能直接指向本服务端 |
| `VITE_SCOPE` 默认 `PRINCIPAL_ROLE:ALL` | 同取值 | 本工程只接受这一个（理由见 [§5.3](#53-scope-只接受-principal_roleall)） |
| Client Credentials 为默认模式 | 默认开启 | |
| OIDC 模式：`VITE_OIDC_ISSUER_URL` / `..._CLIENT_ID` / `..._REDIRECT_URI` / `..._SCOPE` | `...console.oidc.*` 同名同义 | 回调地址示例同为 `/console/auth/callback` |
| 用 `.well-known/openid-configuration` 自动发现端点 | 同 | 服务端发现后下发给控制台 |
| 公开客户端 + PKCE，前端无密钥 | 同 | |

**差异**：Polaris 是「服务端签发 + 服务端验签」的两种模式；本工程把两者拆得更开——
客户端凭据的令牌由本服务端签，OIDC 的令牌由 IdP 签、本服务端只验。
对控制台来说两者一样（都是一个 Bearer 令牌），对运维来说区别只在于
「出问题时该去看本服务端的日志还是 IdP 的日志」。

**本工程多出来的一个方式**：用户名 + 密码（默认 `admin/admin`）。
Polaris Console 没有这一路——它假设你手上已经有一个主体或一个 IdP。
本工程补上它，是因为默认部署 `auth.enabled=false` 时一个主体都不会被创建，
而控制台默认要求登录（见 [§2.1](#21-两个开关门禁不是安全边界)）：
没有这条路，装好之后第一个界面就是「进不去」。它的接口刻意**不**做成 OAuth 形状
（`POST /api/console/v1/login`，JSON 进 JSON 出），免得被误当成 RFC 6749 的一部分。

---

## 4. 方式一：用户名 + 密码（默认）

### 4.1 请求

```
POST /api/console/v1/login
Content-Type: application/json

{"username": "admin", "password": "admin"}
```

```bash
curl -s -X POST http://localhost:8080/api/console/v1/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}'
```

响应（驼峰字段——本端点是控制台的扩展端点，不是 OAuth 规范端点）：

```json
{
  "accessToken": "eyDfkXyuDuNRJsJ6…",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "principal": "admin"
}
```

拿到之后用法与另两条完全一样：`Authorization: Bearer <accessToken>`。
`principal` 是**映射之后**的主体名（见 [§4.3](#43-账号与主体的边界)），
前端据此在跳转前显示「以谁的身份进来了」。

### 4.2 失败

| HTTP | 报文/错误 | 触发条件 |
| --- | --- | --- |
| 400 | `username and password are both required` | 缺用户名或密码（含空请求体、只有空白的用户名） |
| 400 | `username/password login is not enabled on this server: …` | 本方式被关掉，或控制台根本不要求登录（后者见 [§2.1](#21-两个开关门禁不是安全边界)） |
| 401 | `invalid username or password` | **用户名不存在或密码不对，一字不差** |
| 429 | `too many failed login attempts; retry after N seconds` | 同一来源 + 用户名连续失败超限（默认 5 次 / 分钟） |

用本工程的 `ErrorResponse` 形状（`{message, resourceType, resourceName, code}`）而不是
RFC 6749 的 `{error, error_description}`——调用方只有控制台，
而「与工程内一致」在这里比「与规范一致」有用（对照
[§5.2](#52-响应) 里令牌端点的相反选择）。

**为什么要 400 与 401 分开。** 「服务端没提供这个功能」「你没填全」「凭据不对」
是三种不同的行动：换配置 / 补参数 / 换密码。混成一个码会让人去改错东西。

**为什么 401 的报文对两种失败一字不差。** 区分它们会让攻击者先枚举出有效用户名，
把爆破面从「两个都不知道」缩小成「只知道密码」。而且不止报文一致：
用户名不存在时也**照样走一次摘要比较**（对固定假值 `ABSENT_PASSWORD_DIGEST`），
否则响应耗时本身就会泄露「这个用户名存在」。

### 4.3 账号与主体的边界

账号配在 `paimon.rest.auth.console.password.users`，默认只有一个 `admin: admin`：

```yaml
paimon:
  rest:
    auth:
      console:
        password:
          enabled: true
          users:
            admin: change-me            # 明文密码，必须改（启动日志会告警）
            alice: "${ALICE_CONSOLE_PASSWORD}"
          principals:                   # 可选：账号名 → 主体名
            alice: alice@example.com
          rate-limit:                   # 比客户端凭据更严：人定的密码可能只有几个字符
            enabled: true
            max-failures: 5
            window: 1m
```

- **账号不是主体。** 主体（`paimon_principal`）是授权链路的一端，有 `client_id` /
  `client_secret`；这里的账号只是「开门的钥匙」，不进授权链路。
- **默认同名。** 没配 `principals` 映射时，主体名就是用户名。因此 `admin` 这个账号
  会以主体名 `admin` 去查授权链路——**它不等于管理员**：除非
  `paimon.rest.authorization.service-admins` 里有它，或者它被授予了某个 principal role，
  否则登录成功但什么也看不到。
- **`principals` 映射是给这种情况用的**：控制台开门用的 id 与授权用的主体名不一致
  （例如 SSO 里是邮箱）。办法与静态令牌的 `token-principals` 完全相同。
- **哪些账号在授权链路里查不到，启动时会告警。** 这是最常见的配置陷阱：
  登录成功、界面空白，而日志里只有一句「什么也看不到」。

**为什么口令是明文配置。** 它不是主体密钥，没有「下发一次、之后只存哈希」的流程；
配置里能放 `${ENV_VAR}`，生产应当用它。服务端在检测到「还在用内置默认密码」时
会在启动日志里明确告警（本条也写进了 [§12](#12-已知边界)）。

### 4.4 限速

与客户端凭据共用 `LoginAttemptLimiter`，但**各有各的策略**（默认 5 次 / 分钟 vs 10 次 / 分钟）：
前者猜的是 32 字节随机密钥，后者猜的是人定的密码，共用一个阈值只能取折中。
桶按「来源地址 + 用户名」分——只用 IP 会在 NAT 后误伤整栋楼的人，
只用用户名则编造用户名就能绕开。

**只在失败时计数**，成功不清零、也不计入：清零零会让攻击者靠偶尔猜中重置计数，
计入则会让一个正常开着的控制台被自己的心跳流量锁住。

---

## 5. 方式二：OAuth 2.0 客户端凭据

### 5.1 请求

```
POST /api/catalog/v1/oauth/tokens
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=<主体 client_id>&client_secret=<主体 secret>
```

`scope` 可省略（默认 `PRINCIPAL_ROLE:ALL`）。也接受 JSON 请求体，
以及 RFC 6749 §2.3.1 的 HTTP Basic 传凭据：

```bash
# 表单
curl -s -X POST http://localhost:8080/api/catalog/v1/oauth/tokens \
  -d grant_type=client_credentials -d client_id=root -d client_secret="$SECRET"

# HTTP Basic（值需先 form-urlencode，含 ':' 或非 ASCII 的密钥必须这么做）
curl -s -X POST http://localhost:8080/api/catalog/v1/oauth/tokens \
  -u 'root:secret' -d grant_type=client_credentials
```

### 5.2 响应

成功（RFC 6749 §5.1，字段名是规范要求的 snake_case，不是本工程其余 DTO 的驼峰）：

```json
{"access_token":"eyJhbGciOiJIUzI1NiJ9...","token_type":"bearer","expires_in":3600,"scope":"PRINCIPAL_ROLE:ALL"}
```

失败（RFC 6749 §5.2）：

| HTTP | `error` | 触发条件 |
| --- | --- | --- |
| 400 | `invalid_request` | 缺 `client_id` / `client_secret`；请求体不是合法 JSON；本方式未开启 |
| 400 | `invalid_scope` | `scope` 不是 `PRINCIPAL_ROLE:ALL` |
| 400 | `unsupported_grant_type` | `grant_type` 不是 `client_credentials` |
| 401 | `invalid_client` | 凭据不符（**不区分** clientId 不存在与密钥不对） |
| 429 | `temporarily_unavailable` | 同一来源 + clientId 连续失败超限 |

401 会带 `WWW-Authenticate: Bearer`（规范要求，通用客户端库据此决定是否重试）。

> **为什么错误形状不走本工程的 `ErrorResponse`。** 这个端点的消费者是通用 OAuth 客户端，
> 不是控制台一个。「与规范一致」在这里比「与工程内一致」重要。
> 因此本端点的**每一条**失败路径都保证是 RFC 6749 形状——包括请求体解析失败这种
> 发生在服务层之前的错误（`OAuthTokenController` 把整个方法体包在一个 try 里，
> 就是为了这条性质不依赖「每个分支都记得自己转换」）。

### 5.3 `scope` 只接受 `PRINCIPAL_ROLE:ALL`

本工程的授权判定是把主体的**全部** principal role 并起来算权限
（`AuthorizationService`），没有 Polaris 那种「以某个角色访问」的层次。

因此接受 `PRINCIPAL_ROLE:read_only` 这样的 scope 会是**最糟的一种**：
调用方以为权限被收窄了，实际拿到的是主体的全部权限，而这一点在测试里不会暴露
（请求全都成功）。**拒绝是可见的**，静默忽略不是。

---

## 6. 方式三：OIDC 授权码 + PKCE

### 6.1 时序

```
浏览器                  控制台(静态)              本服务端               IdP
  │                        │                        │                   │
  │  打开 /console/        │                        │                   │
  │───────────────────────>│                        │                   │
  │                        │  GET /api/console/v1/auth                  │
  │                        │───────────────────────>│                   │
  │                        │                        │ 拉 .well-known/…  │
  │                        │                        │──────────────────>│
  │                        │  authEnabled/methods/oidc{端点}/session    │
  │                        │<───────────────────────│                   │
  │  点「使用 SSO 登录」    │                        │                   │
  │  302 → IdP /authorize?code_challenge=…&state=…  │                   │
  │────────────────────────────────────────────────────────────────────>│
  │  302 → /console/auth/callback?code=…&state=…                        │
  │<────────────────────────────────────────────────────────────────────│
  │                        │  POST IdP /token（code + code_verifier）   │
  │                        │───────────────────────────────────────────>│
  │                        │  id_token（RS256，aud=client_id）          │
  │                        │<───────────────────────────────────────────│
  │                        │  Bearer id_token → GET /api/console/v1/auth
  │                        │───────────────────────>│                   │
  │                        │                        │ 拉 JWKS 验签        │
  │                        │                        │──────────────────>│
  │                        │  authenticated=true, principal=…           │
  │                        │<───────────────────────│                   │
  │  进入控制台             │                        │                   │
```

四步里的每一步都不可省：

1. **服务端下发端点**（而不是前端硬编码）。控制台是构建期打包的静态资源，读不到环境变量；
   发现文档也只有服务端能拉。前端硬编码一份必然漂移，而漂移的表现是「点了登录按钮没反应」。
2. **PKCE `state` 校验**。这是防「别人构造一个回调把受害者登成攻击者账号」的唯一手段。
   不校验的话，攻击者只要诱使受害者打开带自己 `code` 的回调链接即可。
3. **换令牌在浏览器里做**。`code_verifier` 只存在于发起登录的那个标签页的 `sessionStorage`，
   服务端没有它——这正是把「用授权码换令牌」的权利绑在发起登录的浏览器上的机制。
4. **换到令牌后仍要服务端验一次**（`auth.adopt` → `GET /api/console/v1/auth`）。
   受众不匹配、签发者不对、主体取不到，这三种失败在浏览器里看不出来。
   在这里挡住，比让人带着废令牌进控制台、然后到处 401 要清楚得多。

### 6.2 控制台发出去的授权请求

```
GET <authorization_endpoint>
  ?response_type=code
  &client_id=<oidc.client-id>
  &redirect_uri=<oidc.redirect-uri>
  &scope=openid profile email
  &state=<随机 16 字节 base64url>
  &code_challenge=<base64url(SHA-256(verifier))>
  &code_challenge_method=S256
```

`code_verifier` 是 32 字节随机数的 base64url（43 字符，落在 RFC 7636 要求的 43–128 内）。

### 6.3 用哪个令牌

IdP 的令牌响应里可能同时有 `id_token` 与 `access_token`。控制台**优先用 `id_token`**：

- OIDC 规定它一定是 IdP 签名的 JWT；
- 它的 `aud` 就是 `client_id`；
- 本服务端要的 `iss` / `aud` / `exp` / `sub` 它全都有。

`access_token` 不保证是 JWT（不少 IdP 发的是不透明串），受众也常是另一个资源标识。

> 因此服务端的 `paimon.rest.auth.console.oidc.audience` **应当填 IdP 里的 client-id**。
> 留空则不校验受众——同一 issuer 下还有别的应用时，那意味着别的应用的令牌也能进来。

### 6.4 主体名从哪儿来

`paimon.rest.auth.console.oidc.principal-claim`（默认 `sub`）。取到的值要与下列之一对得上，
否则**登录成功但什么也看不到**：

- `paimon_principal.name`（`service-admins` 之外的普通用户），或
- `paimon.rest.authorization.service-admins` 里的名字（服务管理员）。

这一条是最常见的配置陷阱，启动日志会就此告警。

---

## 7. 登录引导：服务端说了算

`GET /api/console/v1/auth`（**不需要令牌**）返回登录页渲染前必须知道的一切：

```json
{
  "authEnabled": false,
  "consoleRequired": true,
  "methods": ["password", "client-credentials", "static-token"],
  "tokenEndpoint": "/api/catalog/v1/oauth/tokens",
  "oidc": {
    "issuer": "https://keycloak.example.com/realms/EXTERNAL",
    "authorizationEndpoint": "https://…/protocol/openid-connect/auth",
    "tokenEndpoint": "https://…/protocol/openid-connect/token",
    "clientId": "paimon-console",
    "redirectUri": "http://localhost:8080/console/auth/callback",
    "scope": "openid profile email"
  },
  "session": {
    "authenticated": true,
    "principal": "alice",
    "source": "console-access-token",
    "expiresAtMillis": 1760000000000,
    "credentialRotationRequired": false
  }
}
```

（上面这一份是**默认部署**的样子：`authEnabled=false` 而 `consoleRequired=true`，
也就是 [§2.1](#21-两个开关门禁不是安全边界) 描述的那种组合。`oidc` 只在
`consoleRequired` 为真时才会去发现文档——控制台不需要登录时，发现它没有意义。）

四个设计点：

- **`methods` 的顺序就是页签的顺序。** 用户名密码在最前（不需要先建主体），
  静态令牌永远最后。前端不再自己决定「该推荐哪种」。
- **`authEnabled` 与 `consoleRequired` 分开返回，而且必须同时如实。**
  前者说明数据面是否受保护，后者说明浏览器要不要先登录。控制台据此决定
  要不要在登录页写那句「这层门禁只挡住界面」——少了任何一个字段，
  那句话要么写不出来，要么会写成「本服务端已受保护」。
- **`oidc` 拉不到发现文档时是 `null`**，此时 OIDC 不进 `methods`。
  列出来而点了没反应，比不列出来更难排查。
- **`session` 是「你递过来的令牌算不算数」，不是「你之前登录过没有」**（服务端没有会话）。
  它总是返回一个对象，前端不必为读「是否已登录」而处理两种形状。

`/console/**` 的**静态资源本身不在鉴权范围**：浏览器无法在文档请求上带
`Authorization` 头，要保护它只能靠 Cookie 会话，而那会引入 CSRF 面——
对一组不含数据的 JS/CSS 不值得。真正的边界在 API 上。

---

## 8. 认证链：一个令牌来自哪里

`TokenAuthenticationService` 按顺序问三个来源，任一条认出令牌就停下：

1. **静态令牌**（`paimon.rest.auth.tokens`）——字符串包含判断，零解析成本。
2. **控制台访问令牌**——本服务端签的 JWT，一次 HMAC 验签。
3. **OIDC 令牌**——IdP 签的 JWT，可能是 RSA 验签，还可能要刷新 JWKS（最贵）。

顺序是有意的：把最便宜的放前面，让最常见的调用路径（引擎用静态令牌）不付验签的钱。

**三种来源在这里合流，下游看不到区别。** 授权判定按主体名查授权链路，
审计字段记主体名——它们都不需要知道令牌是配置里的字符串、控制台签的 JWT，
还是 Keycloak 签的 JWT。这正是「认证可替换、授权只有一套」应当呈现的样子。

### 认证范围的默认是「保护」，豁免只有三条

`WebConfig` 给 `/v1/**`、`/api/catalog/v1/**`、`/api/management/v1/**`、
`/api/console/v1/**` 全部挂了拦截器，再**精确排除**三条：

| 豁免路径 | 为什么必须在鉴权之外 |
| --- | --- |
| `GET /api/console/v1/auth` | 登录页要先知道服务端支持哪种登录，才谈得上发起登录 |
| `POST /api/console/v1/login` | 它也是用来换令牌的，同理 |
| `POST /api/catalog/v1/oauth/tokens` | 同上，RFC 6749 的客户端凭据流程 |

默认保护 + 显式豁免（而不是默认放行 + 显式保护）：新增端点时忘记配置的后果是
「访问不了」，而不是「谁都能访问」。

**豁免之外还有一层范围判定。** 拦截器挂在这些前缀上，但「要不要真的查令牌」
由 `BearerAuthInterceptor.requiresToken()` 决定：

```java
if (properties.getAuth().isEnabled()) {
    return true;                       // 整体开鉴权：全部受管路径都要令牌
}
return properties.getAuth().getConsole().isRequired()
        && request.getRequestURI().startsWith("/api/console/v1/");   // 只开门禁：仅控制台
```

**已知取舍**：只开门禁时，`/api/catalog/v1/**`（除被豁免的令牌端点）也仍然匿名可调。
拦截器只能按 URI 前缀判断，而「只开门禁」这个配置本身就是用户明确接受
「这不是安全边界」的表达（见 [§2.1](#21-两个开关门禁不是安全边界)）。
把它做得更细只会让「门禁」看起来像安全边界，而它不是。

---

## 9. 配置

```yaml
paimon:
  rest:
    auth:
      # 数据面（/v1/**、/api/catalog/v1/**、/api/management/v1/**）是否要求令牌。
      # 默认 false —— 装好就能用；生产应当打开（见 §2.1）
      enabled: true
      # ---- 控制台签发的访问令牌（方式一、方式二共用） ----
      access-token:
        ttl: 1h                      # 浏览器一次工作会话的长度；没有撤销机制时不宜大
        issuer: paimon-rest
        signing-key: ""              # Base64，解码后 ≥32 字节。生成：openssl rand -base64 48
      # ---- 浏览器登录方式，三种可同时启用 ----
      console:
        # 控制台自己是否要求先登录。默认 true：只挡住界面，
        # /v1/** 与管理 API 是否受保护仍看上面的 enabled（§2.1）
        required: true
        password:
          enabled: true              # 方式一（默认开启，装好就能进）
          users:
            admin: admin             # 明文；生产必须改，且建议写成 ${ENV_VAR}
          principals: {}             # 账号名 → 主体名；不填则两者同名（§4.3）
          rate-limit:                # 比客户端凭据更严：猜的是人定的密码
            enabled: true
            max-failures: 5
            window: 1m
        client-credentials:
          enabled: true              # 方式二（默认开启）
          rate-limit:
            enabled: true
            max-failures: 10
            window: 1m
        oidc:
          enabled: false             # 方式三
          issuer-uri: "https://keycloak.example.com/realms/EXTERNAL"
          client-id: "paimon-console"
          redirect-uri: "http://localhost:8080/console/auth/callback"
          scope: openid profile email
          principal-claim: sub
          audience: "paimon-console" # 建议填 client-id；留空不校验受众
          metadata-cache-ttl: 10m
          jwks-cache-ttl: 1h
          clock-skew: 60s
```

`signed-key` 留空时进程启动随机生成：**本地单机调试可用，但重启会让所有人掉线、
多实例之间互相 401**。生产必须显式配置，启动日志会就此告警。
格式不对或解码后短于 32 字节则**直接启动失败**——签名密钥配错不是可以带病运行的配置。

**启动时会告警的三件事**（`DataInitializer.warnAboutAuthConfiguration`）：

1. 门禁开着而 `auth.enabled=false` —— 默认配置必然触发，提醒你数据面还是敞开的；
2. 还在用内置默认密码 `admin/admin`；
3. 配了密码账号但控制台不要求登录（那这条路根本不会被走到）。

---

## 10. 安全考量

| 风险 | 处置 |
| --- | --- |
| 令牌端点被爆破 | `LoginAttemptLimiter`：按「来源地址 + 标识」分桶，**只在失败时计数**，桶数上限 4096（满了先清过期，仍满则停止为新来源计数并告警）。客户端凭据默认 10 次 / 分钟。 |
| **控制台口令被爆破** | 同上，但策略更严（默认 5 次 / 分钟）：`clientSecret` 是 32 字节随机值，而人定的密码可能只有几个字符。 |
| 枚举出真实 clientId | 「clientId 不存在」与「密钥不对」返回**一字不差**的报文，且前者也走一次摘要比较（对固定的假摘要），避免从耗时分出两种失败。 |
| **枚举出真实用户名** | 同上：「用户名不存在」与「密码不对」一字不差，耗时也拉平（§4.2）。 |
| 凭据比较耗时泄露 | `Digests.sha256Hex` + `MessageDigest.isEqual`（常量时间）。静态令牌列表也逐个比摘要而非原文。 |
| CSRF（伪造回调把受害者登成攻击者） | PKCE 的 `state` 校验；不匹配直接拒绝并清掉本地中间状态。 |
| 前端泄露密钥 | 不存任何密钥。OIDC 用公开客户端 + PKCE；`client_secret` 只在方式二的表单里由使用者输入，用完不落盘（落盘的是换回来的令牌）。 |
| **门禁被误当成安全边界** | 服务端在启动日志里告警；`/api/console/v1/auth` 如实返回两个字段；登录页与设置页都写明「只挡住界面，`curl` 仍能拿到数据」（§2.1）。 |
| **默认口令留在生产** | 启动日志告警；`docs` 与 `application.yml` 都标明必须改；`users` 支持 `${ENV_VAR}`。 |
| 令牌被截获 | 短 TTL（默认 1h）限制暴露窗口。**没有撤销机制**，这是自包含令牌的固有代价。 |
| JWKS 轮换导致新令牌全被拒 | 遇到不认识的 `kid` 时**强制重取一次 JWKS**；刷新失败沿用旧值（IdP 短暂不可用不踢人）。 |
| `alg: none` / 算法混淆 | 显式白名单 HS256/RS256/384/512/ES256/384/512，拒绝 `none` 与不认识的算法；HS256 与 RS256 分开用各自的验签函数，不存在按令牌自述算法选实现的路径。 |
| OIDC mix-up（被劫持的 IdP 自称他人） | 发现文档里的 `issuer` 必须与配置的 `issuer-uri` 一致，否则整份文档作废。 |
| 时钟漂移导致合法令牌被拒 | `clock-skew`（默认 60s）同时作用于 `exp` 与 `nbf`。 |

`/console/**` 不在鉴权范围，因此**不引入 Cookie 会话**，也就没有 CSRF 面需要防。
令牌只存在 `localStorage`（键 `paimon.console.token`），由 `client.js` 统一附加到请求头。

---

## 11. 控制台侧的实现位置

| 文件 | 职责 |
| --- | --- |
| `src/api/endpoints.js` | 三条端点的 method/path（`auth` / `passwordLogin` / `token`），受 `verify-console.py` 校验 |
| `src/api/client.js` | 统一附加 `Authorization`；`form` 体编码；认 RFC 6749 的 `{error, error_description}` |
| `src/api/console-auth.js` | 登录引导、用户名密码换令牌、客户端凭据换令牌三条调用 |
| `src/api/oidc.js` | PKCE：生成 verifier/challenge/state、拼授权 URL、用授权码换令牌 |
| `src/store/auth.js` | 登录态（`authEnabled` / `consoleRequired` / 认证方式 / 主体 / 来源 / 过期）；四条登录路径都收敛到 `adopt()`；`loginRequired()` 与 `gateOnly()` 是界面判据的唯一出处 |
| `src/views/LoginView.vue` | 登录页：按服务端下发的 `methods` 渲染页签；门禁单开时显示「只挡住界面」提示 |
| `src/views/AuthCallbackView.vue` | SSO 回调：校验 `state` → 换令牌 → 让服务端确认 → 回跳 |
| `src/views/SettingsView.vue` | 「连接设置」：分别显示数据面鉴权与控制台门禁，并写明后者不是安全边界 |
| `src/router/index.js` | `/login`、`/auth/callback` 为 `public`；守卫按 `auth.loginRequired() && authenticated` 拦截 |

令牌只有一处存储（`localStorage`），因此「登录页登录」与「设置页手填令牌」
不是两套机制，只是同一件事的两个入口——`client.js` 不需要知道令牌是怎么来的，
加一种登录方式不需要动它。

`auth.logout()` **不通知服务端**，因为服务端不知道这件事（没有会话表可删）。
它的语义就是「忘掉本机这份令牌」。

### 怎么验

登录链路的故障分三类，各有归属，**不能靠一层测试全包**：

| 层 | 工具 | 守住什么 | 项数 |
| --- | --- | --- | --- |
| 单元 / 切片 | `ConsoleAuthEndpointTests` + `ConsoleApiTests`（`./mvnw -o test`） | 错误码、豁免列表、状态码、`WWW-Authenticate`、门禁的生效范围、默认口令；连内存 H2，不需要外部依赖 | 31 + 12 个用例 |
| 协议层 | `scripts/sweep-console-auth.sh` | 对运行中的实例发真实 HTTP：引导、用户名密码登录、换令牌、凭据校验、静态令牌、SPA 深链回退；断言实际报文而不只是状态码 | 63 项 |
| 前端集成 | `npm run check:login`（在 `paimon-rest-console/`） | 跑 `src/store/auth.js` 与 `src/api/*` 的**真实代码**：请求体字段名与编码、令牌落盘的键、`Authorization` 的附加、登出、失败回滚 | 59 项 |

后两层都需要一个**开了鉴权**的实例（`--paimon.rest.auth.enabled=true`）。
`CLIENT_ID` / `CLIENT_SECRET` 取自启动日志里 `created bootstrap principal` 那行——
**注意那行只在 `paimon.rest.authorization.enabled=true` 时才会出现**；没开授权时库中
一个主体也没有，先用默认账号密码登录、再调管理 API 建一个即可：

```bash
PW=$(curl -s -X POST http://localhost:8080/api/console/v1/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST http://localhost:8080/api/management/v1/principals \
  -H "Authorization: Bearer $PW" -H 'Content-Type: application/json' \
  -d '{"principal":{"name":"sweep-principal","properties":{}},"credentialRotationRequired":false}'
# 响应里的 credentials.clientId / clientSecret 就是后两个环境变量
```

用法见 README 第 7 节「端到端验收脚本」。

前端集成那一层不是重复劳动：`adopt()` 失败不回滚的 bug 会让「一次写错令牌就把正在用的
有效令牌一起弄丢」，而它在服务端 209 个用例与静态校验里**全是绿的**——
服务端只看到「客户端拿了个坏令牌」，看不到客户端把好令牌也擦了。

---

## 12. 已知边界

- **门禁不是安全边界。** `console.required=true` + `auth.enabled=false` 时，
  浏览器被拦在登录页，而 `curl /v1/config` 照样 200（[§2.1](#21-两个开关门禁不是安全边界)）。
  这是刻意的默认值取舍，不是疏漏。
- **控制台口令是明文配置。** 没有哈希存储、没有轮换流程，改口令要改配置并重启；
  配置里可以写 `${ENV_VAR}`，生产应当用它。默认 `admin/admin` 存在时启动日志会告警。
- **无法即时撤销令牌。** 只能等 TTL 过期，或轮换 `access-token.signing-key`
  （作废所有人的令牌）。要做单点撤销需要引入令牌黑名单，那会让「服务端不保存状态」
  这条性质失效，因此没有做。
- **OIDC 的 RP-initiated logout 未实现。** IdP 的 `end_session_endpoint` 已经发现出来了，
  但没下发到前端，也没接。目前登出是纯本地行为，IdP 那边的会话仍然存在。
- **不做刷新令牌（refresh token）。** 过期就重新登录。控制台是低频使用的运维界面，
  引入刷新流程会带来「刷新令牌怎么存、怎么撤销」一整串问题，收益不抵。
- **`scope` 只有 `PRINCIPAL_ROLE:ALL`。** 见 [§5.3](#53-scope-只接受-principal_roleall)。
- **`token-principals` 与 `password.principals` 都是静态配置。** 令牌 / 账号 → 主体名的
  映射写在配置文件里，改映射要重启。
- **主体名超过 255 字符时，审计列里记的是摘要。** 认出令牌要靠配置或签名，认不出时
  退化为「令牌即主体名」（门禁模式下尤其常见：令牌可能来自另一个实例，重启后签名密钥
  变了就认不出来）。而访问令牌有 272 个字符，长于审计列 `varchar(255)`，
  直接入库会以 `Data too long for column 'created_by'` 报 500。因此写入前归一化为
  `sha256:<前 12 位>`——同一个令牌得到同一个标签，可追溯，且不把凭据片段写进元数据库。
  正确的解法始终是配 `token-principals`，让审计里出现的是人名。见
  [`../README.md`](../README.md) 第 6 节第 19 条。
- **同一个问题也不靠「加宽审计列」来解决。** 把这三列加宽到 `varchar(512)` 挡得住控制台
  令牌（272 字符），换来的却是更糟的结果：这三列是被接口原样返回的（`TableDtos` /
  `DatabaseDtos` / `ViewDtos` / `FunctionDtos` / `PartitionDtos`），加宽后令牌**明文入库且
  可以读回来**——读得到表元数据的人就拿到了一个可用的令牌（签名密钥配置好时还跨实例有效），
  而原先的 500 至少只意味着「没写进去」。何况主体名长度没有上界，512 挡不住 OIDC 的
  ID token（通常数百到一千多字符），溢出只是被推后。代价上也不划算：MySQL profile 的
  `ddl-auto` 是 `validate`，改列宽要对 14 张表共 42 个列做迁移，而归一化方案对数据库零变更。
- **控制台不解析 JWT。** 前端拿到的令牌里有什么 claim 一概不看，`exp` 也不看
  （只在「剩余有效期」提示上用一个由服务端返回的 `expiresAtMillis`）。
  前端解出来的 `exp` 只能骗自己，签名、受众、签发者都验不了。
