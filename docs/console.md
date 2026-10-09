# Web 管理控制台

控制台是 `paimon-rest-server` 自带的一个 Vue 单页应用，挂在 `/console/` 下，
覆盖服务端两个 API 面：

| API 面 | 基址 | 控制台覆盖 |
| --- | --- | --- |
| Catalog API | `/v1/{prefix}/**` | database / table / view / function / semantic view / tag / branch / partition / snapshot / 数据令牌与查询授权 |
| Management API | `/api/management/v1/**` | catalog / principal / principal-role / catalog-role / grants |
| 控制台扩展 | `/api/console/v1/meta` | 只读：导出枚举取值与服务端配置摘要，**非 Polaris 规格** |

它是**运维与排查工具**，不是数据写入入口：不提供 `commitTable`（`.../tables/{table}/commit`），
理由见 [§7](#7-端点的取舍为什么没有-committable)。对快照的介入只做「读」与「回滚」两件事。

---

## 1. 快速开始

控制台的构建产物**提交在仓库里**（`paimon-rest-server/src/main/resources/static/console`），
因此**只是启动服务端不需要 Node**：

```bash
# 1) 构建（不需要 npm）
./mvnw -o package -DskipTests

# 2) 启动（h2 profile 用于本地试跑）
java -jar paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=h2 --server.port=8080

# 3) 打开
open http://127.0.0.1:8080/console/
```

只有**改动控制台源码**时才需要 Node 20+：

```bash
./scripts/build-console.sh          # 装依赖（按需）→ 构建 → 校验一致性
```

首次进入控制台**默认要求登录**（`paimon.rest.auth.console.required=true`）：
登录页第一个页签是用户名密码，默认账号密码 `admin/admin`，直接用它进去即可。
服务端另外开了 `paimon.rest.auth.enabled=true` 时，`/v1/**` 与管理 API 也要求令牌，
此时登录后拿到的那份令牌会自动带上——不需要再去「连接设置」手填。
见 [§6](#6-鉴权登录与连接设置)。

> **注意**：只开门禁（`console.required=true` 而 `auth.enabled=false`）时，
> 这层登录**只挡住界面**，`curl` 数据接口仍然能拿到数据。要保护数据请开 `auth.enabled`。

### 1.1 本地开发

前端要热更新时，用 Vite 的 dev server，它把 `/v1` 与 `/api` 代理到 `127.0.0.1:8080`：

```bash
./mvnw -o -pl paimon-rest-server spring-boot:run -Dspring-boot.run.profiles=h2
cd paimon-rest-console && npm run dev        # http://127.0.0.1:5173/console/
```

dev server 的基址也是 `/console/`（与生产一致），因此不需要为开发环境改任何路径。

---

## 2. 目录结构

```
paimon-rest-console/
├── index.html                     入口页（含首屏占位样式）
├── vite.config.js                 base=/console/，outDir 指向服务端 resources/static/console
└── src/
    ├── api/
    │   ├── endpoints.js           ★ 全部 82 条端点的 method/path，唯一契约表
    │   ├── client.js              fetch 封装：鉴权、路径拼装、错误归一化
    │   ├── catalog.js             Catalog API 的调用函数
    │   ├── management.js          Management API 的调用函数（含创建请求的包体约定）
    │   ├── console-auth.js        登录引导、用户名密码换令牌、客户端凭据换令牌（这三条不需要令牌）
    │   └── oidc.js                OIDC 授权码 + PKCE：授权 URL、state、换令牌
    ├── components/                ResourceState（三态）、PropertiesEditor、StorageConfigFields、
    │                              CredentialDialog、FieldLabel（字段备注收进问号图标）、
    │                              JsonBlock、CopyText、TimeText、PageHeader、PanelCard
    ├── composables/index.js       useResource（竞态丢弃）、刷新事件、通知与危险确认
    ├── layouts/ConsoleLayout.vue  侧边导航 + catalog 选择器 + 主题切换 + 刷新
    ├── router/index.js            路由表；侧边菜单由它聚合生成；登录守卫
    ├── store/                     auth（登录态）、session（catalog 与 meta）、
    │                              credentials（令牌与基址）、theme
    ├── styles/index.css           设计变量 --pc-* 与布局类
    ├── utils/format.js            时间、字节、JSON 等展示格式化
    └── views/                     11 个页面，见 §4
```

`src/api/endpoints.js` 与 `scripts/verify-console.py` 是这套结构里的两根钉子，
它们的约束见 [§5](#5-契约表与机械校验)。

---

## 3. 服务端如何托管

控制台是 history 路由的单页应用，服务端要承担两件事：把真实文件当静态资源返回，
把「不是文件」的路径交给入口页（否则刷新 `/console/catalogs` 会 404）。
两者都在 `ConsoleWebConfig` 里，用的是 `PathResourceResolver` 子类，**不是**回退控制器。

**为什么不用 `@GetMapping("/console/**")`。** 控制器映射的优先级高于静态资源处理器，
一旦挂上去就会把 `assets/**` 也接管过来，于是得在控制器里自己判断「这是文件还是路由」，
再把文件请求转交回静态资源链——绕一圈，还要处理转发回自身导致的循环。
`PathResourceResolver` 本来就在这条链上，「文件不存在则退回入口页」正是它的职责范围。

回退的判据是**路径末段有没有扩展名**：

| 请求 | 结果 |
| --- | --- |
| `/console` | 302 → `/console/` |
| `/console/` | 200 入口页 |
| `/console/catalogs` | 200 入口页（前端路由） |
| `/console/tables/paimon/default/orders` | 200 入口页（前端路由，三段参数） |
| `/console/assets/index-<hash>.js` | 200 `text/javascript` |
| `/console/assets/missing.js` | **404** |

最后一行是有意为之：缺失的 `.js` 必须 404。如果也回退成入口页，一次产物不同步
（浏览器缓存里是旧 HTML，引用了已被替换掉的带哈希文件）就会表现为
「页面白屏但状态码 200」，比直接 404 难查得多。

`/console/**` **不**在鉴权拦截器范围内（`WebConfig` 只给 `/v1/**`、`/api/catalog/v1/**`、
`/api/management/v1/**`、`/api/console/v1/**` 加了拦截）。浏览器无法在文档请求上带
`Authorization` 头，要保护静态资源只能靠 Cookie 会话，而那会引入 CSRF 面——
对一组不含数据的 JS/CSS 不值得。真正的边界在 API 上（见 §6）。

---

## 4. 页面清单

路由表在 `src/router/index.js`，侧边菜单直接由它聚合生成（按 `meta.group` 分组），
不另外维护菜单配置——加一个页面只需加一条路由。

| 路由 | 页面 | 覆盖的能力 |
| --- | --- | --- |
| `/login` | 登录 | 按服务端下发的登录方式渲染页签：用户名密码（默认）/ 主体凭据（OAuth 2.0 客户端凭据）/ SSO（OIDC）/ 静态令牌（需配 `auth.tokens`）。**未开启的方式不渲染**；一个可用方式都没有时说明该开哪个配置项。门禁单开时先显示一句「只挡住界面」。不套管理布局 |
| `/auth/callback` | SSO 回调 | 校验 `state` → 用授权码换令牌 → 让服务端确认 → 回跳原页面。不套管理布局 |
| `/` | 控制台概览 | 统计块、服务端配置摘要、**连接自检**、catalog 清单、`GET /v1/config` 展示 |
| `/browse` | 目录浏览 | 左库右对象；四页签（表/视图/函数/语义视图）；建库、建表、注册表、按表 ID 定位、重命名、删除、详情抽屉 |
| `/tables/:prefix/:database/:table` | [表详情](console-table-detail.md) | 九页签：详细信息 / Schema / 变更 / 快照 / 标签 / 分支 / 分区 / 权限 / 数据访问；含**变更构造器**、快照与标签的**回滚**、表级 grants 的增删 |
| `/catalogs` | Catalog 管理 | 列出 / 创建 / 详情 / 更新（带版本）/ 删除 |
| `/principals` | 主体 | 列出 / 创建 / 更新 / 删除 / rotate / reset；授予与撤销服务角色。创建、轮换、重置后的凭据弹窗可一键导出 JSON 文件（`{principal, clientId, clientSecret}`，文件名 `<主体>-credentials.json`），密钥仍只在弹窗期间可见 |
| `/principal-roles` | 服务角色 | 列出 / 创建 / 更新 / 删除；查看成员；授予与撤销 catalog 角色 |
| `/catalog-roles` | Catalog 角色与授权 | 角色 CRUD；查看授予了该角色的服务角色；**grants 的增删**（按资源类型分组渲染权限） |
| `/settings` | 连接设置 | API 基址、访问令牌、登录状态、主题；`/api/console/v1/meta` 原始响应 |
| `/:pathMatch(.*)*` | 未找到 | 兜底 |

### 4.1 变更构造器

「变更」页签不是「读一个改动列表」，而是**构造器**：从 10 个动作里选一个、填字段、
点「添加」，攒够一批后一次性 POST `alterTable`——服务端的 alter 接口接收一组变更并
原子应用，提交后无法撤销，只能反向再做一次。UI 的动作值与提交时的 `action` 不一定同名
（例如表单里的「调整列位置」提交为 `updateColumnPosition`），映射集中在 `toChange()` 一处，
由校验脚本第 8 组与规格比对。

### 4.2 连接自检

首页的「连接自检」不是装饰：控制台最常见的故障是**地址或令牌配错**，
而其表现是「每个页面都是空列表」，看不出根因。它逐个探测控制台依赖的端点，
把失败的那一个连同 HTTP 状态与错误消息一起列出来，一眼能分辨
「服务端没起来」「令牌不对」「路径写错了」。

### 4.3 表单取值的来源

所有下拉框的取值都来自 `GET /api/console/v1/meta`，**不在前端硬编码**：

- `catalogTypes` / `storageTypes` / `credentialManagerTypes` / `fileIoTypes`
- `supportedStorageTypes`：本部署实际可用的存储类型子集（由 `file-io.type` 决定）。
  控制台把不可用项渲染成**禁用**而不是隐藏——看到「有 OBS 这个类型但当前部署没开」
  比看不到要好。
- `grantTypes` 与 `privilegesByGrantType`：资源类型 → 该层级可授予的权限。
  这份对应关系由服务端的 `Privilege.allowedFor(String)` 从管理规格还原。
  前端若复制一份必然漂移，而漂移的表现是「提交后才被服务端拒绝」，排查成本高。

meta 只暴露枚举取值与服务端自身的配置，不含任何主体、catalog 或凭据信息，
因此与 `/api/management/v1/**` 一样只过认证拦截器，不做细粒度授权判定。

---

## 5. 契约表与机械校验

### 5.1 为什么要有一张端点表

控制台是用字符串拼 URL 的，而服务端的路径属于**契约**（catalog API 见
`paimon-rest-server/src/main/resources/static/rest-catalog-open-api.yaml`，
管理 API 见 `spec/polaris-management-service.yml`）。路径写错时前端的表现是
「404 空列表」，看不出是前端拼错了还是服务端改了——而这批路径有 82 条，
靠人工比对不现实。

因此所有路径收在 `src/api/endpoints.js` 一张表里，视图层不手写 URL 字符串，
并交给 `scripts/verify-console.py` 与服务端规格逐条比对。

```js
// 形如
listDatabases: { method: 'GET', path: '/v1/{prefix}/databases' },
```

`{名字}` 占位由 `client.js` 的 `buildPath()` 替换并 `encodeURIComponent` 编码；
缺失参数直接抛错，不会拼出半个 URL。

### 5.2 校验脚本

```bash
python3 scripts/verify-console.py      # 退出码 0 通过 / 1 不一致 / 2 环境不满足
PYTHON=/path/to/python3 scripts/verify-console.py   # 多解释器时指定装了 PyYAML 的那个
```

它**不构建前端、不启动服务端**，只做静态比对，因此可以放进提交前的检查。10 组 21 项：

| 组 | 校验内容 |
| --- | --- |
| 1. 端点存在 | 每条路径都能在规格或扩展白名单中找到；端点名不重复 |
| 2. 扩展白名单 | 声明为规格外扩展的端点确实被使用，且每条都写明了理由（无过期条目） |
| 3. 端点引用 | `src/` 里按名字调用的端点都在 `endpoints.js` 中声明 |
| 4. 无死条目 | `endpoints.js` 里声明的端点都在 `src/` 中被调用过 |
| 5. 基址一致 | vite `base`、路由 `base`、构建输出目录、服务端静态资源位置、client 默认基址五处指向同一处；令牌端点路径与服务端下发的 `tokenEndpoint` 一致 |
| 6. 产物完整 | 入口页存在，且它引用的每个 assets 文件都存在 |
| 7. 产物新鲜 | 构建产物不比控制台源码旧（改了源码没重新构建会在这里暴露） |
| 8. 变更构造器 | 表详情的变更构造器能提交的动作都在规格的 `SchemaChange` 里，且每个动作提交的字段都在该动作的 schema 里有定义 |
| 9. 对话框状态 | 每个 `el-dialog` 都声明了 `destroy-on-close`（否则编辑时会残留上一次的状态） |
| 10. 下拉候选来源 | 每个 `el-select` 都声明了候选来源（`el-option` / `:options` 等）；确属自由输入的要在源码里写 `el-select-free-input` 标记显式豁免（否则「下拉是空的」这类 bug 只有打开页面才看得到） |

第 7 组是这套机制里最实用的一条：**产物过期不会让任何东西报错**，
它只是安静地少一个按钮。

第 8 组针对的是**请求体形状**——这是控制台里最容易错、又最难自己发现的地方。
`endpoints.js` 只固定了路径与动词，请求体是各视图自己拼的；形状写错时服务端只回一句
笼统的 400，而报错要等真提交了才看得到。规格里的 `SchemaChange` 是个判别联合
（18 个动作，每个动作的字段都定义得很清楚），因此可以静态比对。脚本按层次解析
`toChange()` 的返回值（`move` 这类字段的值是嵌套对象，其字段属于规格里的 `Move`，
混进来会误报），取出每个动作提交的一级字段再与规格比对。

编写这类校验时值得注意：**要能证明它抓得住错误**。把 `addColumn` 的 `fieldNames`
改成单数、或把某个动作名改掉，脚本应当报出具体的字段/动作名而不是通过——空跑的校验
比没有校验更糟。

唯一允许出现在规格之外的端点是 `EXTENSION_ENDPOINTS` 里的：

```python
EXTENSION_ENDPOINTS = {
    ("GET", "/api/console/v1/meta"): "控制台元数据（枚举取值与服务配置摘要），非 Polaris 规格",
    ("GET", "/api/console/v1/auth"): "控制台登录引导（可用认证方式、令牌端点、当前令牌状态）",
    ("POST", "/api/console/v1/login"): "控制台用户名密码登录（默认方式，响应是驼峰字段而非 OAuth 形状）",
    ("POST", "/api/catalog/v1/oauth/tokens"): "OAuth 2.0 客户端凭据令牌端点（RFC 6749 第 4.4 节）",
}
```

每加一条都要写理由，且必须在控制台里真的被调用，否则脚本报「过期条目」。

`verify-console.py` 是**静态**的：它证明前端写下的路径与规格一致，证明不了登录链路真的通。
登录另有两层对着**运行中的实例**跑的验收（都需要以 `--paimon.rest.auth.enabled=true` 启动）：

```bash
# HTTP 层：真实报文、错误码、限速、用户名密码登录、静态令牌、SPA 深链回退——63 项
BASE=http://127.0.0.1:8080 CLIENT_ID=<clientId> CLIENT_SECRET=<secret> \
  ./scripts/sweep-console-auth.sh

# 前端集成：跑 src/store/auth.js 与 src/api/* 的真实代码——59 项
BASE=http://127.0.0.1:8080 CLIENT_ID=<clientId> CLIENT_SECRET=<secret> \
  npm run check:login     # 在 paimon-rest-console/ 下
```

（`CLIENT_ID` / `CLIENT_SECRET` 只在开了授权时才会由启动日志自动给出。
没开授权时先用默认账号密码登录、再调管理 API 建一个主体，命令见
[`console-auth.md` §11](console-auth.md#11-控制台侧的实现位置)。）

`check:login` 用 vite 的 `ssr` 构建把前端模块**原样**打成 Node 可执行产物（不是另写一份仿真），
装好 `localStorage` 替身并包住 `fetch`，因此能断言「前端实际发出的请求长什么样」——
字段名是 `client_id` 还是 `clientId`、体是表单还是 JSON、令牌写到哪个 key、
失败有没有回滚。这一层抓到过一个真实的 bug（登录失败时把正在用的有效令牌一起弄丢了），
而它在服务端测试与静态校验里全绿。用法见 README 第 7 节「端到端验收脚本」。

### 5.3 加一个页面的完整清单

1. `src/api/endpoints.js`：加端点条目（路径必须能在规格里查到，否则进白名单并写理由）。
2. `src/api/catalog.js` 或 `management.js`：加调用函数。
3. `src/views/`：加视图；删改功能要接 `confirmDanger()`，错误走 `notifyError()`。
   字段备注**不要**铺成输入框下面的一段文字（表单页会被说明文字占满），
   用 `FieldLabel` 收进字段名后的问号图标，悬停显示——短备注传 `tip` 属性，
   带 `code` / 分段的富文本用默认插槽（见 `components/FieldLabel.vue` 的文档注释）。
4. `src/router/index.js`：加路由；想出现在侧边栏就写 `meta: { nav: true, icon, group }`。
   不需要令牌就能看的页面（登录页、SSO 回调）写 `meta: { public: true }`——
   它同时决定「不套管理布局」（`App.vue`）与「守卫不拦」（见 §6.2）。
5. `./scripts/build-console.sh`：重新构建，第 3、4、6、7 组校验会在这里把关。

---

## 6. 鉴权、登录与连接设置

有两个开关，管的是两件不同的事，**不要混为一谈**：

| 配置项 | 默认 | 管什么 |
| --- | --- | --- |
| `paimon.rest.auth.console.required` | `true` | 浏览器打开控制台要不要先登录；只作用于控制台界面与 `/api/console/v1/**` |
| `paimon.rest.auth.enabled` | `false` | `/v1/**`、`/api/catalog/v1/**`、`/api/management/v1/**` 要不要带令牌 |

**默认部署的真实状态**：浏览器被拦在登录页，但数据接口匿名可调
（`curl /v1/config` 返回 200）。这层门禁解决的是「误入的人看到一堆管理表单」，
**不是安全边界**——要保护数据必须把 `auth.enabled` 设为 `true`。
服务端在「门禁开着而 `auth.enabled=false`」时会在启动日志里告警，登录页与设置页也都会写明。

`auth.enabled=true` 后，上述前缀全部要求 `Authorization: Bearer <token>`。
**豁免只有三条**，都是精确路径：`GET /api/console/v1/auth`（登录引导）、
`POST /api/console/v1/login`（用户名密码换令牌）与
`POST /api/catalog/v1/oauth/tokens`（客户端凭据换令牌）——它们要回答的正是「怎么登录」，
放在鉴权之后会形成死循环。

浏览器登录有三种方式，可同时启用（完整设计见
[`console-auth.md`](console-auth.md)）：

| 方式 | 凭据 | 令牌由谁签发 | 默认 |
| --- | --- | --- | --- |
| 用户名 + 密码 | 服务端配置里的账号，默认 `admin/admin` | 本服务端（HS256） | **开启** |
| OAuth 2.0 客户端凭据 | 主体的 `client_id` / `client_secret` | 本服务端（HS256） | 开启 |
| OIDC 授权码 + PKCE | 外部 IdP（Keycloak / Auth0 / Okta…） | IdP（RS256 / ES256） | 关闭 |

用户名密码排在登录页第一个页签：**它不需要先建主体**，是「装好就能进控制台」的那条路。
账号只是「开门的钥匙」，不进授权链路——默认主体名与账号同名，因此
`admin` 这个账号登录后能不能看到东西，取决于它有没有被授予角色（启动日志会就这种
「登录成功但什么也看不到」告警）。

三者产出的都是一个 Bearer 令牌，此后走同一条通道。第四个入口是
`paimon.rest.auth.tokens` 里的静态令牌（给机器用，**配置了才出现**，排在最后一页签）。

**登录页不判断有哪些登录方式。** 支持哪种、令牌端点在哪儿、控制台要不要登录，
全部由 `GET /api/console/v1/auth` 在运行时下发——控制台是构建期打包的静态资源，
读不到服务端配置，硬编码一份必然漂移，而漂移的表现是「点了登录按钮没反应」。

令牌存在本机 `localStorage`：

| localStorage 键 | 含义 |
| --- | --- |
| `paimon.console.token` | 访问令牌（登录页或设置页写入的**同一个**位置） |
| `paimon.console.baseUrl` | API 基址，默认空串（同源） |
| `paimon.console.catalog` | 当前选中的 catalog prefix |
| `paimon.console.theme` | `light` / `dark` |

OIDC 登录期间还会在 `sessionStorage` 里放三个短命键
（`paimon.console.oidc.verifier` / `.state` / `.returnTo`），它们是 PKCE 的中间状态，
**用完即弃**，成功后立刻清掉。放 sessionStorage 而不是 localStorage 有两个原因：
它只在一次往返里有意义；两个标签页同时登录时不会互相覆盖。

请求头由 `client.js` 统一附加，视图层不碰。令牌失效（401）时的提示语是明确的
「未通过鉴权：请在『连接设置』里填写访问令牌」，而不是干巴巴的 HTTP 状态码。

`baseUrl` 默认空串即同源——生产构建后控制台与服务端同端口，不填就是对的；
只有把控制台部署到别处（例如独立的静态站点托管）时才需要覆盖。

### 6.1 登出只是忘掉本机令牌

服务端**没有会话表**：令牌是自包含的 JWT，签出去之后在
`paimon.rest.auth.access-token.ttl`（默认 1 小时）内一直有效，没有可以作废它的接口。
因此 `auth.logout()` 不通知服务端，只清掉本机那份令牌。

这一点在界面上如实说明（顶栏按钮的提示、设置页的说明文字），
否则会给人「已经把自己踢下线了」的错觉。

### 6.2 路由守卫

守卫按「控制台要不要登录」+「手上的令牌算不算数」决定拦不拦，
依据是 `/api/console/v1/auth` 的 `authEnabled`、`consoleRequired` 与
`session.authenticated`。判据只有一个出处——`auth.loginRequired()`：
`authEnabled || consoleRequired`，与服务端 `Auth.Console#loginRequired` 是同一个式子
（写两处迟早漂移，而漂移的表现是「守卫放行了、页面却全是 401」）。

- **门禁单开时也拦。** `consoleRequired=true` 而 `authEnabled=false` 时，
  浏览器照样要先登录——这正是本次改动的目标。
- **问不到服务端时放行**，不把人困在登录页。连不上时该看到的是各页面自己的错误提示
  （带 HTTP 状态与原因），而不是一句「请先登录」——后者会把「服务端没起来」
  误导成「凭据不对」。
- **令牌过期先清掉再判断**（`auth.dropExpiredToken()`）。带着一个必然 401 的令牌继续走，
  每个页面都会显示「未通过鉴权」，看起来像权限不足而不是需要重新登录。
- `redirect` 查询参数**只接受站内路径**（以单个 `/` 开头）。它会被原样交给
  `router.replace`，放行绝对 URL 等于开放重定向。

### 6.3 凭证只在本机

控制台不代持任何凭据，也不请求服务端把令牌持久化；OIDC 用公开客户端 + PKCE，
前端构建产物里没有任何密钥。

---

## 7. 端点的取舍：为什么没有 `commitTable`

`POST /v1/{prefix}/databases/{database}/tables/{table}/commit` 在规格里存在，
但 `endpoints.js` **有意不包含它**：

它是写入方（Spark / Flink 的 writer）提交快照的接口，请求体里要带 manifest list
等**由写入方在本地生成的文件路径**。控制台拼不出一个真实的快照——
提供一个这样的表单，只会让人提交出一张指向不存在文件的表，
而且这张表的元数据是「合法」的，坏在数据侧，事后极难定位。

与之相对，`rollbackTable` 被实现了：它以**已存在的** instant 为目标，
参数是快照 ID 或标签名，控制台可以从列表里选，不存在凭空捏造的问题。
回滚是不可逆操作，它的确认框是强警告形式。

同一原则适用于别处：**控制台只做它能凭已有数据做对的事**。

---

## 8. 构建与提交约定

### 8.1 产物落位

`vite.config.js` 把 `outDir` 直接指向服务端：

```js
build: {
  outDir: '../paimon-rest-server/src/main/resources/static/console',
  emptyOutDir: true,
}
```

这样 `mvnw package` 与 Dockerfile **不依赖 npm**，Java 侧构建路径上完全没有 Node。
代价是产物必须跟着源码一起提交。

### 8.2 产物要提交

`paimon-rest-console/.gitignore` 忽略的是 `node_modules/`、`dist/`、`.vite/`；
构建产物不在这个目录里，它落在服务端 `src/main/resources/static/console` 下，
**属于被跟踪的内容**：

- 改了控制台源码却没重新构建并提交产物 → 服务端打包出来的是上一版控制台；
- `verify-console.py` 第 7 组会查出产物比源码旧（但只在有人跑它的时候）。

`.dockerignore` 排除了 `paimon-rest-console/node_modules/`（几十万个文件，
纯粹的上下文体积），保留源码——镜像不需要它，但留着便于在容器内排查。

### 8.3 增量构建的一个坑：target/classes 会累积旧产物

控制台的产物按内容哈希命名（`index-CG9zHvOr.js`），每次前端重建文件名都会变；
而 `maven-resources-plugin` 只做「复制 / 覆盖」，**从不删除** `target` 里已经不存在于
`src` 的文件。于是增量执行 `mvn package` 时，`target/classes/static/console` 会累积
历次构建的旧文件——实测一次增量构建就多出 20 个死文件（含上一版 1.1 MB 的 index chunk），
打进 jar 里就是白白胖胖的几 MB，而且看 jar 内容会误以为有两个入口 chunk。

Dockerfile 走的是 `clean package`，镜像不受影响；受影响的是**本地改完控制台后
只跑 `package`** 的场景，而那恰好是最常见的场景。因此在 `paimon-rest-server/pom.xml` 里
把 `maven-clean-plugin` 的一个执行绑到了 `initialize`：

```xml
<execution>
  <id>clean-derived-console-assets</id>
  <phase>initialize</phase>
  <goals><goal>clean</goal></goals>
  <configuration>
    <excludeDefaultDirectories>true</excludeDefaultDirectories>
    <filesets>
      <fileset><directory>${project.build.outputDirectory}/static/console</directory></fileset>
    </filesets>
  </configuration>
</execution>
```

早于 `process-resources`，所以随后的复制是干净的一次；
`excludeDefaultDirectories` 置真以避免误删整个 `target`（那会把增量构建变成全量构建）。

判断它是否生效：

```bash
ls paimon-rest-server/target/classes/static/console/assets | wc -l   # 应与源码目录一致
```

### 8.4 产物体积

主 chunk 约 1.1 MB（gzip 368 KB），其中绝大部分是 Element Plus 的全量组件与图标。
控制台是内网管理界面，这个体积可以接受；真要做按需引入，
改的是 `src/main.js` 的图标注册与 `vite.config.js` 的 `manualChunks`，
不影响上述任何约定。

---

## 9. 测试

`ConsoleApiTests`（12 个用例）覆盖服务端侧的行为，重点是**托管机制**而非页面渲染
（页面渲染属于前端职责，不适合放在服务端测试里）：

| 用例 | 断言 |
| --- | --- |
| `metaExposesEnumsUsedByConsoleForms` | meta 返回了表单需要的全部枚举组 |
| `metaPrivilegesFollowManagementSpec` | 权限分组与管理规格一致 |
| `authAsksForALoginEvenWhenTheDataPlaneIsOpen` | 默认配置下 `authEnabled=false` 但 `consoleRequired=true`，且 `methods` 以 `password` 开头——两个字段必须同时如实 |
| `authReportsTheSessionWhenATokenIsPresent` | 同一个端点带上令牌时只多出「你是谁」，不会被 401 挡掉 |
| `consoleEndpointsRejectAnonymousRequestsWhileTheGateIsOn` | 门禁开着时 `/api/console/v1/**` 拒匿名，而 `/v1/**` 仍匿名可调（门禁不是安全边界） |
| `authAdvertisesThePolarisCompatibleTokenEndpoint` | 引导下发的令牌端点就是 Polaris 那条路径 |
| `consoleRootRedirectsToTrailingSlash` | `/console` → 302 |
| `consoleDirectoryUrlForwardsToEntryPage` | `/console/` → 200 入口页 |
| `consoleEntryIsServedAtItsRealUrl` | `/console/index.html` → 200 |
| `clientSideRoutesFallBackToEntryPage` | 深链回到入口页 |
| `missingAssetStillReturns404` | 缺失 assets 仍 404（不回退） |
| `committedConsoleHtmlReferencesExistingAssets` | 已提交的入口页引用的产物都存在 |

登录链路本身另有 `ConsoleAuthEndpointTests`（31 个用例，错误码、接线、门禁范围、
默认口令与限速）与两个运行期验收脚本，见 §5.2。

最后一条把「产物不同步」变成了服务端测试能抓到的问题，
与 `verify-console.py` 第 6 组是同一件事的两个入口（前者在 `mvnw test` 里跑，
后者在提交前跑）。

一个实现细节：断言中文内容要用 `getContentAsString(StandardCharsets.UTF_8)`。
静态资源响应头里没有 charset，`getContentAsString()` 会退回 ISO-8859-1，
断言会看到乱码——这是测试工具的行为，不是服务端的问题。

运行：

```bash
./mvnw -o -pl paimon-rest-server -Dtest=ConsoleApiTests test
```

---

## 10. 已知边界

- **不做写入路径。** 不提供 `commitTable`（见 §7）。控制台不产生数据，
  只改元数据与授权。
- **不做细粒度授权判定。** `/api/console/v1/meta` 只过认证不看权限，
  因为它不暴露任何具体资源信息；`/api/console/v1/auth` 与 `/api/console/v1/login`
  连认证都不过（它们要在登录之前就能被调用），暴露的内容按「对匿名访问者是否敏感」筛过，
  且换令牌的那两条自带失败限速。
- **门禁不是安全边界。** `console.required=true` + `auth.enabled=false` 时，
  界面被拦在登录页而数据接口匿名可调（见 §6）。这句结论在三处界面上写着：
  登录页、设置页的「控制台门禁」一行、以及首页的服务信息表。
- **控制台口令是明文配置。** `console.password.users` 没有哈希存储与轮换流程，
  改口令要改配置并重启；默认 `admin/admin` 时启动日志会告警。
- **不代持凭据。** 令牌只在浏览器 `localStorage` 里；OIDC 用公开客户端 + PKCE，
  构建产物里没有任何密钥。
- **登出无法作废服务端令牌。** 令牌是自包含的 JWT，服务端没有会话表。
  要立刻失效只能轮换签名密钥（会作废所有人的令牌）。详见
  [`console-auth.md` §12](console-auth.md#12-已知边界)。
- **OIDC 不做单点登出。** IdP 的 `end_session_endpoint` 已发现但未接：
  控制台登出后，IdP 那边的会话仍然存在。
- **无审计视图。** 服务端记录了操作日志，但控制台目前没有对应的展示页；
  需要时按 §5.3 的清单加。
- **无 i18n。** 界面为中文单语，文案直接写在组件里。
- **产物需手工同步。** 没有 CI 钩子强制 `verify-console.py`（见 §8.2）。
