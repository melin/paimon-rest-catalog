package io.github.melin.paimonrest.spark.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Paimon Rest Catalog Management API 的客户端。
 *
 * <p>只依赖 JDK 自带的 {@link HttpClient} 与 Spark 自带的 Jackson 2，不引入额外 HTTP 库：
 * 这个模块最终会被放进 Spark 的 classpath，额外依赖会与集群已有版本冲突。
 *
 * <p><b>路径与规格一致。</b>端点、动词、请求体字段名都取自
 * {@code spec/polaris-management-service.yml}，由服务端同一份规格实现，
 * 因此这里不做任何形状转换，避免两侧各自演化。
 *
 * <p><b>响应体有两套形状，极易读错。</b>规格对「单个资源」的响应**大多不套外壳**：
 *
 * <pre>
 * POST /principals                    -> PrincipalWithCredentials  {"principal":{…},"credentials":{…}}
 * POST /principals/{n}/reset|rotate   -> PrincipalWithCredentials  {"principal":{…},"credentials":{…}}
 * GET  /principals/{n}                -> Principal                 裸对象
 * PUT  /principals/{n}                -> Principal                 裸对象
 * POST /principal-roles               -> PrincipalRole             裸对象
 * GET  /principal-roles/{n}           -> PrincipalRole             裸对象
 * POST /catalogs/{c}/catalog-roles    -> CatalogRole               裸对象
 * GET  /catalogs/{c}/catalog-roles/{n}-> CatalogRole               裸对象
 * </pre>
 *
 * 只有主体相关的接口（创建 / 重置 / 轮换）把结果包在 {@code principal} + {@code credentials}
 * 之下，因为需要同时返回一次性明文密钥；其余单资源接口返回裸对象。列表接口则统一是
 * 具名数组：{@code {"catalogs":[…]}}、{@code {"principals":[…]}}、{@code {"roles":[…]}}、
 * {@code {"grants":[…]}}。
 *
 * <p>读错的后果不是报错而是**静默取到空值**：例如从 {@code GET /principals/{n}} 上多读一层
 * {@code principal}，拿到的 {@code entityVersion} 会变成 0，下一次 {@code PUT} 就会因为
 * 版本不符得到 409（{@code expected 0, actual 1}）。因此下面每个方法都标注了它依赖的
 * 响应形状。
 *
 * <p><b>ALTER 的读改写。</b>规格的 update 请求会把 {@code properties} 整体替换，
 * 而 SQL 的 {@code ALTER ... SET PROPERTIES} 语义是「增量设置、其余保留」。
 * 两者不一致，因此在 {@link #alterPrincipalProperties} 等方法里做一次
 * 「读取 → 合并 → 回写」，把替换语义包装成 SQL 用户预期的增量语义。
 *
 * <p>刻意不实现 {@link AutoCloseable}：{@code HttpClient#close} 到 Java 21 才出现，
 * 而本模块按 Java 17 编译；{@code HttpClient} 自身持有守护线程，无需显式关闭。
 */
public final class ManagementApiClient {

    private static final String CATALOGS = "/catalogs";
    private static final String PRINCIPALS = "/principals";
    private static final String PRINCIPAL_ROLES = "/principal-roles";
    private static final String CATALOG_ROLES = "/catalog-roles";
    private static final String GRANTS = "/grants";

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ManagementApiClient(String baseUrl, String token, Duration timeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.token = token == null || token.isBlank() ? null : token;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.mapper = new ObjectMapper();
    }

    // ------------------------------------------------------------------ catalog

    /** {@code GET /catalogs}。 */
    public List<CatalogInfo> listCatalogs() {
        JsonNode root = get(CATALOGS);
        List<CatalogInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("catalogs")) {
            result.add(new CatalogInfo(text(node, "name"), text(node, "type"),
                    properties(node), integer(node, "entityVersion")));
        }
        return result;
    }

    // ------------------------------------------------------------------ principal

    /** {@code GET /principals}。 */
    public List<PrincipalInfo> listPrincipals() {
        JsonNode root = get(PRINCIPALS);
        List<PrincipalInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("principals")) {
            result.add(principalInfo(node));
        }
        return result;
    }

    /** {@code GET /principals/{name}}：响应是裸 {@code Principal}。 */
    public PrincipalInfo getPrincipal(String name) {
        return principalInfo(get(PRINCIPALS + "/" + encode(name)));
    }

    /**
     * {@code POST /principals}。
     *
     * @return 含明文密钥的凭据；密钥只在此处出现一次，服务端不保存明文
     */
    public Credentials createPrincipal(String name, Map<String, String> properties) {
        ObjectNode principal = mapper.createObjectNode();
        principal.put("name", name);
        if (properties != null && !properties.isEmpty()) {
            principal.set("properties", mapper.valueToTree(properties));
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("principal", principal);
        JsonNode response = post(PRINCIPALS, body, 201);
        return credentials(response);
    }

    /**
     * {@code POST /principals/{name}/reset} 与 {@code /rotate}：重置或轮换凭据。
     *
     * <p>注意这两条是规格里少见的**成功返回 200 而非 201** 的 POST 端点
     * （它们的语义是「更新凭据」而不是「创建资源」），响应体与创建一样是
     * {@code PrincipalWithCredentials}。
     */
    public Credentials resetPrincipal(String name, boolean rotate) {
        ObjectNode body = mapper.createObjectNode();
        JsonNode response = post(PRINCIPALS + "/" + encode(name) + (rotate ? "/rotate" : "/reset"),
                body, 200);
        return credentials(response);
    }

    /**
     * {@code PUT /principals/{name}} 的增量包装：先读当前 {@code entityVersion} 与属性，
     * 合并后再回写。
     *
     * @param toSet   要设置或覆盖的属性
     * @param toUnset 要移除的属性名
     */
    public PrincipalInfo alterPrincipalProperties(String name, Map<String, String> toSet,
                                                   Set<String> toUnset) {
        // GET 与 PUT 的响应都是裸 Principal，不是 {"principal":{…}}
        JsonNode current = get(PRINCIPALS + "/" + encode(name));
        Map<String, String> merged = merge(properties(current), toSet, toUnset);
        ObjectNode body = mapper.createObjectNode();
        body.put("currentEntityVersion", current.path("entityVersion").asInt());
        body.set("properties", mapper.valueToTree(merged));
        return principalInfo(put(PRINCIPALS + "/" + encode(name), body));
    }

    /** {@code DELETE /principals/{name}}。 */
    public void dropPrincipal(String name) {
        delete(PRINCIPALS + "/" + encode(name));
    }

    /** {@code GET /principals/{name}/principal-roles}。 */
    public List<String> listPrincipalRolesOf(String principal) {
        JsonNode root = get(PRINCIPALS + "/" + encode(principal) + PRINCIPAL_ROLES);
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    /** {@code PUT /principals/{name}/principal-roles}：把 principal role 授予主体。 */
    public void grantPrincipalRole(String principal, String principalRole) {
        put(PRINCIPALS + "/" + encode(principal) + PRINCIPAL_ROLES,
                roleBody("principalRole", principalRole), 201);
    }

    /** {@code DELETE /principals/{name}/principal-roles/{roleName}}。 */
    public void revokePrincipalRole(String principal, String principalRole) {
        delete(PRINCIPALS + "/" + encode(principal) + PRINCIPAL_ROLES + "/" + encode(principalRole));
    }

    // ------------------------------------------------------------------ principal role

    /** {@code GET /principal-roles}。 */
    public List<RoleInfo> listPrincipalRoles() {
        JsonNode root = get(PRINCIPAL_ROLES);
        List<RoleInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(roleInfo(node));
        }
        return result;
    }

    /** {@code POST /principal-roles}：响应是裸 {@code PrincipalRole}。 */
    public RoleInfo createPrincipalRole(String name, Map<String, String> properties) {
        ObjectNode role = mapper.createObjectNode();
        role.put("name", name);
        if (properties != null && !properties.isEmpty()) {
            role.set("properties", mapper.valueToTree(properties));
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("principalRole", role);
        return roleInfo(post(PRINCIPAL_ROLES, body, 201));
    }

    /** {@code GET /principal-roles/{name}}：响应是裸 {@code PrincipalRole}。 */
    public RoleInfo getPrincipalRole(String name) {
        return roleInfo(get(PRINCIPAL_ROLES + "/" + encode(name)));
    }

    /** {@code PUT /principal-roles/{name}} 的增量包装。GET 与 PUT 的响应都是裸 {@code PrincipalRole}。 */
    public RoleInfo alterPrincipalRoleProperties(String name, Map<String, String> toSet,
                                                 Set<String> toUnset) {
        JsonNode role = get(PRINCIPAL_ROLES + "/" + encode(name));
        ObjectNode body = mapper.createObjectNode();
        body.put("currentEntityVersion", role.path("entityVersion").asInt());
        body.set("properties", mapper.valueToTree(
                merge(properties(role), toSet, toUnset)));
        return roleInfo(put(PRINCIPAL_ROLES + "/" + encode(name), body));
    }

    /** {@code DELETE /principal-roles/{name}}。 */
    public void dropPrincipalRole(String name) {
        delete(PRINCIPAL_ROLES + "/" + encode(name));
    }

    /** {@code GET /principal-roles/{name}/principals}。 */
    public List<String> listPrincipalsOf(String principalRole) {
        JsonNode root = get(PRINCIPAL_ROLES + "/" + encode(principalRole) + PRINCIPALS);
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("principals")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    // ------------------------------------------------------------------ catalog role

    /** {@code GET /catalogs/{catalog}/catalog-roles}。 */
    public List<RoleInfo> listCatalogRoles(String catalog) {
        JsonNode root = get(catalogPath(catalog) + CATALOG_ROLES);
        List<RoleInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(roleInfo(node));
        }
        return result;
    }

    /** {@code POST /catalogs/{catalog}/catalog-roles}：响应是裸 {@code CatalogRole}。 */
    public RoleInfo createCatalogRole(String catalog, String name, Map<String, String> properties) {
        ObjectNode role = mapper.createObjectNode();
        role.put("name", name);
        if (properties != null && !properties.isEmpty()) {
            role.set("properties", mapper.valueToTree(properties));
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("catalogRole", role);
        return roleInfo(post(catalogPath(catalog) + CATALOG_ROLES, body, 201));
    }

    /** {@code GET /catalogs/{catalog}/catalog-roles/{name}}：响应是裸 {@code CatalogRole}。 */
    public RoleInfo getCatalogRole(String catalog, String name) {
        return roleInfo(get(catalogRolePath(catalog, name)));
    }

    /** {@code PUT /catalogs/{catalog}/catalog-roles/{name}} 的增量包装。GET 与 PUT 的响应都是裸 {@code CatalogRole}。 */
    public RoleInfo alterCatalogRoleProperties(String catalog, String name,
                                               Map<String, String> toSet, Set<String> toUnset) {
        JsonNode role = get(catalogRolePath(catalog, name));
        ObjectNode body = mapper.createObjectNode();
        body.put("currentEntityVersion", role.path("entityVersion").asInt());
        body.set("properties", mapper.valueToTree(merge(properties(role), toSet, toUnset)));
        return roleInfo(put(catalogRolePath(catalog, name), body));
    }

    /** {@code DELETE /catalogs/{catalog}/catalog-roles/{name}}。 */
    public void dropCatalogRole(String catalog, String name) {
        delete(catalogRolePath(catalog, name));
    }

    /** {@code GET /principal-roles/{principalRole}/catalog-roles/{catalog}}。 */
    public List<String> listCatalogRolesOf(String principalRole, String catalog) {
        JsonNode root = get(PRINCIPAL_ROLES + "/" + encode(principalRole)
                + CATALOG_ROLES + "/" + encode(catalog));
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    /** {@code PUT /principal-roles/{principalRole}/catalog-roles/{catalog}}。 */
    public void grantCatalogRole(String principalRole, String catalog, String catalogRole) {
        put(PRINCIPAL_ROLES + "/" + encode(principalRole) + CATALOG_ROLES + "/" + encode(catalog),
                roleBody("catalogRole", catalogRole), 201);
    }

    /** {@code DELETE /principal-roles/{principalRole}/catalog-roles/{catalog}/{catalogRole}}。 */
    public void revokeCatalogRole(String principalRole, String catalog, String catalogRole) {
        delete(PRINCIPAL_ROLES + "/" + encode(principalRole) + CATALOG_ROLES + "/"
                + encode(catalog) + "/" + encode(catalogRole));
    }

    /** {@code GET /catalogs/{catalog}/catalog-roles/{name}/principal-roles}。 */
    public List<String> listPrincipalRolesOfCatalogRole(String catalog, String catalogRole) {
        JsonNode root = get(catalogRolePath(catalog, catalogRole) + PRINCIPAL_ROLES);
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    // ------------------------------------------------------------------ 资源授权

    /** {@code GET /catalogs/{catalog}/catalog-roles/{name}/grants}。 */
    public List<GrantInfo> listGrants(String catalog, String catalogRole) {
        JsonNode root = get(catalogRolePath(catalog, catalogRole) + GRANTS);
        List<GrantInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("grants")) {
            result.add(grantInfo(node));
        }
        return result;
    }

    /** {@code PUT /catalogs/{catalog}/catalog-roles/{name}/grants}：新增一条授权。 */
    public void addGrant(String catalog, String catalogRole, GrantInfo grant) {
        ObjectNode body = mapper.createObjectNode();
        body.set("grant", grantNode(grant));
        put(catalogRolePath(catalog, catalogRole) + GRANTS, body, 201);
    }

    /** {@code POST /catalogs/{catalog}/catalog-roles/{name}/grants}：撤销一条授权。 */
    public void revokeGrant(String catalog, String catalogRole, GrantInfo grant) {
        ObjectNode body = mapper.createObjectNode();
        body.set("grant", grantNode(grant));
        post(catalogRolePath(catalog, catalogRole) + GRANTS, body, 201);
    }

    // ------------------------------------------------------------------ 内部：HTTP

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), path, 200);
    }

    private JsonNode post(String path, JsonNode body, int expected) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                path, expected);
    }

    private JsonNode put(String path, JsonNode body) {
        return put(path, body, 200);
    }

    private JsonNode put(String path, JsonNode body, int expected) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                path, expected);
    }

    private JsonNode delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE(), path, 204);
    }

    private JsonNode send(HttpRequest.Builder builder, String path, int expected) {
        builder.timeout(timeout).header("Accept", "application/json");
        if (token != null) {
            // 未配置令牌时不发送 Authorization 头：空值会让服务端把空串当成主体名
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ManagementApiException(0, request.method(), path,
                    "cannot reach the management API at " + baseUrl + ": " + e.getMessage(),
                    null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementApiException(0, request.method(), path,
                    "interrupted while calling the management API", null, null);
        }
        int status = response.statusCode();
        String body = response.body();
        if (status != expected) {
            throw error(request.method(), path, status, body);
        }
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ManagementApiException(status, request.method(), path,
                    "management API returned a body that is not valid JSON: " + e.getMessage(),
                    null, null);
        }
    }

    /** 把服务端的 {@code ErrorResponse} 还原成异常；无法解析时退化为原始响应文本。 */
    private ManagementApiException error(String method, String path, int status, String body) {
        String message = body;
        String type = null;
        String name = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = mapper.readTree(body);
                if (node.hasNonNull("message")) {
                    message = node.get("message").asText();
                }
                if (node.hasNonNull("type")) {
                    type = node.get("type").asText();
                }
                if (node.hasNonNull("name")) {
                    name = node.get("name").asText();
                }
            } catch (IOException ignored) {
                // 保持原始文本作为消息
            }
        }
        return new ManagementApiException(status, method, path, message, type, name);
    }

    @Override
    public String toString() {
        return "ManagementApiClient(" + baseUrl + ")";
    }

    // ------------------------------------------------------------------ 内部：形状转换

    /*
     * 下面五个摘要类型刻意写成普通静态嵌套类，而不是 {@code record}。
     *
     * 它们会被 Scala 侧引用——{@code ManagementAstBuilder} / {@code ManagementCommands}
     * 按名字引用 {@code GrantInfo}，其余几个靠方法返回值推断后调用访问器。IDE 的
     * 混合编译会让 scalac 直接解析 Java 源码提取签名，而 Scala 2.12 的 Java 源码
     * 解析器不认识 {@code record} 关键字，于是这些类型在 IDE 里整体消失：
     * 报「not a member of object」，连带 lambda 报「missing parameter type」。
     * Maven 构建不受影响（POM 里 {@code sendJavaToScalac=false}，scalac 读的是
     * javac 产出的 class 文件），但 IDE 与 Maven 必须同时是绿的，因此只能写普通类。
     * 访问器沿用 record 的方法名（{@code name()} 而非 {@code getName()}），
     * 调用点因此一行不用改；equals/hashCode/toString 手写，保持 record 的值语义。
     */

    /** 主体摘要。{@code clientSecret} 不在此模型内，明文密钥只经 {@link Credentials} 返回一次。 */
    public static final class PrincipalInfo {

        private final String name;
        private final String clientId;
        private final Map<String, String> properties;
        private final Integer entityVersion;

        public PrincipalInfo(String name, String clientId, Map<String, String> properties,
                             Integer entityVersion) {
            this.name = name;
            this.clientId = clientId;
            this.properties = properties;
            this.entityVersion = entityVersion;
        }

        public String name() {
            return name;
        }

        public String clientId() {
            return clientId;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public Integer entityVersion() {
            return entityVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PrincipalInfo other
                    && Objects.equals(name, other.name)
                    && Objects.equals(clientId, other.clientId)
                    && Objects.equals(properties, other.properties)
                    && Objects.equals(entityVersion, other.entityVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, clientId, properties, entityVersion);
        }

        @Override
        public String toString() {
            return "PrincipalInfo[name=" + name + ", clientId=" + clientId
                    + ", properties=" + properties + ", entityVersion=" + entityVersion + "]";
        }
    }

    /** 角色摘要：principal role 与 catalog role 的公共形状。 */
    public static final class RoleInfo {

        private final String name;
        private final Map<String, String> properties;
        private final Boolean federated;
        private final Integer entityVersion;

        public RoleInfo(String name, Map<String, String> properties, Boolean federated,
                        Integer entityVersion) {
            this.name = name;
            this.properties = properties;
            this.federated = federated;
            this.entityVersion = entityVersion;
        }

        public String name() {
            return name;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public Boolean federated() {
            return federated;
        }

        public Integer entityVersion() {
            return entityVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RoleInfo other
                    && Objects.equals(name, other.name)
                    && Objects.equals(properties, other.properties)
                    && Objects.equals(federated, other.federated)
                    && Objects.equals(entityVersion, other.entityVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, properties, federated, entityVersion);
        }

        @Override
        public String toString() {
            return "RoleInfo[name=" + name + ", properties=" + properties
                    + ", federated=" + federated + ", entityVersion=" + entityVersion + "]";
        }
    }

    /** catalog 摘要。 */
    public static final class CatalogInfo {

        private final String name;
        private final String type;
        private final Map<String, String> properties;
        private final Integer entityVersion;

        public CatalogInfo(String name, String type, Map<String, String> properties,
                           Integer entityVersion) {
            this.name = name;
            this.type = type;
            this.properties = properties;
            this.entityVersion = entityVersion;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public Integer entityVersion() {
            return entityVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CatalogInfo other
                    && Objects.equals(name, other.name)
                    && Objects.equals(type, other.type)
                    && Objects.equals(properties, other.properties)
                    && Objects.equals(entityVersion, other.entityVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, properties, entityVersion);
        }

        @Override
        public String toString() {
            return "CatalogInfo[name=" + name + ", type=" + type
                    + ", properties=" + properties + ", entityVersion=" + entityVersion + "]";
        }
    }

    /** 创建 / 重置 / 轮换主体时一次性返回的凭据。 */
    public static final class Credentials {

        private final String name;
        private final String clientId;
        private final String clientSecret;

        public Credentials(String name, String clientId, String clientSecret) {
            this.name = name;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public String name() {
            return name;
        }

        public String clientId() {
            return clientId;
        }

        public String clientSecret() {
            return clientSecret;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Credentials other
                    && Objects.equals(name, other.name)
                    && Objects.equals(clientId, other.clientId)
                    && Objects.equals(clientSecret, other.clientSecret);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, clientId, clientSecret);
        }

        @Override
        public String toString() {
            // clientSecret 是一次性明文，不进日志与诊断输出
            return "Credentials[name=" + name + ", clientId=" + clientId + "]";
        }
    }

    /**
     * 一条资源授权。
     *
     * @param type      资源类型判别值：{@code catalog} / {@code namespace} / {@code table} /
     *                  {@code view} / {@code policy} / {@code semantic-model}
     * @param namespace 多级命名空间；catalog 类型为空列表
     * @param objectName 对象名；catalog 与 namespace 类型为 {@code null}
     * @param privilege 权限取值
     */
    public static final class GrantInfo {

        private final String type;
        private final List<String> namespace;
        private final String objectName;
        private final String privilege;

        public GrantInfo(String type, List<String> namespace, String objectName,
                         String privilege) {
            this.type = type;
            this.namespace = namespace == null ? List.of() : namespace;
            this.objectName = objectName;
            this.privilege = privilege;
        }

        public String type() {
            return type;
        }

        public List<String> namespace() {
            return namespace;
        }

        public String objectName() {
            return objectName;
        }

        public String privilege() {
            return privilege;
        }

        /** catalog 级授权。 */
        public static GrantInfo onCatalog(String privilege) {
            return new GrantInfo("catalog", List.of(), null, privilege);
        }

        /** namespace 级授权。 */
        public static GrantInfo onNamespace(List<String> namespace, String privilege) {
            return new GrantInfo("namespace", List.copyOf(namespace), null, privilege);
        }

        /** 对象级授权：table / view / policy / semantic-model。 */
        public static GrantInfo onObject(String type, List<String> namespace, String objectName,
                                         String privilege) {
            return new GrantInfo(type, List.copyOf(namespace), objectName, privilege);
        }

        /** 规格中各资源类型对应的对象名字段名。 */
        private String objectFieldName() {
            return switch (type) {
                case "table" -> "tableName";
                case "view" -> "viewName";
                case "policy" -> "policyName";
                case "semantic-model" -> "semanticModelName";
                default -> null;
            };
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GrantInfo other
                    && Objects.equals(type, other.type)
                    && Objects.equals(namespace, other.namespace)
                    && Objects.equals(objectName, other.objectName)
                    && Objects.equals(privilege, other.privilege);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, namespace, objectName, privilege);
        }

        @Override
        public String toString() {
            return "GrantInfo[type=" + type + ", namespace=" + namespace
                    + ", objectName=" + objectName + ", privilege=" + privilege + "]";
        }
    }

    private ObjectNode grantNode(GrantInfo grant) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", grant.type());
        if (!grant.namespace().isEmpty()) {
            ArrayNode array = node.putArray("namespace");
            grant.namespace().forEach(array::add);
        }
        String field = grant.objectFieldName();
        if (field != null) {
            node.put(field, grant.objectName());
        }
        node.put("privilege", grant.privilege());
        return node;
    }

    private GrantInfo grantInfo(JsonNode node) {
        List<String> namespace = new ArrayList<>();
        for (JsonNode element : node.path("namespace")) {
            namespace.add(element.asText());
        }
        String type = text(node, "type");
        String objectName = null;
        if (type != null) {
            String field = switch (type) {
                case "table" -> "tableName";
                case "view" -> "viewName";
                case "policy" -> "policyName";
                case "semantic-model" -> "semanticModelName";
                default -> null;
            };
            if (field != null) {
                objectName = text(node, field);
            }
        }
        return new GrantInfo(type, namespace, objectName, text(node, "privilege"));
    }

    private PrincipalInfo principalInfo(JsonNode node) {
        return new PrincipalInfo(text(node, "name"), text(node, "clientId"), properties(node),
                integer(node, "entityVersion"));
    }

    private Credentials credentials(JsonNode node) {
        JsonNode credentials = node.path("credentials");
        return new Credentials(text(node.path("principal"), "name"), text(credentials, "clientId"),
                text(credentials, "clientSecret"));
    }

    private RoleInfo roleInfo(JsonNode node) {
        return new RoleInfo(text(node, "name"), properties(node), bool(node, "federated"),
                integer(node, "entityVersion"));
    }

    private ObjectNode roleBody(String field, String roleName) {
        ObjectNode role = mapper.createObjectNode();
        role.put("name", roleName);
        ObjectNode body = mapper.createObjectNode();
        body.set(field, role);
        return body;
    }

    private static Map<String, String> merge(Map<String, String> current,
                                             Map<String, String> toSet, Set<String> toUnset) {
        Map<String, String> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (toUnset != null) {
            toUnset.forEach(merged::remove);
        }
        if (toSet != null) {
            merged.putAll(toSet);
        }
        return merged;
    }

    private static Map<String, String> properties(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry -> result.put(entry.getKey(),
                    entry.getValue().isNull() ? null : entry.getValue().asText()));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    /** 新的空属性集合，供不需要属性的调用点使用。 */
    public static Set<String> noKeys() {
        return new LinkedHashSet<>();
    }

    private String catalogPath(String catalog) {
        return CATALOGS + "/" + encode(catalog);
    }

    private String catalogRolePath(String catalog, String catalogRole) {
        return catalogPath(catalog) + CATALOG_ROLES + "/" + encode(catalogRole);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /**
     * 路径段编码。
     *
     * <p>{@link URLEncoder} 是 form 编码，空格会变成 {@code +}，在路径里是错的；
     * 这里把它换回 {@code %20}。不使用 {@code URI} 的构造函数是因为它会二次编码。
     */
    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String url) {
        String result = url == null ? "" : url.trim();
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 供诊断输出：客户端指向的基址。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 便于测试断言：是否配置了令牌。 */
    public Optional<String> tokenOptional() {
        return Optional.ofNullable(token);
    }
}
