#!/usr/bin/env python3
"""校验控制台与接口规格、服务端静态资源三者一致。

为什么需要它：控制台是用字符串拼 URL 的，而服务端的路径属于契约
（catalog API 见 static/rest-catalog-open-api.yaml，管理 API 见
spec/polaris-management-service.yml）。路径写错时前端只表现为「空列表」或 404，
看不出是拼错了还是服务端改了；而这批路径分散在几十个端点里，靠人工比对不现实。

本脚本不构建前端、不启动服务端，只做静态比对——它读的是磁盘上的产物与源码，
因此可以放进提交前的检查里。

校验项：
  1. 端点存在     src/api/endpoints.js 里的每条路径都能在规格中找到（扩展端点走显式白名单）
  2. 白名单有效   声明为规格外扩展的端点确实被使用，且没有过期条目
  3. 端点引用     src/ 里按名字调用的端点都在 endpoints.js 中声明
  4. 无死条目     endpoints.js 里声明的端点都在 src/ 中被调用过
  5. 基址一致     vite 的 base、路由的 base、构建输出目录、服务端静态资源目录四处指向同一处；
                  以及控制台契约表里的令牌端点路径与服务端下发的 tokenEndpoint 是否一致
  6. 产物完整     已提交的入口页存在，且它引用的每个 assets 文件都存在
  7. 产物新鲜     构建产物不比控制台源码旧（改了源码没重新构建会在这里暴露）
  8. 变更构造器   表结构变更的请求形状与规格里 18 种 SchemaChange 子类型逐字段对齐
  9. 对话框       每个 el-dialog 都声明 destroy-on-close（否则编辑时会残留上一次的状态）
 10. 下拉选项     每个 el-select 都声明了候选来源（漏写 el-option 时点开只有「无数据」，
                  而这类漏写编译、类型检查与单元测试都不会报）
 11. 静态凭据     catalog 自带 AK/SK 这条链路跨「服务端 DTO / 支持的类型 / 能力开关的
                  暴露 / 前端表单」四处，任何一处漏改都表现为「填了没反应」或「填完才 400」

用法：
    python3 scripts/verify-console.py

本机若有多个 Python，确保用的是装了 PyYAML 的那个；build-console.sh 会透传
PYTHON=... 给本脚本。

退出码：0 全部通过，1 存在不一致，2 环境不满足（缺依赖）。
"""
import os
import re
import sys

try:
    import yaml
except ImportError:  # 只在环境缺依赖时触发；脚本要读规格，yaml 是硬依赖
    sys.stderr.write(
        "缺少 PyYAML：本脚本需要解析 OpenAPI 规格。\n"
        "安装方式（任选其一）：\n"
        "  %s -m pip install --user PyYAML\n"
        "  PYTHON=/path/to/python3 scripts/build-console.sh   # 换一个已装 PyYAML 的解释器\n"
        % os.path.basename(sys.executable))
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CONSOLE_DIR = os.path.join(ROOT, "paimon-rest-console")
ENDPOINTS_JS = os.path.join(CONSOLE_DIR, "src/api/endpoints.js")
VITE_CONFIG = os.path.join(CONSOLE_DIR, "vite.config.js")
ROUTER = os.path.join(CONSOLE_DIR, "src/router/index.js")
CLIENT_JS = os.path.join(CONSOLE_DIR, "src/api/client.js")

CATALOG_SPEC = os.path.join(
    ROOT, "paimon-rest-server/src/main/resources/static/rest-catalog-open-api.yaml")
MANAGEMENT_SPEC = os.path.join(ROOT, "spec/polaris-management-service.yml")

CONSOLE_ASSETS = os.path.join(
    ROOT, "paimon-rest-server/src/main/resources/static/console")

SERVER_JAVA = os.path.join(
    ROOT, "paimon-rest-server/src/main/java/io/github/melin/paimonrest")

STORAGE_DTOS = os.path.join(SERVER_JAVA, "dto/StorageDtos.java")
STORAGE_CONFIGS = os.path.join(SERVER_JAVA, "support/StorageConfigs.java")
CONSOLE_DTOS = os.path.join(SERVER_JAVA, "dto/ConsoleDtos.java")
CONSOLE_META_SERVICE = os.path.join(SERVER_JAVA, "service/ConsoleMetaService.java")
MANAGEMENT_CATALOG_SERVICE = os.path.join(
    SERVER_JAVA, "service/ManagementCatalogService.java")

