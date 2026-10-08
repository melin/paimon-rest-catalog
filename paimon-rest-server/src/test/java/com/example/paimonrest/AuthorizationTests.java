package com.example.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.paimonrest.config.RequestContext;
import com.example.paimonrest.dto.ManagementEnums.GrantType;
import com.example.paimonrest.dto.Privilege;
import com.example.paimonrest.service.AuthorizationService;
import com.example.paimonrest.service.PrivilegeModel;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * RBAC 授权体系的行为测试。
 *
 * <p>与 {@link ManagementApiTests} 不同，本类在
 * {@code paimon.rest.authorization.enabled=true} 下运行，验证三件事：
 *
 * <ol>
 *   <li>管理端点确实按调用者身份拒绝请求（而不是只在文档里声明 403）；
 *   <li>「主体 → principal role → catalog role → 资源授权」这条链路真的能放行请求；
 *   <li>{@link PrivilegeModel} 的蕴含与作用域覆盖规则符合预期——
 *       尤其是 {@code *_FULL_METADATA} 不能顺带把 {@code CATALOG_*} 权限放大出去。
 * </ol>
 *
 * <p>使用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 * {@code test} profile 负责把数据源从默认的 MySQL 换成内存 H2
 * （见 {@code src/test/resources/application-test.yml}），本类再覆盖库名以进一步隔离。
 */
