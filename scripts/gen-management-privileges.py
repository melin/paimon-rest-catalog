#!/usr/bin/env python3
"""从 Polaris 管理规格直出权限枚举 Java 源码。

规格把权限拆成 6 个按资源层级划分的 enum（CatalogPrivilege、NamespacePrivilege 等），
同名权限在多组中重复出现。Java 侧用一个并集枚举承载全部取值，
再用「资源类型 → 允许集合」还原规格的分组约束，避免在代码里维护 6 份近乎重复的枚举。

用法：gen-management-privileges.py <spec.yml> <输出 .java 路径>
"""
import sys
import yaml

SPEC, OUT = sys.argv[1], sys.argv[2]
d = yaml.safe_load(open(SPEC, encoding="utf-8"))
S = d["components"]["schemas"]

# 规格中的 6 个权限枚举 → 本实现的资源类型判别值
ENUM_TO_RESOURCE = {
    "CatalogPrivilege": "catalog",
    "NamespacePrivilege": "namespace",
    "TablePrivilege": "table",
    "ViewPrivilege": "view",
    "PolicyPrivilege": "policy",
    "SemanticModelPrivilege": "semantic-model",
}

groups = {r: S[n]["enum"] for n, r in ENUM_TO_RESOURCE.items()}

# 并集保持稳定顺序：先按首次出现顺序收集
union = []
for r in ENUM_TO_RESOURCE.values():
    for p in groups[r]:
        if p not in union:
            union.append(p)

L = []
w = L.append
w("package com.example.paimonrest.dto;")
w("")
w("import java.util.Collections;")
w("import java.util.LinkedHashMap;")
w("import java.util.LinkedHashSet;")
w("import java.util.Map;")
w("import java.util.Set;")
w("")
w("/**")
w(" * 管理 API 的权限取值。")
w(" *")
w(" * <p>本文件由 {@code scripts/gen-management-privileges.py} 从")
w(" * {@code spec/polaris-management-service.yml} 生成，取值与规格逐字一致；")
w(" * 修改规格后重新生成即可，不要手改。")
w(" *")
w(f" * <p>规格把权限拆成 6 个按资源层级划分的 enum，同名权限在多组中重复出现。")
w(f" * 这里用一个并集枚举（{len(union)} 项）承载全部取值，再用 {{@link #allowedFor(String)}}")
w(" * 还原规格的分组约束，避免维护 6 份近乎重复的枚举。")
w(" */")
w("public enum Privilege {")
w("")
for i, p in enumerate(union):
    w(f"    {p}{',' if i < len(union) - 1 else ';'}")
w("")
w("    /** 资源类型 → 该层级允许授予的权限集合，对应规格的 6 个权限 enum。 */")
w("    private static final Map<String, Set<Privilege>> ALLOWED;")
w("")
w("    static {")
w("        Map<String, Set<Privilege>> allowed = new LinkedHashMap<>();")
for r in ENUM_TO_RESOURCE.values():
    w(f"        allowed.put(\"{r}\", toSet(")
    vals = groups[r]
    for i in range(0, len(vals), 5):
        chunk = ", ".join(f"Privilege.{v}" for v in vals[i:i + 5])
        sep = "," if i + 5 < len(vals) else "));"
        w(f"                {chunk}{sep}")
w("        ALLOWED = Collections.unmodifiableMap(allowed);")
w("    }")
w("")
w("    private static Set<Privilege> toSet(Privilege... values) {")
w("        Set<Privilege> result = new LinkedHashSet<>();")
w("        Collections.addAll(result, values);")
w("        return Collections.unmodifiableSet(result);")
w("    }")
w("")
w("    /**")
w("     * 指定资源类型允许授予的权限集合。")
w("     *")
w("     * @param resourceType 规格 {@code GrantResource.type} 取值")
w("     * @return 不可变集合；资源类型未知时为空集")
w("     */")
w("    public static Set<Privilege> allowedFor(String resourceType) {")
w("        return ALLOWED.getOrDefault(resourceType, Set.of());")
w("    }")
w("")
w("    /** 全部资源类型的判别值，顺序与规格 definition 一致。 */")
w("    public static Set<String> resourceTypes() {")
w("        return ALLOWED.keySet();")
w("    }")
w("}")
open(OUT, "w", encoding="utf-8").write("\n".join(L) + "\n")
print(f"wrote {OUT}: {len(union)} privileges, groups={ {r: len(v) for r, v in groups.items()} }")
