#!/usr/bin/env python3
"""校验 docs/spark-sql-reference.md 与实现、规格三者一致。

为什么需要它：SQL 参考文档里最贵的一类错误是「写得很像但不成立」——
漏掉一条语句、权限名少一个字母、结果列多一列。这些靠人工读稿看不出来，
但可以机械比对。本脚本不生成文档，只做校验，与生成器分开实现
（共用代码时推导错误会互相掩盖）。

校验项：
  1. 语句覆盖    文档第 2 节的语句数 == 语法文件 statement 规则的备选数
  2. 示例完整    文档第 11 节的示例块覆盖全部 21 条语句
  3. 权限清单    第 8.3 节列出的权限名 == 规格六个 enum 的并集，且分组归属一致
  4. 资源写法    第 8.2 节的六种资源写法在语法与规格判别字段中都能对上
  5. 结果列      第 9 节与实现声明的列名一致
  6. 配置项      第 1.3 节的配置键 == 实现里的配置常量
  7. 端点映射    第 2 节给出的 REST 路径在客户端代码里存在
  8. 保留字      第 3.3 节列出的关键字 == 语法文件的词法关键字，且声明的数量正确

用法：
    python3 scripts/verify-spark-sql-doc.py [规格] [文档]

退出码非 0 表示存在不一致。
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
        "  python3 -m pip install --user PyYAML\n"
        "  或者指向一个已经装了 PyYAML 的解释器：\n"
        "    PYTHON=/path/to/python3 scripts/e2e-spark-sql.sh\n")
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "spec/polaris-management-service.yml")
DOC = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "docs/spark-sql-reference.md")

GRAMMAR = os.path.join(ROOT, "paimon-rest-spark/src/main/antlr4/io/github/melin/paimonrest/spark/parser/ManagementSql.g4")
COMMANDS = os.path.join(ROOT, "paimon-rest-spark/src/main/scala/io/github/melin/paimonrest/spark/ManagementCommands.scala")
EXTENSIONS = os.path.join(ROOT, "paimon-rest-spark/src/main/scala/io/github/melin/paimonrest/spark/ManagementSparkExtensions.scala")
CLIENT = os.path.join(ROOT, "paimon-rest-spark/src/main/java/io/github/melin/paimonrest/spark/client/ManagementApiClient.java")

problems = []
checks = 0


def check(label, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print(f"  OK   {label}")
    else:
        print(f"  FAIL {label}")
        if detail:
            for line in str(detail).splitlines():
                print(f"         {line}")
        problems.append(label)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


doc = read(DOC)
grammar = read(GRAMMAR)

# ------------------------------------------------------------------ 1. 语句覆盖

statement_rule = re.search(r"^statement\s*\n\s*:\s*(.*?)\s*;", grammar, re.S | re.M)
branches = [b.strip() for b in statement_rule.group(1).split("|")] if statement_rule else []
print(f"语法 statement 规则的备选数：{len(branches)}")

overview = re.search(r"## 2\. 语句总览(.*?)\n---\n", doc, re.S)
overview = overview.group(1) if overview else ""
overview_rows = re.findall(r"^\|\s*(\d+)\s*\|", overview, re.M)
check("语句覆盖：文档第 2 节的行数与语法备选数一致",
      len(branches) == len(overview_rows) == 21,
      f"语法 {len(branches)} / 文档 {len(overview_rows)}，两者都应为 21")

# ------------------------------------------------------------------ 2. 示例覆盖

# 每条语句 → 能唯一识别它的正则。顺序与 §2 总览表一致。
SIGNATURES = [
    ("createPrincipal",      r"CREATE\s+PRINCIPAL\s+(?!ROLE)"),
    ("dropPrincipal",        r"DROP\s+PRINCIPAL\s+(?!ROLE)"),
    ("alterPrincipal",       r"ALTER\s+PRINCIPAL\s+(?!ROLE)"),
    ("resetPrincipal",       r"(?:RESET|ROTATE)\s+PRINCIPAL\b"),
    ("createPrincipalRole",  r"CREATE\s+PRINCIPAL\s+ROLE\b"),
    ("dropPrincipalRole",    r"DROP\s+PRINCIPAL\s+ROLE\b"),
    ("alterPrincipalRole",   r"ALTER\s+PRINCIPAL\s+ROLE\b"),
    ("createCatalogRole",    r"CREATE\s+CATALOG\s+ROLE\b"),
    ("dropCatalogRole",      r"DROP\s+CATALOG\s+ROLE\b"),
    ("alterCatalogRole",     r"ALTER\s+CATALOG\s+ROLE\b"),
    ("grantPrincipalRole",   r"GRANT\s+PRINCIPAL\s+ROLE\b"),
    ("revokePrincipalRole",  r"REVOKE\s+PRINCIPAL\s+ROLE\b"),
    ("grantCatalogRole",     r"GRANT\s+CATALOG\s+ROLE\b"),
    ("revokeCatalogRole",    r"REVOKE\s+CATALOG\s+ROLE\b"),
    ("grantPrivileges",      r"GRANT\s+[A-Z_]+\s*(?:,\s*[A-Z_]+\s*)*ON\b"),
    ("revokePrivileges",     r"REVOKE\s+[A-Z_]+\s*(?:,\s*[A-Z_]+\s*)*ON\b"),
    ("showPrincipals",       r"SHOW\s+PRINCIPALS\b"),
    ("showPrincipalRoles",   r"SHOW\s+PRINCIPAL\s+ROLES\b"),
    ("showCatalogRoles",     r"SHOW\s+CATALOG\s+ROLES\b"),
    ("showGrants",           r"SHOW\s+GRANTS\b"),
    ("showCatalogs",         r"SHOW\s+CATALOGS\b"),
]
check("语句标识表覆盖全部语法备选",
      sorted(b for b, _ in SIGNATURES) == sorted(branches),
      "语法侧独有：" + ", ".join(sorted(set(branches) - {b for b, _ in SIGNATURES})) + "\n"
      "文档侧独有：" + ", ".join(sorted({b for b, _ in SIGNATURES} - set(branches))))

example = re.search(r"<!-- doc-example:begin -->(.*?)<!-- doc-example:end -->", doc, re.S)
if not example:
    check("存在第 11 节示例块（doc-example 标记）", False, "未找到 doc-example:begin / end 标记")
    example_body = ""
else:
    example_body = example.group(1)
missing = [name for name, pattern in SIGNATURES
           if not re.search(pattern, example_body, re.I)]
check("示例块覆盖全部 21 条语句", not missing,
      "示例中缺少：" + ", ".join(missing))

# ------------------------------------------------------------------ 3. 权限清单

spec = yaml.safe_load(read(SPEC))
schemas = spec["components"]["schemas"]
TIERS = [("catalog", "CatalogPrivilege"), ("namespace", "NamespacePrivilege"),
         ("table", "TablePrivilege"), ("view", "ViewPrivilege"),
         ("policy", "PolicyPrivilege"), ("semantic model", "SemanticModelPrivilege")]
per_tier = {name: schemas[schema]["enum"] for name, schema in TIERS}
union = {value for values in per_tier.values() for value in values}
tiers_of = {value: frozenset(t for t, _ in TIERS if value in per_tier[t]) for value in union}

section = re.search(r"### 8\.3 权限清单(.*?)\n### 8\.4", doc, re.S)
check("存在第 8.3 节权限清单", section is not None)
section = section.group(1) if section else ""

# 权限名统一写成 `XXX_YYY`，且一定含下划线；这样能排除正文里出现的 `ON` 等反向引用
listed = set(re.findall(r"`([A-Z][A-Z0-9]*_[A-Z0-9_]+)`", section))
check(f"权限名与规格并集一致（{len(union)} 项）",
      listed == union,
      "文档多出：" + ", ".join(sorted(listed - union)) + "\n"
      "文档缺少：" + ", ".join(sorted(union - listed)))

# 分组归属：解析「**……可用**（N 项）：」后面的权限列表
groups = re.findall(r"\*\*([^*]+?)\*\*（(\d+) 项）\s*[:：]\s*\n+\s*([^\n]+(?:\n[^\n]+)*?)\n\s*\n", section, re.S)
observed = {}
for heading, count, body in groups:
    names = re.findall(r"`([A-Z][A-Z0-9]*_[A-Z0-9_]+)`", body)
    if "六个层级" in heading:
        where = frozenset(t for t, _ in TIERS)
    else:
        where = frozenset(t for t, _ in TIERS if t in heading)
    observed[where] = (int(count), names)

expected_groups = {}
for value, where in tiers_of.items():
    expected_groups.setdefault(where, []).append(value)

check("权限分组数与规格分组数一致",
      len(observed) == len(expected_groups),
      f"文档 {len(observed)} 组 / 规格 {len(expected_groups)} 组，"
      f"文档分组：{sorted(len(k) for k in observed)}，规格分组：{sorted(len(k) for k in expected_groups)}")

for where, expected in sorted(expected_groups.items(), key=lambda kv: -len(kv[0])):
    counted, names = observed.get(where, (None, []))
    label = "、".join(sorted(where))
    check(f"分组「{label}」的归属与计数（{len(expected)} 项）",
          sorted(names) == sorted(expected) and counted == len(expected),
          f"规格 {len(expected)} 项 / 文档声明 {counted} 项 / 文档列出 {len(names)} 项\n"
          f"文档多出：{sorted(set(names) - set(expected))}\n"
          f"文档缺少：{sorted(set(expected) - set(names))}")

# ------------------------------------------------------------------ 4. 资源写法

# grantResource 规则体里每行一个备选（首行是 `: x`，其余是 `| y`）
grant_rule = re.search(r"^grantResource\s*\n(.*?)\s*;", grammar, re.S | re.M).group(1)
grant_resources = re.findall(r"\b(\w+Resource)\b", grant_rule)
discriminator = schemas["GrantResource"]["discriminator"]["mapping"]
check("六种资源写法在语法与规格判别字段中一一对应",
      len(grant_resources) == len(discriminator) == 6,
      f"语法 {len(grant_resources)} 种 / 规格 {len(discriminator)} 种")

section_82 = re.search(r"### 8\.2 六种资源写法(.*?)\n### 8\.3", doc, re.S)
body_82 = section_82.group(1) if section_82 else ""
# 表格每一行的第 1 列是资源写法、第 2 列是判别字段取值
resource_rows = re.findall(r"^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`", body_82, re.M)
type_values = set(discriminator.keys())
doc_types = {row[1] for row in resource_rows}
check("第 8.2 节的资源 type 取值与规格判别字段一致",
      doc_types == type_values,
      f"规格 {sorted(type_values)}\n文档 {sorted(doc_types)}\n"
      f"文档少写：{sorted(type_values - doc_types)}；文档多写：{sorted(doc_types - type_values)}")
check("第 8.2 节列出了六种资源写法",
      len(resource_rows) == len(grant_resources) == 6,
      f"文档列出 {len(resource_rows)} 种：{[row[0] for row in resource_rows]}")

# ------------------------------------------------------------------ 5. 结果列

commands = read(COMMANDS)
impl_columns = sorted(set(re.findall(r'attr\("([a-z_]+)"', commands)))
unused = [name for name in impl_columns if f"`{name}`" not in doc]
check(f"实现声明的全部列名都在文档中出现（{len(impl_columns)} 个）",
      not unused,
      "文档未提及：" + ", ".join(unused))

SHOW_COLUMNS = {
    "SHOW CATALOGS": ["name", "type"],
    "SHOW PRINCIPALS": ["name", "client_id"],
    "SHOW PRINCIPAL ROLES": ["name", "federated"],
    "SHOW GRANTS": ["catalog", "catalog_role", "type", "resource", "namespace",
                    "object_name", "privilege"],
}
section_9 = re.search(r"## 9\. SHOW(.*?)\n## 10\.", doc, re.S).group(1)
for label, columns in SHOW_COLUMNS.items():
    block = re.search(r"### 9\.\d+ `" + re.escape(label) + r"`(.*?)(?=\n### |\Z)", section_9, re.S)
    body = block.group(1) if block else ""
    absent = [c for c in columns if f"`{c}`" not in body]
    check(f"{label} 的结果列在文档中写全", not absent,
          "缺少：" + ", ".join(absent))

# ------------------------------------------------------------------ 6. 配置项

extensions = read(EXTENSIONS)
config_keys = sorted(set(re.findall(r'val\s+[A-Z_]+\s*=\s*"(spark\.[a-zA-Z.]+)"', extensions)))
docs_keys = set(re.findall(r"`(spark\.paimon\.rest\.[a-zA-Z.]+)`", doc))
missing_keys = [key for key in config_keys if key not in docs_keys]
check(f"实现的配置键都在文档中列出（{len(config_keys)} 个）",
      not missing_keys,
      "文档未提及：" + ", ".join(missing_keys))

# ------------------------------------------------------------------ 7. 端点映射

client = read(CLIENT)
# 第 2 节表格里的路径模板，转成客户端代码里的字符串拼接形态
table = re.search(r"## 2\. 语句总览(.*?)\n### 结果集形态", doc, re.S).group(1)
paths = re.findall(r"`(?:POST|PUT|GET|DELETE) (`?[A-Z]+`?[^`|]*)`", table)
missing_paths = []
for path in paths:
    core = re.sub(r"\{(\w+)\}", "X", path).strip()
    # 客户端用常量拼接，取路径最后一段做存在性检查即可
    tail = core.split("/")[-1]
    if tail and tail.upper() not in client.upper() and f'"{tail}"' not in client:
        missing_paths.append(core)
check("第 2 节给出的端点路径都能在客户端代码中找到出处",
      not missing_paths,
      "客户端未出现：" + "; ".join(missing_paths))

# ------------------------------------------------------------------ 8. 保留字

# 词法里的关键字定义形如 `ALTER : A L T E R ;`——用字母片段写成，因此大小写不敏感。
# 取反字符集（如 `[aA]`）不是关键字，故正则要求右侧全为单个字母加空格。
lexer_keywords = set(re.findall(r"^([A-Z]+)\s*:\s*(?:[A-Z]\s+)+[A-Z]\s*;", grammar, re.M))

section_33 = re.search(r"### 3\.3 关键字是保留字(.*?)\n### 3\.4", doc, re.S)
body_33 = section_33.group(1) if section_33 else ""
doc_keywords = set(re.findall(r"`([A-Z]+)`", body_33))
check(f"第 3.3 节的保留字清单与语法文件一致（{len(lexer_keywords)} 个）",
      doc_keywords == lexer_keywords,
      "文档多出：" + ", ".join(sorted(doc_keywords - lexer_keywords)) + "\n"
      "文档缺少：" + ", ".join(sorted(lexer_keywords - doc_keywords)))

declared = re.search(r"这\s*(\d+)\s*个词", body_33)
check("第 3.3 节声明的保留字数量正确",
      declared is not None and int(declared.group(1)) == len(lexer_keywords),
      f"声明 {declared.group(1) if declared else '未找到'} 个 / 实际 {len(lexer_keywords)} 个")

# ------------------------------------------------------------------ 汇总

print()
if problems:
    print(f"校验未通过：{len(problems)} / {checks} 项不一致")
    sys.exit(1)
print(f"全部校验通过：{checks} 项")