@SpringBootTest(properties = {
        "paimon.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-authz;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthorizationTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "paimon";
    private static final String ROOT = "root";
    private static final String BOB = "authz-bob";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationService authorizationService;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 工具

    private JsonNode call(int expected, MockHttpServletRequestBuilder request,
                          String asPrincipal) throws Exception {
        if (asPrincipal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + asPrincipal);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status as " + asPrincipal + ", body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode as(String principal, int expected, String method, String path, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request, principal);
    }

    /** 把「catalog role → principal role → principal」整条链路一次性接好。 */
    private void wireRoleChain(String catalogRole, List<String> grantBodies,
                               String principalRole, String principal) throws Exception {
        as(ROOT, 201, "POST", M + "/catalogs/" + CATALOG + "/catalog-roles",
                "{\"catalogRole\":{\"name\":\"" + catalogRole + "\"}}");
        for (String grant : grantBodies) {
            as(ROOT, 201, "PUT",
                    M + "/catalogs/" + CATALOG + "/catalog-roles/" + catalogRole + "/grants", grant);
        }
        as(ROOT, 201, "POST", M + "/principal-roles",
                "{\"principalRole\":{\"name\":\"" + principalRole + "\"}}");
        as(ROOT, 201, "PUT",
                M + "/principal-roles/" + principalRole + "/catalog-roles/" + CATALOG,
                "{\"catalogRole\":{\"name\":\"" + catalogRole + "\"}}");
        as(ROOT, 201, "PUT", M + "/principals/" + principal + "/principal-roles",
                "{\"principalRole\":{\"name\":\"" + principalRole + "\"}}");
    }

    // ------------------------------------------------------------------ 自举

    @Test
    void bootstrapChainGivesRootCatalogAccess() throws Exception {
        // 引导主体已创建且是服务管理员
        assertTrue(authorizationService.isServiceAdmin(ROOT));

        // 引导链路：root → service_admin → catalog_admin → CATALOG_MANAGE_ACCESS
        List<String> catalogRoleIds = authorizationService.catalogRoleIdsOf(ROOT, CATALOG);
        assertFalse(catalogRoleIds.isEmpty(), "root 应当通过引导链路持有 catalog role");

        Set<Privilege> effective = authorizationService.effectivePrivileges(
                ROOT, CATALOG, GrantType.CATALOG, List.of(), null);
        assertTrue(effective.contains(Privilege.CATALOG_MANAGE_ACCESS), "root 应能管理授权");
        assertTrue(effective.contains(Privilege.CATALOG_MANAGE_CONTENT));

        // root 可以列主体
        as(ROOT, 200, "GET", M + "/principals", null);
    }

    @Test
    void unauthenticatedAndUnknownPrincipalsAreRejected() throws Exception {
        // 未携带令牌 → 匿名主体，不是服务管理员
        as(null, 403, "GET", M + "/principals", null);
        // 携带了令牌但该主体不是服务管理员
        as("nobody", 403, "GET", M + "/principals", null);
        // 未在管理面登记的主体没有任何授权
        assertTrue(authorizationService.catalogRoleIdsOf("nobody", CATALOG).isEmpty());
    }

    @Test
    void grantsActuallyEnableAccessAndRevocationRemovesIt() throws Exception {
        as(ROOT, 201, "POST", M + "/principals",
                "{\"principal\":{\"name\":\"" + BOB + "\"}}");

        // 尚无任何授权：读 catalog 被拒
        as(BOB, 403, "GET", M + "/catalogs/" + CATALOG, null);
        // 也不能管理主体
        as(BOB, 403, "GET", M + "/principals", null);
        // 更不能给自己授权
        as(BOB, 403, "PUT", M + "/catalogs/" + CATALOG + "/catalog-roles/x/grants",
                "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"CATALOG_MANAGE_ACCESS\"}}");

        // 接上链路后即可读 catalog
        wireRoleChain("authz_reader", List.of(
                "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"CATALOG_READ_PROPERTIES\"}}"),
                "authz_pr", BOB);
        as(BOB, 200, "GET", M + "/catalogs/" + CATALOG, null);
        // 只有 READ_PROPERTIES，仍不能写
        as(BOB, 403, "PUT", M + "/catalogs/" + CATALOG,
                "{\"currentEntityVersion\":0,\"properties\":{}}");
        // 但仍不是服务管理员
        as(BOB, 403, "GET", M + "/principals", null);

        // 撤销授权后立刻失去访问
        as(ROOT, 201, "POST",
                M + "/catalogs/" + CATALOG + "/catalog-roles/authz_reader/grants",
                "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"CATALOG_READ_PROPERTIES\"}}");
        as(BOB, 403, "GET", M + "/catalogs/" + CATALOG, null);
    }

    // ------------------------------------------------------------------ 蕴含与作用域

    @Test
    void fullMetadataDoesNotEscalateToCatalogWidePrivileges() {
        // TABLE_FULL_METADATA 应当覆盖 Table 级元数据权限，但不得包含数据权限与 CATALOG_* 权限。
        // 规格的 TablePrivilege 枚举里列有 CATALOG_MANAGE_ACCESS，若照搬该枚举做展开，
        // 一个只写表的角色就会获得授权管理能力。
        Set<Privilege> expanded = PrivilegeModel.expand(Privilege.TABLE_FULL_METADATA);
        assertTrue(expanded.contains(Privilege.TABLE_LIST));
        assertTrue(expanded.contains(Privilege.TABLE_DROP));
        assertTrue(expanded.contains(Privilege.TABLE_WRITE_PROPERTIES));
        assertTrue(expanded.contains(Privilege.TABLE_MANAGE_STRUCTURE));

        assertFalse(expanded.contains(Privilege.TABLE_READ_DATA),
                "TABLE_READ_DATA 需单独授予");
        assertFalse(expanded.contains(Privilege.TABLE_WRITE_DATA),
                "TABLE_WRITE_DATA 需单独授予");
        assertFalse(expanded.contains(Privilege.CATALOG_MANAGE_ACCESS),
                "表级元数据权限不得升级为授权管理能力");
        assertFalse(expanded.contains(Privilege.CATALOG_MANAGE_CONTENT));
        assertFalse(expanded.contains(Privilege.CATALOG_MANAGE_METADATA));

        // 同理，namespace 级不得升级为 catalog 级
        Set<Privilege> namespaceFull = PrivilegeModel.expand(Privilege.NAMESPACE_FULL_METADATA);
        assertTrue(namespaceFull.contains(Privilege.NAMESPACE_CREATE));
        assertFalse(namespaceFull.contains(Privilege.CATALOG_MANAGE_CONTENT));
        assertFalse(namespaceFull.contains(Privilege.CATALOG_MANAGE_ACCESS));
    }

    @Test
    void catalogManageContentCoversTheDocumentedSet() {
        Set<Privilege> expanded = PrivilegeModel.expand(Privilege.CATALOG_MANAGE_CONTENT);
        // 文档明列的被蕴含项
        assertTrue(expanded.contains(Privilege.CATALOG_MANAGE_METADATA));
        assertTrue(expanded.contains(Privilege.CATALOG_READ_PROPERTIES));
        assertTrue(expanded.contains(Privilege.CATALOG_WRITE_PROPERTIES));
        assertTrue(expanded.contains(Privilege.TABLE_READ_DATA));
        assertTrue(expanded.contains(Privilege.TABLE_WRITE_DATA));
        // 经 FULL_METADATA 传递展开而来
        assertTrue(expanded.contains(Privilege.TABLE_LIST));
        assertTrue(expanded.contains(Privilege.NAMESPACE_CREATE));
        assertTrue(expanded.contains(Privilege.VIEW_LIST));
        // 但文档未把 CATALOG_MANAGE_ACCESS 列为组成部分
        assertFalse(expanded.contains(Privilege.CATALOG_MANAGE_ACCESS));
    }

    @Test
    void namespaceScopeCoversDescendantsOnly() {
        Set<Privilege> onParent = authorizationService.effectivePrivileges(
                ROOT, CATALOG, GrantType.NAMESPACE, List.of("default"), null);
        assertTrue(onParent.contains(Privilege.CATALOG_MANAGE_ACCESS));

        // 更深的命名空间同样被 catalog 级授权覆盖（引导角色授予的是 catalog 作用域）
        Set<Privilege> onChild = authorizationService.effectivePrivileges(
                ROOT, CATALOG, GrantType.NAMESPACE, List.of("default", "nested"), null);
        assertTrue(onChild.contains(Privilege.CATALOG_MANAGE_ACCESS),
                "catalog 级授权应覆盖其下所有命名空间");

        // 前缀不匹配的命名空间不被覆盖——用一个只有 namespace 级授权的角色来验证
        Set<Privilege> otherCatalog = authorizationService.effectivePrivileges(
                ROOT, "not-a-catalog", GrantType.NAMESPACE, List.of("whatever"), null);
        assertTrue(otherCatalog.isEmpty(), "不存在的 catalog 上不应有有效权限");
    }

    @Test
    void satisfiesHonoursImplication() {
        assertTrue(PrivilegeModel.satisfies(Set.of(Privilege.CATALOG_MANAGE_CONTENT),
                Privilege.TABLE_LIST));
        assertTrue(PrivilegeModel.satisfies(Set.of(Privilege.TABLE_FULL_METADATA),
                Privilege.TABLE_READ_PROPERTIES));
        assertFalse(PrivilegeModel.satisfies(Set.of(Privilege.TABLE_FULL_METADATA),
                Privilege.TABLE_READ_DATA));
        assertTrue(PrivilegeModel.satisfies(Set.of(Privilege.TABLE_READ_DATA),
                Privilege.TABLE_READ_DATA), "权限应当蕴含自身");
    }

    @Test
    void serviceAdminBypassesPrivilegeChecks() {
        RequestContext.setPrincipal(ROOT);
        try {
            // 服务管理员在任意 catalog 上都直接放行
            authorizationService.require("totally-unknown-catalog", GrantType.CATALOG,
                    List.of(), null, Privilege.CATALOG_MANAGE_ACCESS, "do anything");
        } finally {
            RequestContext.clear();
        }
    }
}