STORAGE_CONFIG_FIELDS = os.path.join(
    CONSOLE_DIR, "src/components/StorageConfigFields.vue")
CATALOGS_VIEW = os.path.join(CONSOLE_DIR, "src/views/CatalogsView.vue")

# 管理规格的 servers[0].url 是 {scheme}://{host}/api/management/v1，路径要去掉基址才可比
MANAGEMENT_BASE = "/api/management/v1"

CONSOLE_BASE = "/console/"

# 规格之外的扩展端点。每一条都要有理由，且必须在控制台里真的被调用——否则就是过期条目。
EXTENSION_ENDPOINTS = {
    ("GET", "/api/console/v1/meta"): "控制台元数据（枚举取值与服务配置摘要），非 Polaris 规格",
    # 登录引导。在服务端鉴权范围之外（WebConfig 用精确路径排除），
    # 因为登录页要先知道服务端支持哪些认证方式，才谈得上发起登录。
    ("GET", "/api/console/v1/auth"): "控制台登录引导（可用认证方式、令牌端点、当前令牌状态）",
    # 用户名 + 密码换令牌：控制台的默认登录方式（账号在服务端配置里，默认 admin/admin）。
    # 与下面那条令牌端点一样必须在鉴权之外——它就是用来换令牌的。
    # 用 JSON 而不是表单：调用方只有控制台，不背 OAuth 的 form-urlencoded 约定。
    ("POST", "/api/console/v1/login"): "控制台用户名密码登录（默认方式，响应是驼峰字段而非 OAuth 形状）",
    # OAuth 2.0 客户端凭据令牌端点。路径与 Polaris 一致（其官方 console 的默认
    # VITE_OAUTH_TOKEN_URL 就是这个地址），但它不是 catalog API 规格里的一节，
    # 因此在这里登记。同样必须在鉴权之外——它就是用来换令牌的。
    ("POST", "/api/catalog/v1/oauth/tokens"): "OAuth 2.0 客户端凭据令牌端点（RFC 6749 第 4.4 节）",
}

problems = []
checks = 0


def check(description, ok, detail=""):
    global checks
    checks += 1
    print(("  [OK]   " if ok else "  [FAIL] ") + description)
    if not ok:
        problems.append(description)
        if detail:
            for line in str(detail).splitlines():
                print("         " + line)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# ------------------------------------------------------------------ 0. 解析输入

endpoints_source = read(ENDPOINTS_JS)
# 形如 `  listDatabases: { method: 'GET', path: '/v1/{prefix}/databases' },`
entry_pattern = re.compile(
    r"^\s*(\w+):\s*\{\s*method:\s*'([A-Z]+)',\s*path:\s*'([^']+)'\s*\},?\s*$", re.M)
endpoints = {}
duplicate_names = []
for match in entry_pattern.finditer(endpoints_source):
    name, method, path = match.group(1), match.group(2), match.group(3)
    if name in endpoints:
        duplicate_names.append(name)
    endpoints[name] = (method, path)

catalog_paths = yaml.safe_load(read(CATALOG_SPEC))["paths"]
management_paths = yaml.safe_load(read(MANAGEMENT_SPEC))["paths"]

spec_routes = set()
for path, operations in catalog_paths.items():
    for method in operations:
        spec_routes.add((method.upper(), path))
for path, operations in management_paths.items():
    for method in operations:
        spec_routes.add((method.upper(), MANAGEMENT_BASE + path))

console_routes = {(method, path) for method, path in endpoints.values()}
all_problems_before = len(problems)

# ------------------------------------------------------------------ 1. 端点存在

print("1. 端点存在性")
missing = sorted(console_routes - spec_routes - set(EXTENSION_ENDPOINTS))
check(f"endpoints.js 的 {len(console_routes)} 条路径都能在规格或扩展白名单中找到",
      not missing,
      "\n".join(f"{method} {path}" for method, path in missing))
check("endpoints.js 中没有重名的端点",
      not duplicate_names,
      ", ".join(duplicate_names))

# ------------------------------------------------------------------ 2. 白名单

print("2. 扩展端点白名单")
stale = sorted(set(EXTENSION_ENDPOINTS) - console_routes)
check("白名单里的扩展端点都在控制台中被使用（没有过期条目）",
      not stale,
      "\n".join(f"{method} {path}" for method, path in stale))
