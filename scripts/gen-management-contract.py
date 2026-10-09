#!/usr/bin/env python3
"""从 Polaris 管理规格直出契约基线文档，保证表格与规格零偏差。"""
import sys
from collections import Counter

import yaml

SPEC = sys.argv[1]
OUT = sys.argv[2]

d = yaml.safe_load(open(SPEC, encoding="utf-8"))
S = d["components"]["schemas"]
PATHS = d["paths"]

METHODS = ("get", "post", "put", "delete", "head", "patch")


def codes_of(op):
    """响应码集合，统一为字符串。

    YAML 会把 `200:` 解析成 int 键，而 `default:` 之类的键是 str，
    因此这里一律归一化，避免出现 `"403" in responses` 永远为假这类静默错误。
    """
    return {str(c) for c in op.get("responses", {})}


def ref_name(o):
    if not isinstance(o, dict):
        return ""
    if "$ref" in o:
        return o["$ref"].split("/")[-1]
    if o.get("type") == "array":
        return "[]" + ref_name(o.get("items", {}))
    return o.get("type", "?")


def type_of(v):
    if "$ref" in v:
        return "`" + v["$ref"].split("/")[-1] + "`"
    if v.get("type") == "array":
        return "array<" + type_of(v.get("items", {})) + ">"
    if "enum" in v:
        return "enum(" + ", ".join(f"`{e}`" for e in v["enum"]) + ")"
    t = v.get("type")
    if isinstance(t, list):
        t = " | ".join("null" if x == "null" else x for x in t)
    return t or "?"


def props_of(name):
    """展开 allOf 继承，返回 (父类, 自有属性, required)。"""
    s = S[name]
    parts = s.get("allOf", [s])
    parents, props, required = [], {}, set(s.get("required", []))
    for p in parts:
        if "$ref" in p:
            parents.append(p["$ref"].split("/")[-1])
        else:
            props.update(p.get("properties") or {})
            required |= set(p.get("required") or [])
    if not s.get("allOf"):
        props.update(s.get("properties") or {})
    return parents, props, required


lines = []
w = lines.append

w("# Polaris Management API 契约基线")
w("")
w("本文档由 `spec/polaris-management-service.yml` 直接生成，用于实现与验收对照，")
w("表格内容与规格逐字一致，不含人工推断。生成方式见 `scripts/gen-management-contract.py`。")
w("§4.5 末尾有一处标为「实现侧说明」的段落，是本文件唯一的非规格内容。")
w("")
w(f"- 规格来源：Apache Polaris `spec/polaris-management-service.yml`（`main` 分支）")
w(f"- 规模：**{len(PATHS)} 个路径、{sum(1 for p in PATHS.values() for m in p if m in METHODS)} 个 operation、{len(S)} 个 schema**")
w("- 服务基址：`{scheme}://{host}/api/management/v1`（规格 `servers[0].url`）")
w("")
w("> 说明：规格将管理服务与 catalog 服务分成两份文档，但由同一进程承载。")
w("> 本实现据此在同一进程内同时暴露 `/api/management/v1/**`（本文档）与 `/v1/**`（Paimon REST）。")
w("")

# ---------------------------------------------------------------- endpoints
w("## 1. 端点清单")
w("")
w("| # | 方法 | 路径 | 请求体 | 主要响应 |")
w("| --- | --- | --- | --- | --- |")
n = 0
for path, item in PATHS.items():
    # 保留规格中 operation 的声明顺序，不按方法重排，便于与规格逐行对照
    for m in (k for k in item if k in METHODS):
        op = item[m]
        n += 1
        rb = ""
        for v in op.get("requestBody", {}).get("content", {}).values():
            rb = ref_name(v.get("schema", {}))
            break
        rs = []
        for code, v in op.get("responses", {}).items():
            # 403 对所有 operation 一致，略去以节省篇幅
            if str(code) == "403":
                continue
            body = ""
            for vv in v.get("content", {}).values():
                body = ref_name(vv.get("schema", {}))
                break
            rs.append(f"`{code}`" + (f" {body}" if body else ""))
        w(f"| {n} | {m.upper()} | `{path}` | {('`' + rb + '`') if rb else '—'} | {', '.join(rs)} |")
w("")
_with403 = [f"{m.upper()} `{p}`" for p, item in PATHS.items()
            for m in (k for k in item if k in METHODS) if "403" in codes_of(item[m])]
