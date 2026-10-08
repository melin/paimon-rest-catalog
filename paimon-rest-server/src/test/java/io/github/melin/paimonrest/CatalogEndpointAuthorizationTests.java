package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.paimonrest.config.CatalogAccessRules;
import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.service.AuthorizationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * catalog API（{@code /v1/**}）的授权拦截行为测试。
 *
 * <p>与 {@link AuthorizationTests} 的分工：那一类验证 RBAC 判定本身（链路、蕴含、作用域覆盖），
 * 本类验证判定被真正接到了 catalog 端点上——即
 * {@link CatalogAccessRules} 的映射表与控制器实际暴露的端点集合一致。
 *
 * <p><b>端点清单不手写。</b>{@link #catalogEndpoints()} 直接从
 * {@link RequestMappingHandlerMapping} 读出运行时注册的全部路径与方法，
 * 因此新增端点若忘记在映射表登记，
 * {@link #everyCatalogEndpointPassesWithSufficientPrivileges()} 会立刻失败。
 * 这正是把授权做成集中映射表而非逐处注解的目的。
 *
 * <p>使用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 * {@code test} profile 负责把数据源从默认的 MySQL 换成内存 H2
 * （见 {@code src/test/resources/application-test.yml}），本类再覆盖库名以进一步隔离。
 */
@SpringBootTest(properties = {
        "paimon.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-catalog-authz;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogEndpointAuthorizationTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "paimon";
    private static final String ROOT = "root";
    private static final String READER = "cat-reader";
    private static final String WRITER = "cat-writer";
    private static final String SCOPED = "cat-scoped";

    /** 探针用的命名空间：并不存在，因此端点应返回 404 而不是 403。 */
    private static final String NAMESPACE_A = "authz_ns_a";
    private static final String NAMESPACE_B = "authz_ns_b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private AuthorizationService authorizationService;

    // ------------------------------------------------------------------ 工具

    /** 一个端点：HTTP 方法与已把占位符替换成具体值的路径。 */
    private record Endpoint(String method, String path) {
    }

    /**
     * 从运行时映射表读出 {@code /v1/{prefix}/…} 下的全部端点。
     *
     * <p>{@code /v1/config} 被排除：它是显式豁免授权的端点，
     * 单独由 {@link #configEndpointStaysPublic()} 覆盖。
     */
    private List<Endpoint> catalogEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns.getPatternValues()) {
                if (!pattern.startsWith("/v1/{prefix}")) {
                    continue;
                }
                RequestMethod method = methods.isEmpty() ? RequestMethod.GET : methods.iterator().next();
                endpoints.add(new Endpoint(method.name(), concrete(pattern)));
            }
        }
        endpoints.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
        return endpoints;
    }

    private String concrete(String pattern) {
        return pattern.replace("{prefix}", CATALOG).replaceAll("\\{[^}]+}", "authz_probe");
    }

    private MvcResult perform(String principal, int expected, String method, String path, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = builder(method, path);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (principal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + principal);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, actual, "unexpected status for " + method + " " + path
                + " as " + principal + ", body=" + responseBody);
        return result;
    }

    /** 只取状态码，用于断言「不是 403」。 */
    private int status(String principal, String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = builder(method, path);
        if ("POST".equals(method) || "PUT".equals(method)) {
            request = request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        if (principal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + principal);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder builder(String method, String path) {
        return switch (method) {
            case "GET" -> get(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
    }

    /** 把「catalog role → principal role → principal」整条链路一次性接好。 */
    private void wireRoleChain(String catalogRole, List<String> grantBodies,
                               String principalRole, String principal) throws Exception {
        perform(ROOT, 201, "POST", M + "/catalogs/" + CATALOG + "/catalog-roles",
                "{\"catalogRole\":{\"name\":\"" + catalogRole + "\"}}");
        for (String grant : grantBodies) {
            perform(ROOT, 201, "PUT",
                    M + "/catalogs/" + CATALOG + "/catalog-roles/" + catalogRole + "/grants", grant);
        }
        perform(ROOT, 201, "POST", M + "/principal-roles",
                "{\"principalRole\":{\"name\":\"" + principalRole + "\"}}");
        perform(ROOT, 201, "PUT",
                M + "/principal-roles/" + principalRole + "/catalog-roles/" + CATALOG,
                "{\"catalogRole\":{\"name\":\"" + catalogRole + "\"}}");
        perform(ROOT, 201, "PUT", M + "/principals/" + principal + "/principal-roles",
                "{\"principalRole\":{\"name\":\"" + principalRole + "\"}}");
    }

    private void createPrincipal(String name) throws Exception {
        perform(ROOT, 201, "POST", M + "/principals", "{\"principal\":{\"name\":\"" + name + "\"}}");
    }

    private String catalogGrant(String privilege) {
        return "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"" + privilege + "\"}}";
    }

    private String namespaceGrant(String privilege, String namespace) {
        return "{\"grant\":{\"type\":\"namespace\",\"namespace\":[\"" + namespace
                + "\"],\"privilege\":\"" + privilege + "\"}}";
    }

    // ------------------------------------------------------------------ 映射表完整性

    @Test
    void everyCatalogEndpointPassesWithSufficientPrivileges() throws Exception {
        createPrincipal(WRITER);
        // CATALOG_MANAGE_CONTENT 按文档展开后覆盖 catalog / namespace / table / view 四层，
        // 但不含 SEMANTIC_MODEL_*，因此语义模型端点需要单独授予
        wireRoleChain("authz_catalog_writer", List.of(
                catalogGrant("CATALOG_MANAGE_CONTENT"),
                catalogGrant("SEMANTIC_MODEL_FULL_METADATA")),
                "authz_writer_pr", WRITER);

        List<Endpoint> endpoints = catalogEndpoints();
        assertTrue(endpoints.size() >= 55,
                "catalog API 的端点数量明显偏少，可能是枚举逻辑失效了：" + endpoints.size());

        List<String> rejected = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (status(WRITER, endpoint.method(), endpoint.path()) == 403) {
                rejected.add(endpoint.method() + " " + endpoint.path());
            }
        }
        assertTrue(rejected.isEmpty(),
                "以下端点被 403 拒绝，说明映射表缺失或权限要求过严：" + rejected);

        // 映射表登记的端点数量应与运行时端点数量一致
        long mapped = endpoints.stream()
                .filter(endpoint -> CatalogAccessRules.resolve(endpoint.path(), endpoint.method()) != null)
                .count();
        assertEquals(endpoints.size(), mapped,
                "存在未在 CatalogAccessRules 登记的 catalog 端点（失败关闭会在运行时报 403）");
    }

    @Test
    void configEndpointStaysPublic() throws Exception {
        assertTrue(CatalogAccessRules.isPublic("/v1/config"));
        perform(null, 200, "GET", "/v1/config", null);
    }

    // ------------------------------------------------------------------ 拒绝未授权调用

    @Test
    void unprivilegedCallersAreRejectedOnCatalogEndpoints() throws Exception {
        // 匿名
        perform(null, 403, "GET", "/v1/" + CATALOG + "/databases", null);
        // 已登记主体但没有任何授权
        createPrincipal("cat-nobody");
        assertEquals(403, status("cat-nobody", "GET", "/v1/" + CATALOG + "/databases"));
        // 未登记主体同样被拒
        assertEquals(403, status("cat-ghost", "GET", "/v1/" + CATALOG + "/tables"));
        assertEquals(403, status("cat-ghost", "POST", "/v1/" + CATALOG + "/databases"));
    }

    @Test
    void readPrivilegesDoNotAllowWrites() throws Exception {
        createPrincipal(READER);
        wireRoleChain("authz_catalog_reader", List.of(
                catalogGrant("NAMESPACE_LIST"),
                catalogGrant("TABLE_LIST"),
                catalogGrant("TABLE_READ_PROPERTIES")),
                "authz_reader_pr", READER);

        // 允许：列举命名空间
        perform(READER, 200, "GET", "/v1/" + CATALOG + "/databases", null);
        // 允许：列举表（命名空间不存在，因此 404 而非 403）
        assertEquals(404, status(READER, "GET", "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables"));

        // 拒绝：创建命名空间（需要 NAMESPACE_CREATE）
        assertEquals(403, status(READER, "POST", "/v1/" + CATALOG + "/databases"));
        // 拒绝：创建表（需要 TABLE_CREATE）
        assertEquals(403, status(READER, "POST",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables"));
        // 拒绝：读取数据（TABLE_READ_DATA 按文档需单独授予，不被 FULL_METADATA 蕴含）
        assertEquals(403, status(READER, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables/authz_probe/token"));
        // 拒绝：语义模型（未授予 SEMANTIC_MODEL_*）
        assertEquals(403, status(READER, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/semantic-views/authz_probe"));
    }

    @Test
    void namespaceScopedGrantOnlyCoversItsOwnSubtree() throws Exception {
        createPrincipal(SCOPED);
        wireRoleChain("authz_ns_scoped", List.of(
                namespaceGrant("TABLE_FULL_METADATA", NAMESPACE_A)),
                "authz_scoped_pr", SCOPED);

        // 被授权命名空间内：通过授权检查，继而因表不存在返回 404
        int insideStatus = status(SCOPED, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables/authz_probe");
        assertNotEquals(403, insideStatus, "命名空间内的读表不应被拒绝");
        assertEquals(404, insideStatus, "表不存在应返回 404");

        // 另一个命名空间：拒绝
        assertEquals(403, status(SCOPED, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_B + "/tables/authz_probe"));

        // 服务层判定与之一致
        assertTrue(authorizationService.effectivePrivileges(SCOPED, CATALOG, GrantType.TABLE,
                List.of(NAMESPACE_A), "authz_probe").contains(Privilege.TABLE_READ_PROPERTIES));
        assertTrue(authorizationService.effectivePrivileges(SCOPED, CATALOG, GrantType.TABLE,
                List.of(NAMESPACE_B), "authz_probe").isEmpty(),
                "前缀不匹配的命名空间不应被覆盖");
    }

    // ------------------------------------------------------------------ 映射表自身的单元校验

    @Test
    void mappingTableResolvesRepresentativeEndpoints() {
        assertEquals(GrantType.CATALOG,
                CatalogAccessRules.resolve("/v1/paimon/tables", "GET").resourceType());
        assertEquals(Privilege.TABLE_LIST,
                CatalogAccessRules.resolve("/v1/paimon/tables", "GET").privilege());

        CatalogAccessRules.Requirement readTable =
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t", "GET");
        assertEquals(GrantType.TABLE, readTable.resourceType());
        assertEquals(List.of("db"), readTable.namespace());
        assertEquals("t", readTable.objectName());
        assertEquals(Privilege.TABLE_READ_PROPERTIES, readTable.privilege());

        // 删除表需要 TABLE_DROP，修改需要 TABLE_WRITE_PROPERTIES
        assertEquals(Privilege.TABLE_DROP,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t", "DELETE").privilege());
        assertEquals(Privilege.TABLE_WRITE_PROPERTIES,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t", "POST").privilege());

        // commit 走数据写权限
        assertEquals(Privilege.TABLE_WRITE_DATA,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t/commit", "POST").privilege());
        // token 走数据读权限
        assertEquals(Privilege.TABLE_READ_DATA,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t/token", "GET").privilege());
        // 分区列表是读，分区 drop 是写
        assertEquals(Privilege.TABLE_READ_DATA, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/partitions/list-by-names", "POST").privilege());
        assertEquals(Privilege.TABLE_WRITE_DATA, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/partitions/drop", "POST").privilege());
        // 分支与标签仍是表级元数据
        assertEquals(Privilege.TABLE_READ_PROPERTIES, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/branches", "GET").privilege());
        assertEquals(Privilege.TABLE_WRITE_PROPERTIES, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/tags/old", "DELETE").privilege());
        // 语义模型
        assertEquals(Privilege.SEMANTIC_MODEL_READ, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/semantic-views/sv", "GET").privilege());
        // function 没有对应权限层级，落在命名空间权限上
        assertEquals(GrantType.NAMESPACE, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/functions", "GET").resourceType());
        // 按 id 查表只认 catalog 级授权
        assertEquals(GrantType.TABLE, CatalogAccessRules
                .resolve("/v1/paimon/tables/id/12345", "GET").resourceType());

        // 非 catalog API 与豁免路径
        assertNull(CatalogAccessRules.resolve("/api/management/v1/catalogs", "GET"));
        assertNull(CatalogAccessRules.resolve("/v1/config", "GET"));
        assertNull(CatalogAccessRules.resolve("/polaris-management-service.yml", "GET"));
    }

    @Test
    void pathSegmentsAreUrlDecoded() {
        // 表名允许含需要编码的字符，解码后再与授权中的作用域比较
        CatalogAccessRules.Requirement requirement =
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/my%20table", "GET");
        assertEquals("my table", requirement.objectName());
    }

    @Test
    void unknownEndpointInsideCatalogApiIsNotSilentlyAllowed() {
        // 失败关闭：/v1 下未登记的路径不会被映射表静默放行
        assertNull(CatalogAccessRules.resolve("/v1/paimon/unknown-endpoint", "GET"));
        assertFalse(CatalogAccessRules.isPublic("/v1/paimon/databases"));
        assertFalse(CatalogAccessRules.isPublic("/v1/config/extra"));
        assertTrue(CatalogAccessRules.isPublic("/v1/config"));
        // 结尾斜杠不影响豁免判定
        assertTrue(CatalogAccessRules.isPublic("/v1/config/"));
    }
}