unjustified = sorted(route for route in EXTENSION_ENDPOINTS if not EXTENSION_ENDPOINTS[route])
check("每个扩展端点都写明了理由", not unjustified)

# ------------------------------------------------------------------ 3. 端点引用

print("3. 端点引用")
source_files = []
for base, _, files in os.walk(os.path.join(CONSOLE_DIR, "src")):
    for name in files:
        if name.endswith((".js", ".vue")):
            source_files.append(os.path.join(base, name))
all_source = "\n".join(read(path) for path in source_files)

called = set(re.findall(r"\b(?:get|post|put|del|request)\(\s*'(\w+)'", all_source))
unknown = sorted(called - set(endpoints))
check(f"src/ 中按名字调用的 {len(called)} 个端点都已声明",
      not unknown,
      ", ".join(unknown))

# ------------------------------------------------------------------ 4. 死条目

print("4. 无死条目")
unused = sorted(name for name in endpoints if name not in called)
check(f"endpoints.js 声明的 {len(endpoints)} 个端点都在 src/ 中被调用过",
      not unused,
      ", ".join(unused))

# ------------------------------------------------------------------ 5. 基址一致

print("5. 基址一致")
vite_source = read(VITE_CONFIG)
router_source = read(ROUTER)
expected_out = os.path.relpath(CONSOLE_ASSETS, CONSOLE_DIR)

check(f"vite.config.js 的 base 为 {CONSOLE_BASE}",
      f"base: '{CONSOLE_BASE}'" in vite_source,
      "未找到 base 配置")
check(f"vite.config.js 的 outDir 指向 {expected_out}",
      f"outDir: '{expected_out}'" in vite_source,
      "未找到 outDir 配置")
check(f"路由的 history base 为 {CONSOLE_BASE}",
      f"createWebHistory('{CONSOLE_BASE}')" in router_source,
      "未找到 createWebHistory 配置")
# 服务端侧：静态资源处理器与转发目标
web_config = read(os.path.join(
    ROOT,
    "paimon-rest-server/src/main/java/io/github/melin/paimonrest/config/ConsoleWebConfig.java"))
check("服务端静态资源位置与构建输出目录一致",
      f'CONSOLE_LOCATION = "classpath:/static{CONSOLE_BASE}"' in web_config,
      "ConsoleWebConfig 中的资源位置不是 classpath:/static/console/")
check("client.js 的默认 API 基址是同源（空串）",
      "return localStorage.getItem(BASE_URL_KEY) || ''" in read(CLIENT_JS),
      "默认基址不是同源，会让生产构建后的相对路径请求打到别处")

# 令牌端点的路径在两处出现：控制台的契约表，以及服务端下发给前端的 tokenEndpoint
# （外部 OAuth 客户端按它对接）。控制台实际上用的是自己的契约表，两者不一致时
# 外部客户端打的是对的、浏览器登录打的是错的——「只有登录按钮坏」这种故障
# 很难从现象反推回原因，因此在这里钉死。
auth_controller = read(os.path.join(
    ROOT, "paimon-rest-server/src/main/java/io/github/melin/paimonrest/web/ConsoleAuthController.java"))
advertised = re.search(r'TOKEN_ENDPOINT\s*=\s*"([^"]+)"', auth_controller)
console_token_path = endpoints.get("token", (None, None))[1]
check("控制台的令牌端点与服务端下发的 tokenEndpoint 一致",
      advertised is not None and console_token_path == advertised.group(1),
      f"endpoints.js={console_token_path}，ConsoleAuthController="
      f"{advertised.group(1) if advertised else '未找到 TOKEN_ENDPOINT 常量'}")

# ------------------------------------------------------------------ 6. 产物完整

print("6. 构建产物完整")
index_path = os.path.join(CONSOLE_ASSETS, "index.html")
exists = os.path.isfile(index_path)
check("入口页 index.html 已提交", exists, index_path)
if exists:
    html = read(index_path)
    referenced = re.findall(r'(?:src|href)="(/console/[^"]+)"', html)
    check("入口页引用了构建产物", bool(referenced))
    missing_assets = [
        ref for ref in referenced
        if not os.path.isfile(os.path.join(CONSOLE_ASSETS, ref[len(CONSOLE_BASE):]))
    ]
    check(f"入口页引用的 {len(referenced)} 个资源都存在",
          not missing_assets,
          "\n".join(missing_assets))

