package com.example.paimonrest.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 管理 API 的权限取值。
 *
 * <p>本文件由 {@code scripts/gen-management-privileges.py} 从
 * {@code spec/polaris-management-service.yml} 生成，取值与规格逐字一致；
 * 修改规格后重新生成即可，不要手改。
 *
 * <p>规格把权限拆成 6 个按资源层级划分的 enum，同名权限在多组中重复出现。
 * 这里用一个并集枚举（64 项）承载全部取值，再用 {@link #allowedFor(String)}
 * 还原规格的分组约束，避免维护 6 份近乎重复的枚举。
 */
public enum Privilege {

    SEMANTIC_MODEL_LIST,
    SEMANTIC_MODEL_CREATE,
    SEMANTIC_MODEL_READ,
    SEMANTIC_MODEL_WRITE,
    SEMANTIC_MODEL_DROP,
    SEMANTIC_MODEL_FULL_METADATA,
    SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE,
    CATALOG_MANAGE_ACCESS,
    CATALOG_MANAGE_CONTENT,
    CATALOG_MANAGE_METADATA,
    CATALOG_READ_PROPERTIES,
    CATALOG_WRITE_PROPERTIES,
    NAMESPACE_CREATE,
    TABLE_CREATE,
    VIEW_CREATE,
    NAMESPACE_DROP,
    TABLE_DROP,
    VIEW_DROP,
    NAMESPACE_LIST,
    TABLE_LIST,
    VIEW_LIST,
    NAMESPACE_READ_PROPERTIES,
    TABLE_READ_PROPERTIES,
    VIEW_READ_PROPERTIES,
    NAMESPACE_WRITE_PROPERTIES,
    TABLE_WRITE_PROPERTIES,
    VIEW_WRITE_PROPERTIES,
    TABLE_READ_DATA,
    TABLE_WRITE_DATA,
    NAMESPACE_FULL_METADATA,
    TABLE_FULL_METADATA,
    VIEW_FULL_METADATA,
    POLICY_CREATE,
    POLICY_WRITE,
    POLICY_READ,
    POLICY_DROP,
    POLICY_LIST,
    POLICY_FULL_METADATA,
    CATALOG_ATTACH_POLICY,
    CATALOG_DETACH_POLICY,
    TABLE_ASSIGN_UUID,
    TABLE_UPGRADE_FORMAT_VERSION,
    TABLE_ADD_SCHEMA,
    TABLE_SET_CURRENT_SCHEMA,
    TABLE_ADD_PARTITION_SPEC,
    TABLE_ADD_SORT_ORDER,
    TABLE_SET_DEFAULT_SORT_ORDER,
    TABLE_ADD_SNAPSHOT,
    TABLE_SET_SNAPSHOT_REF,
    TABLE_REMOVE_SNAPSHOTS,
    TABLE_REMOVE_SNAPSHOT_REF,
    TABLE_SET_LOCATION,
    TABLE_SET_PROPERTIES,
    TABLE_REMOVE_PROPERTIES,
    TABLE_SET_STATISTICS,
    TABLE_REMOVE_STATISTICS,
    TABLE_REMOVE_PARTITION_SPECS,
    TABLE_MANAGE_STRUCTURE,
    NAMESPACE_ATTACH_POLICY,
    NAMESPACE_DETACH_POLICY,
    TABLE_ATTACH_POLICY,
    TABLE_DETACH_POLICY,
    POLICY_ATTACH,
    POLICY_DETACH;

    /** 资源类型 → 该层级允许授予的权限集合，对应规格的 6 个权限 enum。 */
    private static final Map<String, Set<Privilege>> ALLOWED;

    static {
        Map<String, Set<Privilege>> allowed = new LinkedHashMap<>();
        allowed.put("catalog", toSet(
                Privilege.SEMANTIC_MODEL_LIST, Privilege.SEMANTIC_MODEL_CREATE, Privilege.SEMANTIC_MODEL_READ, Privilege.SEMANTIC_MODEL_WRITE, Privilege.SEMANTIC_MODEL_DROP,
                Privilege.SEMANTIC_MODEL_FULL_METADATA, Privilege.SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE, Privilege.CATALOG_MANAGE_ACCESS, Privilege.CATALOG_MANAGE_CONTENT, Privilege.CATALOG_MANAGE_METADATA,
                Privilege.CATALOG_READ_PROPERTIES, Privilege.CATALOG_WRITE_PROPERTIES, Privilege.NAMESPACE_CREATE, Privilege.TABLE_CREATE, Privilege.VIEW_CREATE,
                Privilege.NAMESPACE_DROP, Privilege.TABLE_DROP, Privilege.VIEW_DROP, Privilege.NAMESPACE_LIST, Privilege.TABLE_LIST,
                Privilege.VIEW_LIST, Privilege.NAMESPACE_READ_PROPERTIES, Privilege.TABLE_READ_PROPERTIES, Privilege.VIEW_READ_PROPERTIES, Privilege.NAMESPACE_WRITE_PROPERTIES,
                Privilege.TABLE_WRITE_PROPERTIES, Privilege.VIEW_WRITE_PROPERTIES, Privilege.TABLE_READ_DATA, Privilege.TABLE_WRITE_DATA, Privilege.NAMESPACE_FULL_METADATA,
                Privilege.TABLE_FULL_METADATA, Privilege.VIEW_FULL_METADATA, Privilege.POLICY_CREATE, Privilege.POLICY_WRITE, Privilege.POLICY_READ,
                Privilege.POLICY_DROP, Privilege.POLICY_LIST, Privilege.POLICY_FULL_METADATA, Privilege.CATALOG_ATTACH_POLICY, Privilege.CATALOG_DETACH_POLICY,
                Privilege.TABLE_ASSIGN_UUID, Privilege.TABLE_UPGRADE_FORMAT_VERSION, Privilege.TABLE_ADD_SCHEMA, Privilege.TABLE_SET_CURRENT_SCHEMA, Privilege.TABLE_ADD_PARTITION_SPEC,
                Privilege.TABLE_ADD_SORT_ORDER, Privilege.TABLE_SET_DEFAULT_SORT_ORDER, Privilege.TABLE_ADD_SNAPSHOT, Privilege.TABLE_SET_SNAPSHOT_REF, Privilege.TABLE_REMOVE_SNAPSHOTS,
                Privilege.TABLE_REMOVE_SNAPSHOT_REF, Privilege.TABLE_SET_LOCATION, Privilege.TABLE_SET_PROPERTIES, Privilege.TABLE_REMOVE_PROPERTIES, Privilege.TABLE_SET_STATISTICS,
                Privilege.TABLE_REMOVE_STATISTICS, Privilege.TABLE_REMOVE_PARTITION_SPECS, Privilege.TABLE_MANAGE_STRUCTURE));
        allowed.put("namespace", toSet(
                Privilege.SEMANTIC_MODEL_LIST, Privilege.SEMANTIC_MODEL_CREATE, Privilege.SEMANTIC_MODEL_READ, Privilege.SEMANTIC_MODEL_WRITE, Privilege.SEMANTIC_MODEL_DROP,
                Privilege.SEMANTIC_MODEL_FULL_METADATA, Privilege.SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE, Privilege.CATALOG_MANAGE_ACCESS, Privilege.CATALOG_MANAGE_CONTENT, Privilege.CATALOG_MANAGE_METADATA,
                Privilege.NAMESPACE_CREATE, Privilege.TABLE_CREATE, Privilege.VIEW_CREATE, Privilege.NAMESPACE_DROP, Privilege.TABLE_DROP,
                Privilege.VIEW_DROP, Privilege.NAMESPACE_LIST, Privilege.TABLE_LIST, Privilege.VIEW_LIST, Privilege.NAMESPACE_READ_PROPERTIES,
                Privilege.TABLE_READ_PROPERTIES, Privilege.VIEW_READ_PROPERTIES, Privilege.NAMESPACE_WRITE_PROPERTIES, Privilege.TABLE_WRITE_PROPERTIES, Privilege.VIEW_WRITE_PROPERTIES,
                Privilege.TABLE_READ_DATA, Privilege.TABLE_WRITE_DATA, Privilege.NAMESPACE_FULL_METADATA, Privilege.TABLE_FULL_METADATA, Privilege.VIEW_FULL_METADATA,
                Privilege.POLICY_CREATE, Privilege.POLICY_WRITE, Privilege.POLICY_READ, Privilege.POLICY_DROP, Privilege.POLICY_LIST,
                Privilege.POLICY_FULL_METADATA, Privilege.NAMESPACE_ATTACH_POLICY, Privilege.NAMESPACE_DETACH_POLICY, Privilege.TABLE_ASSIGN_UUID, Privilege.TABLE_UPGRADE_FORMAT_VERSION,
                Privilege.TABLE_ADD_SCHEMA, Privilege.TABLE_SET_CURRENT_SCHEMA, Privilege.TABLE_ADD_PARTITION_SPEC, Privilege.TABLE_ADD_SORT_ORDER, Privilege.TABLE_SET_DEFAULT_SORT_ORDER,
                Privilege.TABLE_ADD_SNAPSHOT, Privilege.TABLE_SET_SNAPSHOT_REF, Privilege.TABLE_REMOVE_SNAPSHOTS, Privilege.TABLE_REMOVE_SNAPSHOT_REF, Privilege.TABLE_SET_LOCATION,
                Privilege.TABLE_SET_PROPERTIES, Privilege.TABLE_REMOVE_PROPERTIES, Privilege.TABLE_SET_STATISTICS, Privilege.TABLE_REMOVE_STATISTICS, Privilege.TABLE_REMOVE_PARTITION_SPECS,
                Privilege.TABLE_MANAGE_STRUCTURE));
        allowed.put("table", toSet(
                Privilege.CATALOG_MANAGE_ACCESS, Privilege.TABLE_DROP, Privilege.TABLE_LIST, Privilege.TABLE_READ_PROPERTIES, Privilege.TABLE_WRITE_PROPERTIES,
                Privilege.TABLE_READ_DATA, Privilege.TABLE_WRITE_DATA, Privilege.TABLE_FULL_METADATA, Privilege.TABLE_ATTACH_POLICY, Privilege.TABLE_DETACH_POLICY,
                Privilege.TABLE_ASSIGN_UUID, Privilege.TABLE_UPGRADE_FORMAT_VERSION, Privilege.TABLE_ADD_SCHEMA, Privilege.TABLE_SET_CURRENT_SCHEMA, Privilege.TABLE_ADD_PARTITION_SPEC,
                Privilege.TABLE_ADD_SORT_ORDER, Privilege.TABLE_SET_DEFAULT_SORT_ORDER, Privilege.TABLE_ADD_SNAPSHOT, Privilege.TABLE_SET_SNAPSHOT_REF, Privilege.TABLE_REMOVE_SNAPSHOTS,
                Privilege.TABLE_REMOVE_SNAPSHOT_REF, Privilege.TABLE_SET_LOCATION, Privilege.TABLE_SET_PROPERTIES, Privilege.TABLE_REMOVE_PROPERTIES, Privilege.TABLE_SET_STATISTICS,
                Privilege.TABLE_REMOVE_STATISTICS, Privilege.TABLE_REMOVE_PARTITION_SPECS, Privilege.TABLE_MANAGE_STRUCTURE));
        allowed.put("view", toSet(
                Privilege.CATALOG_MANAGE_ACCESS, Privilege.VIEW_DROP, Privilege.VIEW_LIST, Privilege.VIEW_READ_PROPERTIES, Privilege.VIEW_WRITE_PROPERTIES,
                Privilege.VIEW_FULL_METADATA));
        allowed.put("policy", toSet(
                Privilege.CATALOG_MANAGE_ACCESS, Privilege.POLICY_READ, Privilege.POLICY_DROP, Privilege.POLICY_WRITE, Privilege.POLICY_LIST,
                Privilege.POLICY_FULL_METADATA, Privilege.POLICY_ATTACH, Privilege.POLICY_DETACH));
        allowed.put("semantic-model", toSet(
                Privilege.CATALOG_MANAGE_ACCESS, Privilege.SEMANTIC_MODEL_READ, Privilege.SEMANTIC_MODEL_WRITE, Privilege.SEMANTIC_MODEL_DROP, Privilege.SEMANTIC_MODEL_FULL_METADATA,
                Privilege.SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    private static Set<Privilege> toSet(Privilege... values) {
        Set<Privilege> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    /**
     * 指定资源类型允许授予的权限集合。
     *
     * @param resourceType 规格 {@code GrantResource.type} 取值
     * @return 不可变集合；资源类型未知时为空集
     */
    public static Set<Privilege> allowedFor(String resourceType) {
        return ALLOWED.getOrDefault(resourceType, Set.of());
    }

    /** 全部资源类型的判别值，顺序与规格 definition 一致。 */
    public static Set<String> resourceTypes() {
        return ALLOWED.keySet();
    }
}