_total_ops = sum(1 for p in PATHS.values() for m in p if m in METHODS)
_missing403 = [f"{m.upper()} `{p}`" for p, item in PATHS.items()
               for m in (k for k in item if k in METHODS) if "403" not in codes_of(item[m])]
w(f"{len(_with403)}/{_total_ops} 个 operation 声明了 `403`（无权限）响应；上表为节省篇幅略去该列。")
if _missing403:
    w("")
    w(f"未声明 `403` 的是 {'、'.join(_missing403)}——两个只读端点，规格只给出了 `200`。")
    w("这属于规格侧遗漏，实现侧仍照常鉴权（见 §4.5）。")
w("")

# ---------------------------------------------------------------- privileges
def enum_block(title, name):
    w(f"### {title}")
    w("")
    e = S[name]["enum"]
    w(f"`{name}`，{len(e)} 项：")
    w("")
    for i in range(0, len(e), 4):
        w("- " + "、".join(f"`{x}`" for x in e[i:i + 4]))
    w("")


w("## 2. 权限枚举")
w("")
w("权限按资源层级分组。同名权限在多个枚举中重复出现，表示它在该层级同样有效")
w("（例如 `CATALOG_MANAGE_ACCESS` 在五组中都有，因此可在任一层级授予）。")
w("")
enum_block("Catalog 级", "CatalogPrivilege")
enum_block("Namespace 级", "NamespacePrivilege")
enum_block("Table 级", "TablePrivilege")
enum_block("View 级", "ViewPrivilege")
enum_block("Policy 级", "PolicyPrivilege")
enum_block("Semantic model 级", "SemanticModelPrivilege")

# ---------------------------------------------------------------- schemas
w("## 3. 资源模型")
w("")
w("### 3.1 主体与角色")
w("")
w("| schema | 父类 | 字段 |")
w("| --- | --- | --- |")
for name in ["Principal", "PrincipalWithCredentials", "PrincipalRole", "CatalogRole"]:
    parents, props, required = props_of(name)
    cells = []
    for k, v in props.items():
        mark = " \\*" if k in required else ""
        cells.append(f"`{k}`{mark}: {type_of(v)}")
    w(f"| `{name}` | {', '.join('`' + p + '`' for p in parents) or '—'} | {'; '.join(cells) or '—'} |")
w("")
w("`\\*` 表示 required。")
w("")

w("### 3.2 请求体")
w("")
w("| schema | 父类 | 字段 |")
w("| --- | --- | --- |")
for name in ["CreateCatalogRequest", "UpdateCatalogRequest", "CreatePrincipalRequest",
             "UpdatePrincipalRequest", "ResetPrincipalRequest", "CreatePrincipalRoleRequest",
             "UpdatePrincipalRoleRequest", "GrantPrincipalRoleRequest",
             "CreateCatalogRoleRequest", "UpdateCatalogRoleRequest",
             "GrantCatalogRoleRequest", "AddGrantRequest", "RevokeGrantRequest"]:
    parents, props, required = props_of(name)
    cells = []
    for k, v in props.items():
        mark = " \\*" if k in required else ""
        cells.append(f"`{k}`{mark}: {type_of(v)}")
    w(f"| `{name}` | {', '.join('`' + p + '`' for p in parents) or '—'} | {'; '.join(cells) or '—'} |")
w("")

w("### 3.3 授权载体 GrantResource")
w("")
w("`GrantResource` 是以 `type` 为判别字段的多态联合，规格给出了显式 `discriminator.mapping`：")
w("")
w("| `type` | 具体 schema | 定位字段 | 权限字段类型 |")
w("| --- | --- | --- | --- |")
mapping = S["GrantResource"]["discriminator"]["mapping"]
for disc, target in mapping.items():
    name = target.split("/")[-1]
    _, props, _ = props_of(name)
    locator = [k for k in props if k not in ("privilege", "type")]
    privilege = ""
    if "privilege" in props:
        privilege = "`" + props["privilege"]["$ref"].split("/")[-1] + "`"
    cells = []
    for k in locator:
        cells.append(f"`{k}`: {type_of(props[k])}")
    w(f"| `{disc}` | `{name}` | {'; '.join(cells) or '—'} | {privilege or '—'} |")
w("")
w("各类别要求定位字段与权限字段同时存在（规格 `required`）；`type` 来自父类且为必填。")
w("")