# ------------------------------------------------------------------ 7. 产物新鲜度

print("7. 构建产物新鲜度")
if not exists:
    print("  [SKIP] 入口页不存在，先构建再谈新鲜度")
else:
    built_at = os.path.getmtime(index_path)
    stale = []
    watched = [VITE_CONFIG, os.path.join(CONSOLE_DIR, "package.json")]
    for base, _, files in os.walk(os.path.join(CONSOLE_DIR, "src")):
        watched.extend(os.path.join(base, name) for name in files)
    for path in watched:
        if os.path.isfile(path) and os.path.getmtime(path) > built_at:
            stale.append(os.path.relpath(path, ROOT))
    check("构建产物不比控制台源码旧",
          not stale,
          "以下文件晚于构建产物，需要重新执行 npm run build：\n" + "\n".join(stale))

# ------------------------------------------------------------------ 8. 变更构造器

print("8. 变更构造器（表结构变更的请求形状）")

# 表详情的「变更」页签是个构造器：逐个动作填表，最后一次性 POST alterTable。
# 请求体由 TableView.vue 的 toChange() 拼出来，这张表是最容易和服务端漂移的地方——
# 动作名写错、字段名写错，服务端一律只回一句笼统的 400，而报错要等真提交了才看得到。
# 规格里的 SchemaChange 是个判别联合，动作名与各自动作的字段都定义得很清楚，
# 因此可以静态比对：能提交的动作必须在规格里，提交的字段必须在该动作的 schema 里有定义。
table_view_path = os.path.join(CONSOLE_DIR, "src/views/TableView.vue")
table_view = read(table_view_path)

# 表单里可选的 action 值（UI 别名，未必等于提交时的 action）
option_values = re.findall(r"\{\s*value:\s*'(\w+)',\s*label:", table_view)

# 规格里全部 SchemaChange 子类型：action 常量 → 该动作允许的字段（含 action 自身）
schema_changes = {}
for name, node in (yaml.safe_load(read(CATALOG_SPEC)).get("components") or {}).get(
        "schemas", {}).items():
    if not isinstance(node, dict):
        continue
    props = node.get("properties")
    if not props or "action" not in props:
        continue
    const = props["action"].get("const")
    if const:
        schema_changes[const] = set(props)


def top_level_keys(inner):
    """取一个字面对象的一级键。

    必须按层次扫描而不是正则直接抓 `\\w+:`：`move` 这类字段的值是嵌套对象，
    它的字段属于另一个 schema（规格里的 Move），混进来会误报。
    """
    keys, level, index = set(), 0, 0
    while index < len(inner):
        ch = inner[index]
        if ch in "{[(":
            level += 1
        elif ch in "}])":
            level -= 1
        elif level == 0 and (ch.isalnum() or ch == "_"):
            matched = re.match(r"(\w+)\s*([:,}]?)", inner[index:])
            name, sep = matched.group(1), matched.group(2)
            # `key: value` 与 `key,`（简写）都算一级键；其余（如函数调用）不是
            if sep in (":", ",", "}"):
                keys.add(name)
            # 跳过整个标识符：停在中间会让正则从 "ction" 这种位置重新匹配
            index += len(name)
            continue
        index += 1
    return keys


def to_change_cases(source):
    """从 toChange() 的 switch 里取出「表单动作 → 提交的 action 与一级字段」。"""
    match = re.search(r"function\s+toChange\s*\([^)]*\)\s*\{", source)
    if not match:
        return None
    tail = source[match.end():]
    end = re.search(r"\n\}", tail)
    body = tail[:end.start()] if end else tail

    cases = {}
    for block in re.split(r"\n\s*case\s+'", body)[1:]:
        quoted = re.match(r"(\w+)'", block)
        if not quoted:
            continue
        form_value = quoted.group(1)
        # `return { ... }` 与 `const change = { ... }` 两种写法都要认：
        # addColumn 分支因为要条件添加 comment，走的是后一种
        returned = re.search(r"(?:return|=)\s*\{", block)
        if not returned:
            continue
        text, depth = [], 1
        for index in range(returned.end(), len(block)):
            ch = block[index]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    break
            text.append(ch)
        inner = "".join(text)
        action = re.search(r"action:\s*'(\w+)'", inner)
        cases[form_value] = (action.group(1) if action else form_value,
                             top_level_keys(inner))
    return cases


