package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 控制台的服务端支撑测试。
 *
 * <p>控制台本身是前端代码，不在这里测；本类只覆盖服务端为它承担的三件事：
 *
 * <ol>
 *   <li><b>元数据端点</b>（{@code /api/console/v1/meta}）。前端表单的取值都来自它，
 *       一旦枚举改名或权限分组漂移，表现为「页面能打开但提交后报错」，
 *       所以这里断言的是内容而不是状态码。
 *   <li><b>单页应用回退</b>。history 路由要求「非文件路径返回入口页」，
 *       而「带扩展名的缺失文件仍然 404」是这条规则的另一半——
 *       少了它，构建产物不同步会变成白屏但状态码 200。
 *   <li><b>已提交的构建产物自洽</b>。控制台的构建产物随源码一起进版本库
 *       （见 {@code paimon-rest-console/vite.config.js} 的说明），
 *       因此要有一条断言挡住「重新构建后只提交了 index.html、忘了 assets」这类半提交。
 * </ol>
 */
@SpringBootTest(properties = {
        // 默认配置下控制台要求先登录（console.required=true），因此 /api/console/v1/meta
        // 也要求令牌。本类测的是元数据与托管，不想在每条用例里走一遍登录：
        // 登记一个静态令牌，由 getJson 统一带上——顺带把「门禁开启时静态令牌可用」也覆盖了
        "paimon.rest.auth.tokens[0]=console-api-test-token"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConsoleApiTests {

    private static final String META = "/api/console/v1/meta";

    private static final String AUTH = "/api/console/v1/auth";

    private static final String TOKEN = "console-api-test-token";

    private static final String CONSOLE_ENTRY = "static/console/index.html";

    /** 入口页里引用的、以控制台基址开头的静态资源。 */
    private static final Pattern CONSOLE_ASSET = Pattern.compile("(?:src|href)=\"(/console/[^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode getJson(String path, int expected) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body);
    }

    // ------------------------------------------------------------------ 元数据

    @Test
    void metaExposesEnumsUsedByConsoleForms() throws Exception {
        JsonNode body = getJson(META, 200);
        JsonNode service = body.get("service");
        JsonNode enums = body.get("enums");
        assertNotNull(service, "service block is missing");
        assertNotNull(enums, "enums block is missing");

        assertEquals("paimon-rest-server", service.get("name").asString());
        assertEquals("paimon", service.get("defaultPrefix").asString());
        assertFalse(service.get("authEnabled").asBoolean(), "auth is off in the test profile");
        assertEquals("default", service.get("credentialManagerType").asString());
        assertEquals("default", service.get("fileIoType").asString());
        assertTrue(service.get("maxPageSize").asInt() > 0);

        assertEquals(List.of("INTERNAL", "EXTERNAL"), strings(enums.get("catalogTypes")));
        // 两个扩展存储类型必须在取值集合里：控制台的存储类型下拉框据此渲染
        assertEquals(Set.of("S3", "GCS", "AZURE", "OBS", "OSS", "FILE"),
                new LinkedHashSet<>(strings(enums.get("storageTypes"))));

        // file-io.type=default 时六种存储全部可用，FILE 恒在其中
        assertEquals(Set.of("S3", "GCS", "AZURE", "OBS", "OSS", "FILE"),
                new LinkedHashSet<>(strings(enums.get("supportedStorageTypes"))));

        assertTrue(strings(enums.get("credentialManagerTypes")).containsAll(List.of("default", "noop")));
        assertTrue(strings(enums.get("fileIoTypes")).containsAll(List.of("default", "local", "obs", "oss")));

        // 资源类型的判别值来自管理规格的 discriminator mapping，顺序与规格 definition 一致
        assertEquals(
                List.of("catalog", "namespace", "table", "view", "policy", "semantic-model"),
                strings(enums.get("grantTypes")));
    }

    /**
     * 权限分组必须与服务端 {@code Privilege.allowedFor} 还原出的规格约束一致。
     *
     * <p>抽查两组边界：{@code view} 层级不含命名空间类权限，{@code catalog} 层级含
     * {@code CATALOG_MANAGE_ACCESS}。若控制台拿到的分组与判定用的分组不同源，
     * 用户会看到能选、但提交必被拒的权限项。
     */
    @Test
    void metaPrivilegesFollowManagementSpec() throws Exception {
        JsonNode privileges = getJson(META, 200).get("enums").get("privilegesByGrantType");
        assertNotNull(privileges);

        JsonNode catalog = privileges.get("catalog");
        JsonNode view = privileges.get("view");
        assertNotNull(catalog, "catalog group is missing");
        assertNotNull(view, "view group is missing");

        List<String> catalogPrivileges = strings(catalog);
        List<String> viewPrivileges = strings(view);

        assertTrue(catalogPrivileges.contains("CATALOG_MANAGE_ACCESS"));
        assertTrue(catalogPrivileges.contains("TABLE_MANAGE_STRUCTURE"));
        assertTrue(viewPrivileges.contains("VIEW_READ_PROPERTIES"));
        assertFalse(viewPrivileges.contains("NAMESPACE_CREATE"),
                "view 层级不该出现命名空间权限");
        assertFalse(viewPrivileges.contains("TABLE_CREATE"),
                "view 层级不该出现表权限");
    }

    // ------------------------------------------------------------------ 登录引导

    /**
     * 默认配置下的登录引导：控制台要求登录，但数据面仍然开放。
     *
     * <p>这两个字段必须分开报告，而且必须同时如实：{@code authEnabled=false} 说明
     * {@code /v1/**} 与管理 API 仍然是匿名可调的，{@code consoleRequired=true} 说明
     * 控制台自己会拦在登录页上。控制台据此在登录页写一句「这层门禁只挡住界面」——
     * 少了任何一个字段，那句话要么写不出来，要么会写成「本服务端已受保护」。
     *
     * <p>{@code methods} 必须非空：门禁开着时要能告诉用户怎么进来。
     */
    @Test
    void authAsksForALoginEvenWhenTheDataPlaneIsOpen() throws Exception {
        // 这条必须匿名请求：getJson 会带上令牌，而「未登录时 session 是什么样」
        // 正是本用例要断言的一半内容
        MvcResult result = mockMvc.perform(get(AUTH)).andReturn();
        JsonNode body = mapper.readTree(body(result));
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);

        assertFalse(body.get("authEnabled").asBoolean(), "测试 profile 下 paimon.rest.auth.enabled=false");
        assertTrue(body.get("consoleRequired").asBoolean(), "控制台默认要求登录");
        assertEquals(List.of("password", "client-credentials", "static-token"), strings(body.get("methods")),
                "用户名密码排在第一个：它不需要先建主体");
        assertFalse(body.get("session").get("authenticated").asBoolean());
        assertNull(body.get("session").get("principal"));
        assertNull(body.get("oidc"), "未启用 OIDC 时不该下发客户端参数");
    }

    /**
     * 门禁模式下带着令牌访问登录引导，要能认出「你是谁」。
     *
     * <p>与上一条互为对照：同一个端点，带没带令牌的差别只体现在 {@code session} 上，
     * 而不是整个请求被 401 挡掉——登录页需要能读到「支持哪些登录方式」，
     * 即使手里拿着的是个过期令牌。
     */
    @Test
    void authReportsTheSessionWhenATokenIsPresent() throws Exception {
        JsonNode session = getJson(AUTH, 200).get("session");

        assertTrue(session.get("authenticated").asBoolean());
        // 本类的令牌没有配 token-principals 映射，因此主体名就是令牌本身
        assertEquals(TOKEN, session.get("principal").asString());
        assertEquals("static-token", session.get("source").asString());
    }

    /**
     * 门禁开着时，控制台自己的端点要求令牌。
     *
     * <p>这是「只让控制台要求登录」这层门禁在服务端的落点：控制台界面被拦在登录页，
     * 它的数据端点也不接受匿名请求。**注意范围**——只有 {@code /api/console/v1/**}
     * 这一段，catalog API 与管理 API 仍按 {@code paimon.rest.auth.enabled} 走。
     */
    @Test
    void consoleEndpointsRejectAnonymousRequestsWhileTheGateIsOn() throws Exception {
        assertEquals(401, mockMvc.perform(get(META)).andReturn().getResponse().getStatus(),
                "门禁开着时匿名请求不该读到控制台元数据");
        assertNotNull(getJson(META, 200), "带令牌仍应可读");

        // 数据面不受门禁影响：这是默认配置下刻意保留的行为，写成断言免得被无意改掉
        assertEquals(200, mockMvc.perform(get("/v1/config?warehouse=paimon")).andReturn()
                .getResponse().getStatus(), "console.required 不该让 /v1/** 也要求令牌");
    }

    /**
     * 令牌端点的地址由服务端下发，且与 Polaris 一致。
     *
     * <p>控制台是构建期打包的静态资源，读不到服务端配置，因此这个地址只能运行时下发。
     * 断言它的取值是为了守住与 Polaris 的对齐：按 OAuth 2.0 写的客户端与
     * Polaris 官方 console 的默认 {@code VITE_OAUTH_TOKEN_URL} 都是这个路径。
     */
    @Test
    void authAdvertisesThePolarisCompatibleTokenEndpoint() throws Exception {
        assertEquals("/api/catalog/v1/oauth/tokens", getJson(AUTH, 200).get("tokenEndpoint").asString());
    }

    // ------------------------------------------------------------------ 单页应用回退

    @Test
    void consoleRootRedirectsToTrailingSlash() throws Exception {
        MvcResult result = mockMvc.perform(get("/console")).andReturn();
        assertEquals(302, result.getResponse().getStatus());
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.endsWith("/console/"), () -> "unexpected Location: " + location);
    }

    /**
     * {@code /console/} 转发到入口页。
     *
     * <p>断言的是转发目标而不是响应体：目录式路径到不了资源解析器
     * （见 {@code ConsoleWebConfig} 的说明），这里要守住的就是「这条转发还在」。
     * 入口页本身能否取到，由 {@link #consoleEntryIsServedAtItsRealUrl} 覆盖。
     */
    @Test
    void consoleDirectoryUrlForwardsToEntryPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/console/")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("/console/index.html", result.getResponse().getForwardedUrl());
    }

    @Test
    void consoleEntryIsServedAtItsRealUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/console/index.html")).andReturn();
        String body = body(result);
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "响应没有 Content-Type");
        assertTrue(contentType.startsWith("text/html"),
                () -> "入口页应当是 HTML，实际为 " + contentType);
        assertTrue(body.contains("id=\"app\""), "入口页应当是控制台的挂载点");
    }

    /**
     * 前端路由的深链接与多级路径都要回退到入口页。
     *
     * <p>五条路径分别覆盖：一级路由（{@code /catalogs}）、带查询串的路由、
     * 表详情这种三段式路由（{@code /tables/{catalog}/{database}/{table}}）、
     * OIDC 回调（{@code /auth/callback}）——最后这条是外部 IdP 重定向回来的落点，
     * 它回退失败意味着 SSO 登录在这台服务端上永远走不完；
     * 以及登录页本身。
     */
    @Test
    void clientSideRoutesFallBackToEntryPage() throws Exception {
        String entry = readClasspathText(CONSOLE_ENTRY);
        for (String path : List.of(
                "/console/catalogs",
                "/console/browse?catalog=paimon",
                "/console/tables/paimon/default/orders",
                "/console/principal-roles",
                "/console/catalog-roles",
                "/console/login",
                "/console/auth/callback")) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            String body = body(result);
            assertEquals(200, result.getResponse().getStatus(), () -> path + " body=" + body);
            assertEquals(entry, body, () -> path + " 应当返回入口页原文");
        }
    }

    /**
     * 缺失的静态文件仍然 404。
     *
     * <p>这是回退规则的另一半：构建产物更新后浏览器仍持有旧入口页的情况很常见，
     * 若把缺失的 JS 也回退成 HTML，症状会是「白屏但一切 200」，只能靠翻控制台发现。
     */
    @Test
    void missingAssetStillReturns404() throws Exception {
        MvcResult result = mockMvc
                .perform(get("/console/assets/this-file-does-not-exist-000000.js"))
                .andReturn();
        assertEquals(404, result.getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 构建产物

    /**
     * 已提交的入口页里引用的每个资源都真实存在。
     *
     * <p>构建产物的文件名带内容哈希，重新构建会同时换掉入口页与 assets。
     * 只提交其中一半时页面会 404（或更糟：白屏），而 git 状态看起来是「正常的一次改动」。
     */
    @Test
    void committedConsoleHtmlReferencesExistingAssets() throws Exception {
        String html = readClasspathText(CONSOLE_ENTRY);
        Set<String> assets = new LinkedHashSet<>();
        Matcher matcher = CONSOLE_ASSET.matcher(html);
        while (matcher.find()) {
            assets.add(matcher.group(1));
        }
        assertFalse(assets.isEmpty(), "入口页没有引用任何 /console/ 下的资源，构建产物不完整");

        List<String> missing = new ArrayList<>();
        for (String asset : assets) {
            String relativePath = asset.substring("/console/".length());
            if (!new ClassPathResource("static/console/" + relativePath).exists()) {
                missing.add(asset);
            }
        }
        assertTrue(missing.isEmpty(), () -> "入口页引用了不存在的资源：" + missing);
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> strings(JsonNode array) {
        assertNotNull(array, "expected a JSON array");
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    /**
     * 按 UTF-8 读响应体。
     *
     * <p>静态资源的响应头只有 {@code text/html} 而没有 charset，
     * {@code getContentAsString()} 会退回 ISO-8859-1，中文注释变成乱码，
     * 与期望内容逐字比较时必然失败——而这只说明读取方式不对，不是页面有问题。
     */
    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String readClasspathText(String path) throws IOException {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