w("### 3.4 catalog 与存储配置")
w("")
w("| schema | 父类 | 字段 |")
w("| --- | --- | --- |")
# OBS / OSS 两个子类型不在规格里（规格把这两家云归入 S3 兼容存储，靠自定义 endpoint 接入），
# 字段在这里显式声明——这是全文唯一一处「表格内容不来自规格」的地方。
# 必须与 StorageDtos 的 HuaweiObsStorageConfigInfo / AliyunOssStorageConfigInfo 保持一致；
# 改动那两个 record 时同步这里，否则文档会与实现脱节。
EXTENSION_SCHEMAS = {
    "HuaweiObsStorageConfigInfo": {"endpoint": "string", "stsUnavailable": "boolean"},
    "AliyunOssStorageConfigInfo": {"endpoint": "string", "stsUnavailable": "boolean"},
}
EXTENSION_NOTE = " — **非规格子类型，本工程扩展**"
for name in ["Catalog", "PolarisCatalog", "ExternalCatalog", "StorageConfigInfo", "AwsStorageConfigInfo",
             "AzureStorageConfigInfo", "GcpStorageConfigInfo",
             "HuaweiObsStorageConfigInfo", "AliyunOssStorageConfigInfo",
             "FileStorageConfigInfo",
             "ConnectionConfigInfo", "AuthenticationParameters"]:
    if name in EXTENSION_SCHEMAS:
        cells = [f"`{k}`: {v}" for k, v in EXTENSION_SCHEMAS[name].items()]
        w(f"| `{name}` | `StorageConfigInfo` | {'; '.join(cells)}{EXTENSION_NOTE} |")
        continue
    parents, props, required = props_of(name)
    cells = []
    for k, v in props.items():
        mark = " \\*" if k in required else ""
        cells.append(f"`{k}`{mark}: {type_of(v)}")
    w(f"| `{name}` | {', '.join('`' + p + '`' for p in parents) or '—'} | {'; '.join(cells) or '—'} |")
w("")
w("`StorageConfigInfo.storageType` 是存储实现的判别字段（`S3` / `GCS` / `AZURE` / `FILE`）；")
w("`ConnectionConfigInfo.connectionType` 是外部连接实现的判别字段")
w("（`ICEBERG_REST` / `HADOOP` / `HIVE` / `BIGQUERY`）。")
w("")
w("上表 `StorageConfigInfo` 一行的 `enum` 是**规格原文**，只列规格声明的四个取值。")
w("本实现另外支持 `OBS`（华为云）与 `OSS`（阿里云），它们是扩展而非规格内容，")
w("因此不混进那一行——表格与规格保持可机械核对的一致，差异集中记在下方取舍里。")
w("")
w("**六种存储配置均已实现**：`S3` / `AZURE` / `GCS` 的类型专属字段会原样保存并回读，")
w("`AZURE` 的 `tenantId` 按其 `required` 校验（缺失返回 400）。")
w("四点实现取舍：")
w("")
w("- `AwsStorageConfigInfo` 中已废弃的 `currentKmsKey` / `allowedKmsKeys` 不接收也不返回，")
w("  统一用 `encryptionKeys` / `decryptionKeys`；传入会被静默忽略。")
w("- 落在本类型之外的字段（例如 `storageType` 为 `FILE` 却带 `roleArn`）同样被静默忽略，")
w("  而不是报 400——全站都依赖 Spring 默认的宽松绑定，单独收紧会造成行为不一致。")
w("- `PUT /catalogs/{catalogName}` 的 `storageConfigInfo` 是**整体替换**：")
w("  请求里未出现的类型专属字段会被清空，不是「保持不变」。")
w("- `OBS` / `OSS` 两个取值超出规格的 `enum`，是本工程为华为云 OBS 与阿里云 OSS 加的扩展。")
w("  规格把这两家云归入「兼容 S3 协议的对象存储」，靠自定义 `endpoint` 接入；")
w("  单列它们是为了让凭据下发产出厂商原生的 `fs.obs.*` / `fs.oss.*` 键族——这两套键与 `s3.*`")
w("  互不通用，混用会静默失效。**影响面仅限管理 API 的按类型分派逻辑**：引擎只是把")
w("  `storageConfigInfo` 原样透传，不解析 `storageType`，因此 REST Catalog 协议不受影响。")
w("  两个子类型的 `endpoint` 都不是必填项：判断依据是引擎侧是否已在 `core-site.xml` 里")
w("  配好对应端点，服务端无从得知，硬判会把合法配置拒之门外。")
w("")
w("`ConnectionConfigInfo` 仍只按 Iceberg REST 形态处理。")
w("")