cases = to_change_cases(table_view)
check("TableView.vue 的变更构造器可解析（toChange 与 CHANGE_ACTIONS 都在）",
      cases is not None and bool(option_values),
      "未找到 toChange() 或 CHANGE_ACTIONS；改了函数名就要同步改本脚本的解析")

if cases and option_values:
    unknown_actions = []
    for form_value in option_values:
        action, _ = cases.get(form_value, (None, set()))
        if action is None or action not in schema_changes:
            unknown_actions.append(f"{form_value} → {action}")
    check(f"表单能提交的 {len(option_values)} 个动作都在规格的 SchemaChange 里",
          not unknown_actions,
          "\n".join(sorted(unknown_actions)))

    bad_fields = []
    for form_value in option_values:
        action, keys = cases.get(form_value, (None, set()))
        if action not in schema_changes:
            continue
        for key in sorted(keys - schema_changes[action]):
            bad_fields.append(f"{action}.{key}（规格的字段是 "
                              f"{sorted(schema_changes[action] - {'action'})}）")
    check("提交的字段都在对应动作的 schema 里有定义",
          not bad_fields,
          "\n".join(bad_fields))

# ------------------------------------------------------------------ 9. 对话框状态

print("9. 对话框（打开/关闭时的状态残留）")

# 对话框默认常驻 DOM，内部维护副本的组件只在创建时读一次 props，
# 于是第二次打开（编辑）时表单还停在上一次的值上——保存就会把 A 类型改写成 B 类型。
# `destroy-on-close` 让每次打开都拿到新实例，是这条链路最便宜的保险。
dialog_status = []
vue_files = sorted(
    os.path.join(base, name)
    for base, _, names in os.walk(os.path.join(CONSOLE_DIR, "src"))
    for name in names if name.endswith(".vue"))

for path in vue_files:
    text = read(path)
    for match in re.finditer(r"<el-dialog\b[^>]*>", text, re.S):
        tag = match.group(0)
        if "destroy-on-close" not in tag:
            line = text[:match.start()].count("\n") + 1
            dialog_status.append(
                f"{os.path.relpath(path, ROOT)}:{line} 缺少 destroy-on-close")

dialogs = sum(len(re.findall(r"<el-dialog\b", read(p), re.S)) for p in vue_files)
check(f"全部 {dialogs} 个对话框都声明了 destroy-on-close",
      not dialog_status,
      "\n".join(dialog_status))

# ------------------------------------------------------------------ 10. 下拉选项

print("10. 下拉选项（el-select 的候选来源）")

# `el-select` 漏写 `el-option` 时，它不是一个「空列表」，而是一个点开只写「无数据」的面板：
# 带着 `allow-create` 还能让用户手工敲进去，看着像是能用；没有就成了一个点了没反应的下拉。
# 关键在于这类漏写**编译、类型检查、单元测试全都不会报**——只有在浏览器里点一下才暴露，
# 于是最容易被当成「某个下拉坏了」的偶发问题。所以在这里逐个数出来，让它变成静态可查。
empty_selects = []
select_count = 0
FREE_INPUT_MARK = "el-select-free-input"


def blank_comments(source):
    """把 HTML 注释换成等长空白，但原样保留 `el-select-free-input` 标记。

    换成等长空白而不是删掉，是为了让后面报出的行号仍然是文件里的真实行号。
    保留标记，则是为了让「有意的自由输入」能豁免下面这条检查——豁免写在源码里，
    评审时看得见；藏一张名单在脚本里就没人知道它为什么被放过了。
    """
    def repl(match):
        text = match.group(0)
        parts = []
        rest = text
        while True:
            idx = rest.find(FREE_INPUT_MARK)
            if idx < 0:
                parts.append(re.sub(r"[^\n]", " ", rest))
                break
            parts.append(re.sub(r"[^\n]", " ", rest[:idx]))
            parts.append(FREE_INPUT_MARK)  # 原样保留，长度不变
            rest = rest[idx + len(FREE_INPUT_MARK):]
        return "".join(parts)
    return re.sub(r"<!--.*?-->", repl, source, flags=re.S)


# 标记在 blank_comments 之后仍然长这样
MARK_PLACEHOLDER = FREE_INPUT_MARK

