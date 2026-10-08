#!/usr/bin/env python3
"""校验 docs/management-api-contract.md 与规格逐行一致。

与 gen-management-contract.py 的区别在于**刻意不复用它的任何函数**：
这里把 Markdown 表格反解回结构化数据，再与规格独立比对，
用于发现生成器自身的推导错误（生成器与校验器共用代码时，错误会互相掩盖）。

用法：
    python3 scripts/verify-management-contract.py spec/polaris-management-service.yml docs/management-api-contract.md
"""
import re
import sys
from collections import Counter

import yaml

SPEC = sys.argv[1] if len(sys.argv) > 1 else "spec/polaris-management-service.yml"
DOC = sys.argv[2] if len(sys.argv) > 2 else "docs/management-api-contract.md"

METHODS = ("get", "post", "put", "delete", "head", "patch")
ERROR_CODES = ("400", "403", "404", "409")

spec = yaml.safe_load(open(SPEC, encoding="utf-8"))
doc = open(DOC, encoding="utf-8").read()

failures = []


def check(label, actual, expected):
    if actual == expected:
        print(f"  ✓ {label}")
    else:
        print(f"  ✗ {label}")
        print(f"      文档: {actual}")
        print(f"      规格: {expected}")
        failures.append(label)


def rows_between(start_marker, end_marker):
    """取出两个标记之间的 Markdown 表格行，返回二维单元格数组。"""
    m = re.search(re.escape(start_marker) + r"(.*?)" + re.escape(end_marker), doc, re.S)
    assert m, f"文档中找不到章节：{start_marker!r}"
    rows = []
    for line in m.group(1).splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        # 跳过表头与分隔行
        if not cells[0] or cells[0] == "#" or set(cells[0]) <= {"-"}:
            continue
        rows.append(cells)
    return rows


def strip_code(cell):
    return cell.strip().strip("`")


def code_of(cell):
    """从形如 `` `200` `` 或 `` `200` ← 非 201 `` 的单元格里取出状态码。"""
    m = re.match(r"`(\d{3})`", cell.strip())
    assert m, f"单元格不是状态码：{cell!r}"
    return m.group(1)


# ---------------------------------------------------------------- 规格侧
expect_ops = []
expect_success = []
succ_counter = Counter()
for path, item in spec["paths"].items():
    for m in (k for k in item if k in METHODS):
        expect_ops.append((m.upper(), path))
        for code in item[m].get("responses", {}):
            if str(code).startswith("2"):
                expect_success.append((m.upper(), path, str(code)))
                succ_counter[str(code)] += 1

err_counter = Counter(
    str(code)
    for item in spec["paths"].values()
    for m in (k for k in item if k in METHODS)
    for code in item[m].get("responses", {})
    if str(code) in ERROR_CODES
)

with403 = [
    (m.upper(), path)
    for path, item in spec["paths"].items()
    for m in (k for k in item if k in METHODS)
    if "403" in {str(c) for c in item[m].get("responses", {})}
]

# ---------------------------------------------------------------- §1 端点清单
print("§1 端点清单")
doc_ops = [(r[1], strip_code(r[2])) for r in rows_between("## 1. 端点清单", "个 operation 声明了")]
check(f"operation 数 = {len(expect_ops)}", len(doc_ops), len(expect_ops))
check("方法与路径逐行一致（含声明顺序）", doc_ops, expect_ops)

m = re.search(r"(\d+) / (\d+) 个 operation 声明了 `403`", doc)
if not m:
    m = re.search(r"(\d+)/(\d+) 个 operation 声明了 `403`", doc)
check("403 计数说明", (int(m.group(1)), int(m.group(2))) if m else None,
      (len(with403), len(expect_ops)))

# ---------------------------------------------------------------- §4.1 形状
print("§4.1 成功响应逐 operation 对照")
doc_success = [(r[1], strip_code(r[2]), code_of(r[3]))
               for r in rows_between("### 4.1 成功响应逐 operation 对照", "形状判定规则")]
check(f"2xx 响应数 = {len(expect_success)}", len(doc_success), len(expect_success))
check("方法、路径、码逐行一致", doc_success, expect_success)

# ---------------------------------------------------------------- §4.4 成功码
print("§4.4 成功码分布")
m = re.search(r"### 4\.4[^\n]*\n(.*?)### 4\.5", doc, re.S)
assert m, "找不到 §4.4"
for code, count in sorted(succ_counter.items()):
    got = re.search(rf"- `{code}`：(\d+) 次", m.group(1))
    check(f"码 {code} 次数 = {count}", int(got.group(1)) if got else None, count)

post_expected = [
    (m2.upper(), path, str(next(c for c in item[m2]["responses"] if str(c).startswith("2"))))
    for path, item in spec["paths"].items()
    for m2 in (k for k in item if k in METHODS)
    if m2 == "post"
]

# §4.4 的 POST 表没有 `#` 列，因此方法在 r[0]、路径在 r[1]、码在 r[2]
doc_posts = [
    (r[0], strip_code(r[1]), code_of(r[2]))
    for r in rows_between("### 4.4 成功码", "上表")
    if r[0] == "POST"
]
check(f"POST 行数 = {len(post_expected)}", len(doc_posts), len(post_expected))
check("POST 成功码逐行一致", doc_posts, post_expected)

# ---------------------------------------------------------------- §4.5 错误码
print("§4.5 错误码分布")
for code in ERROR_CODES:
    if err_counter[code] == 0:
        continue
    got = re.search(rf"\| `{code}` \| (\d+) / (\d+) \|", doc)
    check(f"错误码 {code} = {err_counter[code]}", int(got.group(1)) if got else None,
          err_counter[code])

print()
if failures:
    print(f"校验未通过：{len(failures)} 项不一致 → {failures}")
    sys.exit(1)
print("校验通过：契约文档与规格逐行一致。")
