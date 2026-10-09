package io.github.melin.paimonrest.config;

import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.dto.Privilege;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * catalog API（{@code /v1/**}）端点 → 所需权限的映射表。
 *
 * <p>Paimon Rest Catalog 规格本身不描述访问控制，这套映射取自 Polaris 的
 * catalog API 授权约定：把每个端点归到「行使哪项权限」上，让
 * {@link io.github.melin.paimonrest.service.AuthorizationService} 用同一套 RBAC 判定。
 *
 * <p><b>为什么用路径而不是逐个控制器加注解。</b>
 * catalog API 有 60 个端点，逐处标注会有两处风险：漏标（静默放行）和标注与实际
 * 路径漂移。集中成一张表后，映射可以被单测逐条覆盖，也能在
 * 「已登记端点集合」与「实际端点集合」不一致时立刻暴露。
 *
 * <p><b>失败关闭。</b>授权开启时，落在 {@code /v1/**} 下、但既不在本表登记、
 * 也不属于 {@link #isPublic(String)} 的请求会被拒绝（403），而不是放行。
 * 这样新增端点若忘记登记会立刻报错，不会留下静默的越权入口。
 *
 * <p><b>规格没有对应权限的端点。</b>Paimon 比 Polaris 多出 function（用户自定义函数）
 * 与 semantic view 之外的若干子资源，规格的六个权限 enum 里没有 function 层级，
 * 这里把 function 归到其所在命名空间的权限上，不做虚构的权限取值。
 * 详见 {@code docs/management-api-contract.md} 的授权映射章节。
 */
public final class CatalogAccessRules {

    /** catalog API 的路径前缀。 */
    private static final String V1 = "/v1/";

    /**
     * 一个端点的授权要求。
     *
     * @param catalog      catalog 名，即路径中的 {@code {prefix}}
     * @param resourceType 资源类型判别值，决定授权的作用域层级
     * @param namespace    多级命名空间；catalog 级要求传空列表
     * @param objectName   对象名；catalog / namespace 级要求传 {@code null}
     * @param privilege    要求的权限
     */
    public record Requirement(String catalog, GrantType resourceType, List<String> namespace,
                              String objectName, Privilege privilege) {
    }

    private CatalogAccessRules() {
    }

    /**
     * 判断路径是否显式豁免授权。
     *
     * <p>只有 {@code GET /v1/config}：它返回服务端能力清单，客户端在拿到认证凭据前
     * 就要读取，且不含任何租户数据。其余端点一律要求授权。
     */
    public static boolean isPublic(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        String path = stripTrailingSlash(requestUri);
        return "/v1/config".equals(path);
    }

    /**
     * 解析端点所需的权限。
     *
     * @param requestUri 请求路径（不含查询串）
     * @param method     HTTP 方法
     * @return 授权要求；路径不是 catalog API 或不匹配任何已登记端点时为 {@code null}
     */
    public static Requirement resolve(String requestUri, String method) {
        if (requestUri == null || !requestUri.startsWith(V1)) {
            return null;
        }
        String remainder = stripTrailingSlash(requestUri.substring(V1.length()));
        if (remainder.isEmpty()) {
            return null;
        }
        String[] raw = remainder.split("/");
        List<String> segments = new ArrayList<>();
        for (int index = 1; index < raw.length; index++) {
            if (!raw[index].isEmpty()) {
                segments.add(decode(raw[index]));
            }
        }
        if (segments.isEmpty()) {
            // /v1/{prefix} 自身不是端点
            return null;
        }
        return dispatch(decode(raw[0]), segments, method == null ? "" : method.toUpperCase(Locale.ROOT));
    }

    private static Requirement dispatch(String catalog, List<String> path, String method) {
        boolean read = isRead(method);
        String head = path.get(0);

        // ---------------------------------------------------------- 以 catalog 为作用域的全局端点
        if ("tables".equals(head) || "views".equals(head) || "functions".equals(head)) {
            return global(catalog, path, method, read);
        }
        if (!"databases".equals(head)) {
            return null;
        }

        // ---------------------------------------------------------- 命名空间自身
        if (path.size() == 1) {
            return read
                    ? at(catalog, GrantType.NAMESPACE, List.of(), null, Privilege.NAMESPACE_LIST)
                    : at(catalog, GrantType.NAMESPACE, List.of(), null, Privilege.NAMESPACE_CREATE);
        }
        String database = path.get(1);
        List<String> namespace = List.of(database);
        if (path.size() == 2) {
            if (read) {
                return at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_READ_PROPERTIES);
            }
            if ("DELETE".equals(method)) {
                return at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_DROP);
            }
            return at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_WRITE_PROPERTIES);
        }

        return switch (path.get(2)) {
            case "tables", "table-details", "register" -> tables(catalog, path, method, read);
            case "views", "view-details" -> views(catalog, path, method, read);
            case "semantic-views" -> semanticViews(catalog, path, method, read);
            default -> functions(catalog, path, method, read, namespace);
        };
    }

    /** {@code /v1/{prefix}/tables……} 这类不带命名空间的全局端点。 */
    private static Requirement global(String catalog, List<String> path, String method, boolean read) {
        String head = path.get(0);
        if (head.equals("functions")) {
            // 没有 function 层级的权限取值，按命名空间粒度要求
            return path.size() == 1 && read
                    ? at(catalog, GrantType.CATALOG, List.of(), null, Privilege.NAMESPACE_LIST)
                    : null;
        }
        if (path.size() == 1) {
            if (!read) {
                return null;
            }
            return head.equals("tables")
                    ? at(catalog, GrantType.CATALOG, List.of(), null, Privilege.TABLE_LIST)
                    : at(catalog, GrantType.CATALOG, List.of(), null, Privilege.VIEW_LIST);
        }
        if (path.size() == 2 && "rename".equals(path.get(1))) {
            return head.equals("tables")
                    ? at(catalog, GrantType.CATALOG, List.of(), null, Privilege.TABLE_WRITE_PROPERTIES)
                    : at(catalog, GrantType.CATALOG, List.of(), null, Privilege.VIEW_WRITE_PROPERTIES);
        }
        if (path.size() == 3 && "tables".equals(head) && "id".equals(path.get(1)) && read) {
            // 按 id 查表跨命名空间，因此只认 catalog 级授权
            return at(catalog, GrantType.TABLE, List.of(), null, Privilege.TABLE_READ_PROPERTIES);
        }
        return null;
    }

    /** {@code /v1/{prefix}/databases/{database}/tables……}，含 partitions / branches / tags / consumers 等子资源。 */
    private static Requirement tables(String catalog, List<String> path, String method, boolean read) {
        String database = path.get(1);
        List<String> namespace = List.of(database);
        String family = path.get(2);

        if (path.size() == 3) {
            if (family.equals("table-details")) {
                return read ? at(catalog, GrantType.TABLE, namespace, null, Privilege.TABLE_LIST) : null;
            }
            if (family.equals("register")) {
                return read ? null : at(catalog, GrantType.TABLE, namespace, null, Privilege.TABLE_CREATE);
            }
            return read
                    ? at(catalog, GrantType.TABLE, namespace, null, Privilege.TABLE_LIST)
                    : at(catalog, GrantType.TABLE, namespace, null, Privilege.TABLE_CREATE);
        }

        String table = path.get(3);
        if (path.size() == 4) {
            if (read) {
                return at(catalog, GrantType.TABLE, namespace, table, Privilege.TABLE_READ_PROPERTIES);
            }
            if ("DELETE".equals(method)) {
                return at(catalog, GrantType.TABLE, namespace, table, Privilege.TABLE_DROP);
            }
            return at(catalog, GrantType.TABLE, namespace, table, Privilege.TABLE_WRITE_PROPERTIES);
        }

        // 表以下还有一层子资源，语义仍以该表为对象
        String sub = path.get(4);
        Privilege required = switch (sub) {
            case "commit", "rollback" -> Privilege.TABLE_WRITE_DATA;
            case "rollback-schema" -> Privilege.TABLE_WRITE_PROPERTIES;
            case "token", "auth" -> Privilege.TABLE_READ_DATA;
            case "partitions" -> partitionPrivilege(path.size() > 5 ? path.get(5) : "", read);
            case "consumers" -> Privilege.TABLE_WRITE_DATA;
            default -> null; // snapshot / snapshots / branches / tags
        };
        if (required == null) {
            if (sub.equals("branches") || sub.equals("tags") || sub.equals("snapshot") || sub.equals("snapshots")) {
                required = read ? Privilege.TABLE_READ_PROPERTIES : Privilege.TABLE_WRITE_PROPERTIES;
            } else {
                return null;
            }
        }
        return at(catalog, GrantType.TABLE, namespace, table, required);
    }

    /** 分区操作：读挂在 {@code TABLE_READ_DATA} 上，写挂在 {@code TABLE_WRITE_DATA} 上。 */
    private static Privilege partitionPrivilege(String operation, boolean read) {
        if (operation.startsWith("list") || read) {
            return Privilege.TABLE_READ_DATA;
        }
        return Privilege.TABLE_WRITE_DATA;
    }

    /** {@code /v1/{prefix}/databases/{database}/views……}。 */
    private static Requirement views(String catalog, List<String> path, String method, boolean read) {
        List<String> namespace = List.of(path.get(1));
        String family = path.get(2);
        if (path.size() == 3) {
            if (family.equals("view-details")) {
                return read ? at(catalog, GrantType.VIEW, namespace, null, Privilege.VIEW_LIST) : null;
            }
            return read
                    ? at(catalog, GrantType.VIEW, namespace, null, Privilege.VIEW_LIST)
                    : at(catalog, GrantType.VIEW, namespace, null, Privilege.VIEW_CREATE);
        }
        String view = path.get(3);
        if (read) {
            return at(catalog, GrantType.VIEW, namespace, view, Privilege.VIEW_READ_PROPERTIES);
        }
        if ("DELETE".equals(method)) {
            return at(catalog, GrantType.VIEW, namespace, view, Privilege.VIEW_DROP);
        }
        return at(catalog, GrantType.VIEW, namespace, view, Privilege.VIEW_WRITE_PROPERTIES);
    }

    /** {@code /v1/{prefix}/databases/{database}/semantic-views……}，对应 {@code SEMANTIC_MODEL_*} 权限。 */
    private static Requirement semanticViews(String catalog, List<String> path, String method,
                                             boolean read) {
        List<String> namespace = List.of(path.get(1));
        if (path.size() == 3) {
            if (!read) {
                return at(catalog, GrantType.SEMANTIC_MODEL, namespace, null, Privilege.SEMANTIC_MODEL_CREATE);
            }
            return at(catalog, GrantType.SEMANTIC_MODEL, namespace, null, Privilege.SEMANTIC_MODEL_LIST);
        }
        String model = path.get(3);
        if (read) {
            return at(catalog, GrantType.SEMANTIC_MODEL, namespace, model, Privilege.SEMANTIC_MODEL_READ);
        }
        if ("DELETE".equals(method)) {
            return at(catalog, GrantType.SEMANTIC_MODEL, namespace, model, Privilege.SEMANTIC_MODEL_DROP);
        }
        return at(catalog, GrantType.SEMANTIC_MODEL, namespace, model, Privilege.SEMANTIC_MODEL_WRITE);
    }

    /**
     * function 端点。
     *
     * <p>管理规格的权限 enum 没有 function 层级，因此不虚构权限取值：
     * 列举与读取要求命名空间的读权限，创建与修改要求命名空间的写权限。
     */
    private static Requirement functions(String catalog, List<String> path, String method,
                                         boolean read, List<String> namespace) {
        if (!"functions".equals(path.get(2)) && !"function-details".equals(path.get(2))) {
            return null;
        }
        if (path.size() == 3) {
            if (path.get(2).equals("function-details")) {
                return read ? at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_LIST) : null;
            }
            return read
                    ? at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_LIST)
                    : at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_WRITE_PROPERTIES);
        }
        if (read) {
            return at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_READ_PROPERTIES);
        }
        return at(catalog, GrantType.NAMESPACE, namespace, null, Privilege.NAMESPACE_WRITE_PROPERTIES);
    }

    private static Requirement at(String catalog, GrantType resourceType, List<String> namespace,
                                  String objectName, Privilege privilege) {
        return new Requirement(catalog, resourceType, namespace, objectName, privilege);
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static String stripTrailingSlash(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 路径段可能含 URL 编码（表名、标签名允许出现特殊字符），比较前统一解码。 */
    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