for path in vue_files:
    text = blank_comments(read(path))
    for match in re.finditer(r"<el-select\b", text):
        select_count += 1
        line = text[:match.start()].count("\n") + 1
        location = f"{os.path.relpath(path, ROOT)}:{line}"
        # 标记写在 el-select 紧邻的上方（注释换行也算在内，故留一段余量）
        if MARK_PLACEHOLDER in text[max(0, match.start() - 400):match.start()]:
            continue
        rest = text[match.start():]
        # 只看 select 自己的开始标签：内部 <el-option ... /> 也是自闭合的，
        # 直接找「第一个 />」会把它错当成 select 自闭合，从而把所有正常写法误判成漏写。
        open_tag = re.match(r"<el-select\b[^>]*>", rest, re.S)
        if open_tag is None:
            empty_selects.append(f"{location} 的开始标签没有正常闭合")
            continue
        if open_tag.group(0).endswith("/>"):
            empty_selects.append(
                f"{location} 写成自闭合标签，无法携带 el-option；"
                f"若确是有意的自由输入，在其上方加 <!-- {FREE_INPUT_MARK} --> 说明")
            continue
        body = rest[open_tag.end():]
        close_tag = body.find("</el-select>")
        if close_tag == -1:
            empty_selects.append(f"{location} 找不到配对的 </el-select>")
            continue
        if not re.search(r"<el-option\b|<el-option-group\b|:options\s*=", body[:close_tag]):
            empty_selects.append(
                f"{location} 没有 el-option / el-option-group / :options；"
                f"若确是有意的自由输入，在其上方加 <!-- {FREE_INPUT_MARK} --> 说明")

check(f"全部 {select_count} 个下拉都声明了候选来源（或显式豁免为自由输入）",
      not empty_selects,
      "\n".join(empty_selects))

# ------------------------------------------------------------------ 11. 静态凭据

print("11. 静态凭据（表单 ↔ 服务端字段与能力开关）")

# catalog 自带的 AK/SK 是超出规格的扩展，它这条链路横跨四个文件：服务端 DTO 声明字段、
# StorageConfigs 决定哪些类型有这对字段、ConsoleDtos / ConsoleMetaService 把「本部署能不能
# 加密落库」报给前端、前端据此决定渲染与校验。任何一处漏改的表现都是「填了没反应」
# 或「填完才吃一个 400」——都是要真点进去才发现的那类问题，因此在这里静态钉住。
storage_dtos = read(STORAGE_DTOS)
storage_configs = read(STORAGE_CONFIGS)
fields_vue = read(STORAGE_CONFIG_FIELDS)


def record_shapes(source):
    """从 Java record 声明里取「参数列表」与「storageType() 返回的枚举值」。

    不另抄一份「哪个 record 对应哪个 storageType」的名单：抄了就会漂移，
    而漂移的表现是校验静默放过一个已经改了名字的类型。
    """
    shapes = {}
    for match in re.finditer(r"public record (\w+)\(", source):
        name = match.group(1)
        tail = source[match.end():]
        next_record = re.search(r"\n    public record ", tail)
        block = tail[:next_record.start()] if next_record else tail
        header = block[:block.index(")")] if ")" in block else block
        wire = re.search(r"ManagementEnums\.StorageType\.(\w+)", block)
        shapes[name] = (header, wire.group(1) if wire else None)
    return shapes


def parameter_names(header):
    """record 头里的参数名。先去掉泛型（`List<String>` 里的逗号不参与分隔）。"""
    names = []
    for part in re.sub(r"<[^>]*>", "", header).split(","):
        part = part.strip()
        if part:
            names.append(re.split(r"\s+", part)[-1])
    return names


shapes = record_shapes(storage_dtos)
by_type = {wire: name for name, (_, wire) in shapes.items() if wire}

server_types = []
method = re.search(
    r"supportsStaticCredentials\(StorageConfigInfo info\)\s*\{(.*?)\n    \}",
    storage_configs, re.S)
for cls in re.findall(r"instanceof (\w+)", method.group(1) if method else ""):
    if shapes.get(cls, (None, None))[1]:
        server_types.append(shapes[cls][1])
server_types = sorted(server_types)

vue_types_source = re.search(
    r"STATIC_CREDENTIAL_TYPES\s*=\s*\[([^\]]*)\]", fields_vue)
vue_types = sorted(re.findall(r"'([^']+)'", vue_types_source.group(1))) \
    if vue_types_source else []