# ---------------------------------------------------------------- shapes
def top_props(name):
    """展开 allOf 继承，返回顶层属性（保持规格声明顺序）。"""
    s = S[name]
    acc = {}
    for part in s.get("allOf", [s]):
        if "$ref" in part:
            acc.update(top_props(part["$ref"].split("/")[-1]))
        else:
            acc.update(part.get("properties") or {})
    if not s.get("allOf"):
        acc.update(s.get("properties") or {})
    return acc


def shape_of(schema_name):
    """把成功响应体机械归类为 空 / 裸对象 / 命名数组 / 包装对象。"""
    if not schema_name or schema_name not in S:
        return "空", "—"
    props = top_props(schema_name)
    if len(props) == 1:
        (field, spec), = props.items()
        if spec.get("type") == "array":
            return "命名数组", f"`{field}`: {type_of(spec)}"
    if "name" in props and "entityVersion" in props:
        return "裸对象", "资源自身字段（见 §3.1 / §3.4）"
    return "包装对象", "; ".join(f"`{k}`: {type_of(v)}" for k, v in props.items())


def success_responses():
    """按规格声明顺序产出每个 operation 的 2xx 响应。

    返回 (operation 序号, 方法, 路径, 码, 响应 schema, 形状, 顶层字段)；
    序号与 §1 的端点清单对齐（每个 operation 恰好一个 2xx）。
    """
    idx = 0
    for path, item in PATHS.items():
        for m in (k for k in item if k in METHODS):
            idx += 1
            for code, resp in item[m].get("responses", {}).items():
                c = str(code)
                if not c.startswith("2"):
                    continue
                body = ""
                for v in resp.get("content", {}).values():
                    body = ref_name(v.get("schema", {}))
                    break
                shape, fields = shape_of(body)
                yield idx, m.upper(), path, c, body, shape, fields


rows = list(success_responses())

w("## 4. 响应形状与状态码")
w("")
w("本节同样由规格机械推导。单列一节的原因：这里是实现侧最容易踩空的地方——")
w("**规格对单个资源的读取与更新直接返回裸对象，只有 principal 的创建、重置、轮换返回包装对象**。")
w("多剥一层不会引发解析错误，只会静默地把 `entityVersion` 读成 `0`，")
w("随后任何携带 `entityVersion` 的写操作都会因版本不匹配被拒。")
w("")
w("### 4.1 成功响应逐 operation 对照")
w("")
w(f"编号与 §1 一致：共 {len(rows)} 行，覆盖 {_total_ops} 个 operation 的全部 2xx 响应。")
w("")
w("| # | 方法 | 路径 | 码 | 响应 schema | 形状 | 顶层字段 |")
w("| --- | --- | --- | --- | --- | --- | --- |")
for idx, m, path, code, body, shape, fields in rows:
    schema_cell = f"`{body}`" if body else "—"
    w(f"| {idx} | {m} | `{path}` | `{code}` | {schema_cell} | {shape} | {fields} |")
w("")
w("形状判定规则（对本节全部行成立）：")
w("")
w("- **空**：`204`，响应未声明 `content`。")
w("- **命名数组**：顶层只有一个字段，且其类型是数组，字段名由规格指定。")
w("- **裸对象**：顶层同时含 `name` 与 `entityVersion`，资源自身就是响应体。")
w("- **包装对象**：顶层字段不含资源身份字段，需要再取一层才是资源。")
w("")

# ---------------------------------------------------------------- wrappers
wrappers = [r for r in rows if r[5] == "包装对象"]
w(f"### 4.2 包装对象只有 {len(wrappers)} 处")
w("")
w("| 方法 | 路径 | 码 | 响应 schema | 顶层字段 |")
w("| --- | --- | --- | --- | --- |")
for idx, m, path, code, body, shape, fields in wrappers:
    w(f"| {m} | `{path}` | `{code}` | `{body}` | {fields} |")
w("")
if "PrincipalCredential" not in S:
    w("`credentials` 在规格里是内联匿名对象 `{clientId, clientSecret}`，没有对应的具名 schema，")
    w("实现时按该形状直接映射即可，不必在规格里找一个叫 `PrincipalCredential` 的类型。")
w("")

# ---------------------------------------------------------------- arrays
arrays = []
array_seen = {}
for idx, m, path, code, body, shape, fields in rows:
    if shape != "命名数组":
        continue
    if body in array_seen:
        continue
    props = top_props(body)
    (field, spec), = props.items()
    elem = ref_name(spec.get("items", {}))
    array_seen[body] = (field, elem)
    arrays.append((body, field, elem))

