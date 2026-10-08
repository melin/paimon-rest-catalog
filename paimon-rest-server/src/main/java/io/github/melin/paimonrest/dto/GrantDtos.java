package io.github.melin.paimonrest.dto;

import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权载体 {@code GrantResource} 的强类型表示。
 *
 * <p>规格用 {@code discriminator: {propertyName: type}} 把它定义成 6 个 schema 的联合：
 * {@code catalog} / {@code namespace} / {@code table} / {@code view} / {@code policy} /
 * {@code semantic-model}。请求按 {@code Map} 接收后由 {@link #from(Map)} 解析成本记录，
 * 响应由 {@link #toJson()} 还原成规格形状。
 *
 * <p>两种资源类型的命名空间与对象名不同名，规格分别在各自 schema 中声明为
 * {@code tableName} / {@code viewName} / {@code policyName} / {@code semanticModelName}，
 * 因此对外字段名不能统一，这里用 {@link GrantType} 映射。
 */
public final class GrantDtos {

    private GrantDtos() {
    }

    /** 规格中各资源类型对应的对象名字段名；catalog 与 namespace 没有对象名。 */
    private static String objectFieldName(GrantType type) {
        return switch (type) {
            case TABLE -> "tableName";
            case VIEW -> "viewName";
            case POLICY -> "policyName";
            case SEMANTIC_MODEL -> "semanticModelName";
            case CATALOG, NAMESPACE -> "";
        };
    }

    /**
     * 一条授权：某个 catalog role 在某资源上持有某项权限。
     *
     * @param type       资源类型判别值
     * @param namespace  多级命名空间路径；{@link GrantType#CATALOG} 时为空列表
     * @param objectName 对象名；{@link GrantType#CATALOG} 与 {@link GrantType#NAMESPACE} 时为 {@code null}
     * @param privilege  权限
     */
    public record GrantSpec(GrantType type,
                            List<String> namespace,
                            String objectName,
                            Privilege privilege) {

        /**
         * 从规格形状的 JSON 对象解析。
         *
         * @throws ApiException 400：{@code type} 缺失或未知、必填字段缺失、权限不属于该资源类型
         */
        public static GrantSpec from(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                throw ApiException.badRequest("grant is required");
            }
            String rawType = Values.string(raw.get("type"));
            if (rawType == null || rawType.isBlank()) {
                throw ApiException.badRequest("grant.type is required");
            }
            GrantType type = GrantType.parse(rawType).orElseThrow(() -> ApiException.badRequest(
                    "Unsupported grant.type: " + rawType
                            + "; expected one of " + java.util.Arrays.stream(GrantType.values())
                            .map(GrantType::wireName).toList()));

            List<String> namespace = Values.stringList(raw.get("namespace"));
            String objectName = null;
            String objectField = objectFieldName(type);
            if (!objectField.isEmpty()) {
                if (namespace.isEmpty()) {
                    throw ApiException.badRequest("grant.namespace is required for type " + type.wireName());
                }
                objectName = Values.string(raw.get(objectField));
                if (objectName == null || objectName.isBlank()) {
                    throw ApiException.badRequest(
                            "grant." + objectField + " is required for type " + type.wireName());
                }
            } else if (type == GrantType.CATALOG && !namespace.isEmpty()) {
                // catalog 级授权不带命名空间，带了说明客户端把层级搞混了
                throw ApiException.badRequest("grant.namespace must be absent for type catalog");
            }

            String rawPrivilege = Values.string(raw.get("privilege"));
            if (rawPrivilege == null || rawPrivilege.isBlank()) {
                throw ApiException.badRequest("grant.privilege is required");
            }
            Privilege privilege = parsePrivilege(rawPrivilege);
            if (!Privilege.allowedFor(type.wireName()).contains(privilege)) {
                throw ApiException.badRequest("Privilege " + privilege
                        + " is not grantable on resource type " + type.wireName());
            }
            return new GrantSpec(type, namespace, objectName, privilege);
        }

        private static Privilege parsePrivilege(String value) {
            for (Privilege candidate : Privilege.values()) {
                if (candidate.name().equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
            // 60 余项取值，全部列举会让错误信息不可读，因此只回报数量
            throw ApiException.badRequest("Unknown privilege: " + value
                    + "; see Privilege enum for the " + Privilege.values().length + " accepted values");
        }

        /** 还原成规格形状，供列表与回显使用。 */
        public Map<String, Object> toJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type.wireName());
            if (!namespace.isEmpty()) {
                result.put("namespace", new ArrayList<>(namespace));
            }
            String objectField = objectFieldName(type);
            if (!objectField.isEmpty()) {
                result.put(objectField, objectName);
            }
            result.put("privilege", privilege.name());
            return result;
        }
    }
}