check("表单承载静态凭据的类型与服务端 supportsStaticCredentials 一致",
      server_types and vue_types == server_types,
      f"StorageConfigs.java={server_types or '未解析出 instanceof 分支'}，"
      f"StorageConfigFields.vue={vue_types or '未找到 STATIC_CREDENTIAL_TYPES'}")

vue_keys_source = re.search(
    r"STATIC_CREDENTIAL_KEYS\s*=\s*\[([^\]]*)\]", fields_vue)
vue_keys = sorted(re.findall(r"'([^']+)'", vue_keys_source.group(1))) \
    if vue_keys_source else []
# 「像凭据的」参数名。新加第三个凭据字段却忘了在表单里处理时，这条会报出来
CREDENTIAL_HINT = re.compile(r"(?i)accesskey|secret|credential")

mismatched = []
for wire in vue_types:
    shape = shapes.get(by_type.get(wire, ""), None)
    if shape is None:
        mismatched.append(f"{wire} 在 StorageDtos.java 里找不到对应的 record")
        continue
    declared = sorted(name for name in parameter_names(shape[0])
                      if CREDENTIAL_HINT.search(name))
    if declared != vue_keys:
        mismatched.append(f"{wire}: 服务端 {declared}，前端 {vue_keys}")
check(f"三种类型的凭据字段名与服务端 DTO 逐字一致（{vue_keys or '未知'}）",
      vue_keys and not mismatched,
      "\n".join(mismatched))

# 这两个字段是本工程加的：万一上游规格哪天也加了同名但语义不同的字段，
# 这条会失败，提醒先去对齐语义再决定是否还叫「扩展」。
management_schemas = (yaml.safe_load(read(MANAGEMENT_SPEC)).get("components")
                      or {}).get("schemas") or {}


def schema_properties(schema):
    names = set(schema.get("properties") or {}) if isinstance(schema, dict) else set()
    for part in (schema or {}).get("allOf") or []:
        if isinstance(part, dict):
            names |= set(part.get("properties") or {})
    return names


spec_properties = (schema_properties(management_schemas.get("StorageConfigInfo"))
                   | schema_properties(management_schemas.get("AwsStorageConfigInfo")))
overlapping = sorted(set(vue_keys) & spec_properties)
check("规格里没有这两个字段（它们确实是本工程的扩展）",
      bool(vue_keys) and not overlapping,
      f"管理规格的 StorageConfigInfo / AwsStorageConfigInfo 已含 {overlapping}，"
      f"需要重新确认语义后再决定是否沿用同一组字段名")

console_dtos = read(CONSOLE_DTOS)
meta_service = read(CONSOLE_META_SERVICE)
check("服务端在 meta 里声明并如实赋值 staticCredentialsEnabled",
      "boolean staticCredentialsEnabled" in console_dtos and "cipher.available()" in meta_service,
      "ConsoleDtos.Enums 需要声明该字段，ConsoleMetaService.enums() 需要传 cipher.available()")

catalogs_view = read(CATALOGS_VIEW)
wiring = {
    "CatalogsView 从 meta 取值": "enums?.staticCredentialsEnabled" in catalogs_view,
    "CatalogsView 传给表单": ':static-credentials-enabled="staticCredentialsEnabled"' in catalogs_view,
    "表单声明该 prop": "staticCredentialsEnabled: { type: Boolean, default: false }" in fields_vue,
}
check("能力开关从 meta 一路传到表单的 prop",
      all(wiring.values()),
      "\n".join(name for name, ok in wiring.items() if not ok))

check("能力关着时表单给出可操作的提示（而不是把字段藏起来）",
      "paimon.rest.storage.credential-secret-key" in fields_vue
      and "openssl rand -base64 32" in fields_vue,
      "StorageConfigFields.vue 应说明该开哪个配置项以及生成密钥的命令")

# 读路径的脱敏：`toDto` 只要漏掉这一层，密文就会原样出现在每个 catalog 的响应里
# ——而客户端拿到一串 base64 并不会报错，它只会安静地把密文当成配置再写回去。
check("管理 API 读路径对存储配置脱敏（withoutSecrets）",
      "withoutSecrets" in read(MANAGEMENT_CATALOG_SERVICE),
      "ManagementCatalogService 的 toDto 需要在返回前抹掉 secretAccessKey 的密文")

# ------------------------------------------------------------------ 汇总

print()
if problems:
    print(f"校验未通过：{len(problems)} / {checks} 项不一致")
    sys.exit(1)
print(f"全部校验通过：{checks} 项")