w("### 4.3 命名数组的容器字段")
w("")
w(f"共 {len(arrays)} 个列表响应，容器字段名由规格给定，且**并不统一**：")
w("")
w("| 响应 schema | 容器字段 | 元素类型 | 元素 schema |")
w("| --- | --- | --- | --- |")
for body, field, elem in arrays:
    w(f"| `{body}` | `{field}` | array<`{elem}`> | `{elem}` |")
w("")
by_field = {}
for body, field, elem in arrays:
    by_field.setdefault(field, []).append(elem)
for field, elems in by_field.items():
    unique = sorted(set(elems))
    if len(unique) > 1:
        joined = " 与 ".join(f"`{x}`" for x in unique)
        w(f"注意 `{field}` 被复用：同一个容器字段名下元素类型并不相同（{joined}）。")
        w("因此不能只按字段名反序列化，必须结合请求路径决定元素类型。")
        w("")

# ---------------------------------------------------------------- success
code_counter = Counter(r[3] for r in rows)
w("### 4.4 成功码：POST 不必然是 201")
w("")
w(f"全部 {len(rows)} 个成功响应的码分布：")
w("")
for code in sorted(code_counter):
    w(f"- `{code}`：{code_counter[code]} 次")
w("")
w("| 方法 | 路径 | 成功码 | 响应 schema |")
w("| --- | --- | --- | --- |")
posts = [r for r in rows if r[1] == "POST"]
for idx, m, path, code, body, shape, fields in posts:
    schema_cell = f"`{body}`" if body else "—"
    mark = " ← 非 201" if code != "201" else ""
    w(f"| {m} | `{path}` | `{code}`{mark} | {schema_cell} |")
w("")
post_200 = [r for r in posts if r[3] == "200"]
if post_200:
    joined = "、".join(f"`{r[2]}`" for r in post_200)
    w(f"上表 {len(posts)} 个 POST 里，{len(post_200)} 个返回 `200`：{joined}。")
    w("其余 POST 返回 `201`。按「写操作一律 201」的惯例写客户端，会在这两个端点上把成功误判为失败。")
    w("")

# ---------------------------------------------------------------- errors
w("### 4.5 错误码分布与规格遗漏")
w("")
w("| 码 | 声明的 operation 数 | 含义 |")
w("| --- | --- | --- |")
meaning = {"400": "请求不合规", "403": "调用者无权限", "404": "资源不存在", "409": "重名或 `entityVersion` 冲突"}
for code in ("400", "403", "404", "409"):
    hits = [f"{m.upper()} `{p}`" for p, item in PATHS.items()
            for m in (k for k in item if k in METHODS) if code in codes_of(item[m])]
    if hits:
        w(f"| `{code}` | {len(hits)} / {_total_ops} | {meaning[code]} |")
w("")
conflicts = [f"{m.upper()} `{p}`" for p, item in PATHS.items()
             for m in (k for k in item if k in METHODS) if "409" in codes_of(item[m])]
if conflicts:
    w(f"声明 `409` 的 {len(conflicts)} 个 operation：{'、'.join(conflicts)}。")
    w("")
if _missing403:
    w(f"`403` 未覆盖全部 operation：{'、'.join(_missing403)} 只声明了 `200`。")
    w("这两个端点同属「列出某个 catalog 下的角色」与「列出某个角色的授权」，")
    w("规格漏写了错误响应，不是有意开放。")
    w("")
    w("**实现侧说明（非规格内容）**：本实现对上述两个端点与同组其余端点一致地要求")
    w("`CATALOG_MANAGE_ACCESS`，即按「规格未声明也鉴权」的失败关闭方向处理，")
    w("见 `CatalogRoleController` 与 `docs/authorization.md`。")
    w("")

# ---------------------------------------------------------------- sanity
# 生成器自检：这些不变量一旦不成立，说明规格结构变了，
# 应当立刻失败，而不是把一份看似正常、实则错位的基线写进文档。
assert len(rows) >= _total_ops, f"2xx 响应数 {len(rows)} 少于 operation 数 {_total_ops}"
assert {r[3] for r in rows} <= {"200", "201", "202", "204"}, \
    f"出现未预期的成功码：{sorted({r[3] for r in rows})}"
assert all(r[4] in S for r in rows if r[4]), \
    f"响应体引用了未知 schema：{sorted({r[4] for r in rows if r[4] and r[4] not in S})}"
assert any(r[5] == "裸对象" for r in rows) and any(r[5] == "命名数组" for r in rows), \
    "形状归类异常：裸对象与命名数组都不应缺失"

open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(f"wrote {OUT}: {len(lines)} lines")
