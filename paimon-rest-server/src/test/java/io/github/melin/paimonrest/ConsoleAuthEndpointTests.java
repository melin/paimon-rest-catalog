package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.paimonrest.domain.entity.PrincipalEntity;
import io.github.melin.paimonrest.domain.repo.PrincipalRepository;
import io.github.melin.paimonrest.service.LoginAttemptLimiter;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 控制台登录在 HTTP 层的端到端测试：令牌端点、认证链、限速。
 *
 * <p>为什么值得在 HTTP 层再测一遍（{@code AccessTokenService} 与
 * {@code OidcService} 已有各自己的单元测试）：<b>这一层才会暴露接线错误</b>——
 * 拦截器的排除列表写错了、路径写错了、响应字段名写成驼峰了、
 * 令牌端点自己反而被鉴权挡住了。这些都不会让任何单元测试失败，
 * 只会让浏览器里的登录按钮没反应。
 */
@SpringBootTest(properties = {
        "paimon.rest.auth.enabled=true",
        // 固定密钥：测试要能构造「另一个实例签发的令牌」这类输入
        "paimon.rest.auth.access-token.signing-key=dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=",
        "paimon.rest.auth.access-token.ttl=30m",
        // 静态令牌：给机器用的那条路径不能被这次改动波及
        "paimon.rest.auth.tokens[0]=static-token-for-tests",
        "paimon.rest.auth.token-principals.static-token-for-tests=static-machine-user",
        // 用户名密码：一个与主体同名的账号，一个映射到主体的账号
        "paimon.rest.auth.console.password.users.console-auth-test=let-me-in",
        "paimon.rest.auth.console.password.principals.console-auth-test=console-auth-test-principal",
        // 阈值调小，让「连续失败后被拒」能在几行内测到
        "paimon.rest.auth.console.client-credentials.rate-limit.max-failures=3",
        "paimon.rest.auth.console.client-credentials.rate-limit.window=1m",
        "paimon.rest.auth.console.password.rate-limit.max-failures=3",
        "paimon.rest.auth.console.password.rate-limit.window=1m"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConsoleAuthEndpointTests {

    private static final String AUTH = "/api/console/v1/auth";

    private static final String LOGIN = "/api/console/v1/login";

    private static final String TOKEN_ENDPOINT = "/api/catalog/v1/oauth/tokens";

    /** 受保护端点的代表：只过认证、不做细粒度授权，适合用来验证令牌是否被接受。 */
    private static final String PROTECTED = "/api/console/v1/meta";

    private static final String STATIC_TOKEN = "static-token-for-tests";

    private static final String PRINCIPAL_NAME = "console-auth-test-principal";

    private static final String CLIENT_ID = "console-auth-test-client";

    private static final String CLIENT_SECRET = "s3cret-value-for-tests";

    /** 配置里的映射账号：用户名与主体名不同名。 */
    private static final String MAPPED_USER = "console-auth-test";

    private static final String MAPPED_PASSWORD = "let-me-in";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrincipalRepository principalRepository;

    @Autowired
    private LoginAttemptLimiter limiter;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void createPrincipal() {
        // 限速器是单例且按「来源地址 + clientId」计数，用例之间必须隔开，
        // 否则前一个用例故意制造的失败会把后一个用例顶到阈值
        limiter.reset();
        if (principalRepository.findByClientId(CLIENT_ID).isEmpty()) {
            principalRepository.save(newPrincipal(PRINCIPAL_NAME, CLIENT_ID, CLIENT_SECRET));
        }
    }

    @AfterEach
    void removePrincipal() {
        principalRepository.findByClientId(CLIENT_ID).ifPresent(principalRepository::delete);
        limiter.reset();
    }

    // ------------------------------------------------------------------ 登录引导

    /**
     * 鉴权开启时，登录引导必须列出可用的登录方式。
     *
     * <p>控制台登录页据此渲染页签：少了这个列表，页面只会显示「无法读取服务端的登录要求」。
     */
    @Test
    void authListsTheAvailableLoginMethods() throws Exception {
        JsonNode body = json(mockMvc.perform(get(AUTH)).andReturn(), 200);

        assertTrue(body.get("authEnabled").asBoolean());
        assertTrue(body.get("consoleRequired").asBoolean());
        assertEquals(List.of("password", "client-credentials", "static-token"), strings(body.get("methods")),
                "用户名密码必须排在最前：它是唯一不需要先建主体的方式");
        assertFalse(body.get("session").get("authenticated").asBoolean());
        assertNull(body.get("session").get("principal"));
    }

    /** 登录引导与两个换取令牌的端点都在鉴权之外——它们被挡住就会形成死循环。 */
    @Test
    void loginBootstrapEndpointsAreReachableWithoutAToken() throws Exception {
        assertEquals(200, mockMvc.perform(get(AUTH)).andReturn().getResponse().getStatus());
        assertEquals(400, mockMvc.perform(post(TOKEN_ENDPOINT)).andReturn().getResponse().getStatus(),
                "无参数应当是 400（参数问题），而不是 401（说明它被鉴权拦住了）");
        assertEquals(400, mockMvc.perform(post(LOGIN)).andReturn().getResponse().getStatus(),
                "登录端点同理：空请求体是 400，不是 401");
    }

    /** 除这两条之外，控制台与 catalog 的端点都必须要求令牌。 */
    @Test
    void protectedEndpointsRejectAnonymousRequests() throws Exception {
        for (String path : List.of(
                "/api/console/v1/meta",
                "/api/management/v1/catalogs",
                "/v1/paimon/databases",
                "/v1/config?warehouse=paimon")) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            assertEquals(401, result.getResponse().getStatus(), () -> path + " 应当要求令牌");
        }
    }

    // ------------------------------------------------------------------ 用户名密码

    /**
     * 用户名 + 密码换到的令牌可以访问受保护端点，并按 {@code password.principals} 映射成主体名。
     *
     * <p>与客户端凭据那条一样，这条测的是闭环而不是签发：<b>「能换到令牌」与
     * 「这个令牌能被接受」之间的接线错误只有在这里才会暴露</b>。
     * 顺带守住映射——配置里 {@code console-auth-test} 映射到
     * {@code console-auth-test-principal}，授权判定按主体名查链路，
     * 映射丢了会让这个账号登录成功却什么都看不到。
     */
    @Test
    void passwordLoginIssuesATokenThatWorksOnProtectedEndpoints() throws Exception {
        JsonNode body = json(login(MAPPED_USER, MAPPED_PASSWORD), 200);

        String token = body.get("accessToken").asString();
        assertFalse(token.isBlank(), "令牌不能是空串——前端会把它当成「没登录」");
        assertEquals("Bearer", body.get("tokenType").asString());
        assertEquals(1800, body.get("expiresInSeconds").asLong(), "TTL 应当来自 access-token.ttl=30m");
        assertEquals(PRINCIPAL_NAME, body.get("principal").asString(),
                "principal 是映射后的主体名，不是用户名");

        MvcResult probe = mockMvc.perform(get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertEquals(200, probe.getResponse().getStatus(), () -> "令牌被拒：" + body(probe));

        JsonNode session = json(mockMvc.perform(get(AUTH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 200).get("session");
        assertTrue(session.get("authenticated").asBoolean());
        assertEquals(PRINCIPAL_NAME, session.get("principal").asString());
        assertEquals("console-access-token", session.get("source").asString(),
                "与客户端凭据签发的是同一种令牌，控制台不必区分来源");
    }

    /**
     * 开箱即用的默认账号 {@code admin/admin}。
     *
     * <p>这是控制台「装好就能进」的那条路，也是本次改动的默认值本身。
     * 把它写成断言而不是只在 {@code application.yml} 里留一行注释：
     * 默认值一旦被谁改掉，登录页会直接不可用，而那种故障很难从日志上看出来。
     */
    @Test
    void theDefaultAdminAccountWorksOutOfTheBox() throws Exception {
        JsonNode body = json(login("admin", "admin"), 200);

        assertFalse(body.get("accessToken").asString().isBlank());
        assertEquals("admin", body.get("principal").asString(), "没有配 principals 映射时主体名就是用户名");
    }

    /**
     * 「用户名不存在」与「密码不对」返回一字不差的 401。
     *
     * <p>区分它们会让攻击者先枚举出有效用户名，把爆破面从
     * 「两个都不知道」缩小成「只知道密码」。因此这里连措辞都要一致，
     * 且两种情况都只算一次失败（分属不同的计数键），不会互相顶到阈值。
     */
    @Test
    void unknownUsernameIsIndistinguishableFromAWrongPassword() throws Exception {
        MvcResult unknown = login("no-such-user", MAPPED_PASSWORD);
        MvcResult wrongPassword = login(MAPPED_USER, "not-the-password");

        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(401, wrongPassword.getResponse().getStatus());
        assertEquals(body(unknown), body(wrongPassword),
                "两种失败的报文必须一字不差，否则可以据此枚举用户名");
        assertTrue(body(wrongPassword).contains("invalid username or password"));
    }

    /** 连续失败到达阈值后被拒；且此后即使密码正确也被拒，否则猜中一次就能重置计数。 */
    @Test
    void tooManyPasswordFailuresAreRateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(401, login(MAPPED_USER, "wrong-" + i).getResponse().getStatus());
        }

        MvcResult blocked = login(MAPPED_USER, MAPPED_PASSWORD);
        assertEquals(429, blocked.getResponse().getStatus(),
                "用户名密码比客户端凭据更怕爆破：clientSecret 是随机值，而人定的密码可能只有几个字符");
        assertTrue(body(blocked).contains("too many failed login attempts"));
    }

    /** 成功的登录不计入失败计数。 */
    @Test
    void successfulPasswordLoginsDoNotCountTowardsTheLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertEquals(200, login(MAPPED_USER, MAPPED_PASSWORD).getResponse().getStatus(),
                    "第 " + (i + 1) + " 次正常登录被拒了");
        }
    }

    /**
     * 缺用户名或密码是 400，而不是 401。
     *
     * <p>这与「凭据不对」是两回事：客户端要采取的行动不同（补参数 vs 换个密码），
     * 混成 401 会让人以为自己的密码写错了。
     */
    @Test
    void incompletePasswordCredentialsAreRejectedAsBadRequest() throws Exception {
        assertEquals(400, login(null, MAPPED_PASSWORD).getResponse().getStatus());
        assertEquals(400, login(MAPPED_USER, null).getResponse().getStatus());
        assertEquals(400, login("  ", MAPPED_PASSWORD).getResponse().getStatus(),
                "只有空白的用户名等同于没填");
        assertEquals(400, mockMvc.perform(post(LOGIN)).andReturn().getResponse().getStatus());
    }

    /** 用户名前后的空白被规范化——手工粘贴时很容易带上。 */
    @Test
    void passwordLoginTrimsTheUsername() throws Exception {
        assertEquals(200, login("  " + MAPPED_USER + "  ", MAPPED_PASSWORD).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 令牌端点

    @Test
    void clientCredentialsReturnAnAccessToken() throws Exception {
        JsonNode body = json(tokenRequest(CLIENT_ID, CLIENT_SECRET, "client_credentials", null), 200);

        assertNotNull(body.get("access_token"));
        assertEquals("bearer", body.get("token_type").asString());
        assertEquals(1800, body.get("expires_in").asLong(), "TTL 应当来自 access-token.ttl=30m");
        assertEquals("PRINCIPAL_ROLE:ALL", body.get("scope").asString());
        assertFalse(body.get("access_token").asString().isBlank());
    }

    /**
     * 用换到的令牌访问受保护端点。
     *
     * <p>这是整条链路的闭环：登录页拿到令牌 → 存进浏览器 → 后续请求带上它。
     * 少了这一步，「令牌能签发」与「令牌能用」之间的接线错误不会被发现。
     */
    @Test
    void theIssuedTokenGrantsAccessToProtectedEndpoints() throws Exception {
        String token = json(tokenRequest(CLIENT_ID, CLIENT_SECRET, "client_credentials", null), 200)
                .get("access_token").asString();

        MvcResult result = mockMvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                () -> "令牌被拒：" + body(result));

        // 登录引导还应当认出这个令牌属于谁
        JsonNode session = json(mockMvc.perform(get(AUTH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), 200).get("session");
        assertTrue(session.get("authenticated").asBoolean());
        assertEquals(PRINCIPAL_NAME, session.get("principal").asString());
        assertEquals("console-access-token", session.get("source").asString());
        assertTrue(session.get("expiresAtMillis").asLong() > System.currentTimeMillis());
    }

    @Test
    void wrongSecretIsRejectedAsInvalidClient() throws Exception {
        JsonNode body = json(tokenRequest(CLIENT_ID, "not-the-secret", "client_credentials", null), 401);

        assertEquals("invalid_client", body.get("error").asString());
        assertNotNull(body.get("error_description"));
    }

    /**
     * 不存在的 clientId 与「密码不对」返回完全相同的响应。
     *
     * <p>区分它们会让攻击者先枚举出真实存在的 clientId，把爆破面从
     * 「两个都不知道」缩小成「只知道密码」。因此这里连措辞都要一致。
     */
    @Test
    void unknownClientIdIsIndistinguishableFromAWrongSecret() throws Exception {
        MvcResult unknown = tokenRequest("no-such-client", CLIENT_SECRET, "client_credentials", null);
        MvcResult wrongSecret = tokenRequest(CLIENT_ID, "not-the-secret", "client_credentials", null);

        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(401, wrongSecret.getResponse().getStatus());
        assertEquals(body(unknown), body(wrongSecret),
                "两种失败的报文必须一字不差，否则可以据此枚举 clientId");
    }

    /** 401 要带 {@code WWW-Authenticate}，否则通用 OAuth 客户端的行为不一致。 */
    @Test
    void invalidClientCarriesABearerChallenge() throws Exception {
        MvcResult result = tokenRequest(CLIENT_ID, "wrong", "client_credentials", null);

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("Bearer", result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void missingCredentialsAreReportedAsInvalidRequest() throws Exception {
        JsonNode body = json(tokenRequest(CLIENT_ID, null, "client_credentials", null), 400);
        assertEquals("invalid_request", body.get("error").asString());

        JsonNode noClientId = json(tokenRequest(null, CLIENT_SECRET, "client_credentials", null), 400);
        assertEquals("invalid_request", noClientId.get("error").asString());
    }

    @Test
    void missingGrantTypeIsReportedAsInvalidRequest() throws Exception {
        JsonNode body = json(tokenRequest(CLIENT_ID, CLIENT_SECRET, null, null), 400);

        assertEquals("invalid_request", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("grant_type"));
    }

    /** 不支持的 grant_type 有专门的错误码——它与「少传参数」要采取的行动不同。 */
    @Test
    void unsupportedGrantTypeIsReportedSeparately() throws Exception {
        JsonNode body = json(tokenRequest(CLIENT_ID, CLIENT_SECRET, "authorization_code", null), 400);

        assertEquals("unsupported_grant_type", body.get("error").asString());
    }

    /**
     * 只接受 {@code PRINCIPAL_ROLE:ALL}。
     *
     * <p>本工程的授权判定是把主体的全部角色并起来算，没有「以某个角色访问」这一层。
     * 接受一个不生效的 scope 会让人以为权限被收窄了，而实际没有——
     * 这种「看起来生效」的配置比直接拒绝危险得多。
     *
     * <p>错误码是 {@code invalid_scope} 而不是 {@code invalid_request}：
     * RFC 6749 第 5.2 节把 scope 取值问题单独分开，而两者状态码都是 400，
     * 只有错误码能把这个区别带给调用方。
     */
    @Test
    void unsupportedScopeIsRejected() throws Exception {
        JsonNode body = json(tokenRequest(CLIENT_ID, CLIENT_SECRET, "client_credentials",
                "PRINCIPAL_ROLE:read_only"), 400);

        assertEquals("invalid_scope", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("PRINCIPAL_ROLE:ALL"));
    }

    @Test
    void emptyScopeIsAcceptedAndNormalised() throws Exception {
        assertEquals("PRINCIPAL_ROLE:ALL",
                json(tokenRequest(CLIENT_ID, CLIENT_SECRET, "client_credentials", "  "), 200)
                        .get("scope").asString());
    }

    /** RFC 6749 允许用 HTTP Basic 传客户端凭据。 */
    @Test
    void clientCredentialsCanBeSentWithHttpBasic() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body(result));
        assertNotNull(json(result, 200).get("access_token"));
    }

    /**
     * Basic 里的凭据应当先做 form-urlencode 解码。
     *
     * <p>规范要求如此（RFC 6749 第 2.3.1 节），而 clientSecret 里含 {@code :}、
     * {@code %} 这类字符是常见情况。少了这一步，这类密钥永远对不上，
     * 且错误信息只会说「凭据无效」。
     */
    @Test
    void basicCredentialsAreFormUrlDecoded() throws Exception {
        String secretWithSpecialCharacters = "pa:ss%word";
        principalRepository.findByClientId(CLIENT_ID).ifPresent(principalRepository::delete);
        principalRepository.save(newPrincipal(PRINCIPAL_NAME, CLIENT_ID, secretWithSpecialCharacters));

        String encoded = java.net.URLEncoder.encode(secretWithSpecialCharacters, StandardCharsets.UTF_8);
        String basic = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + encoded).getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body(result));
    }

    /** 也接受 JSON 请求体——拒绝它只会让人以为端点写错了。 */
    @Test
    void jsonRequestBodiesAreAccepted() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant_type\":\"client_credentials\",\"client_id\":\"" + CLIENT_ID
                                + "\",\"client_secret\":\"" + CLIENT_SECRET + "\"}"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body(result));
        assertNotNull(json(result, 200).get("access_token"));
    }

    /**
     * 请求体不是合法 JSON 时，也必须是 OAuth 的错误形状。
     *
     * <p>这条请求在参数解析阶段就失败了，而那一步在服务层之前——如果它漏到全局处理器，
     * 返回的会是 catalog API 的 {@code {message, resourceType, ...}}。那样一来
     * 「本端点的失败一律是 RFC 6749 第 5.2 节的形状」这条性质就只对一部分路径成立，
     * 而通用 OAuth 客户端库只认 {@code error} 字段，拿到 {@code message} 会当成未知错误。
     */
    @Test
    void malformedJsonBodiesStillUseTheOAuthErrorShape() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andReturn();

        JsonNode body = json(result, 400);
        assertEquals("invalid_request", body.get("error").asString());
        assertNotNull(body.get("error_description"));
        assertNull(body.get("message"), "不该退化成 catalog API 的错误形状");
    }

    // ------------------------------------------------------------------ 限速

    /**
     * 连续失败到达阈值后拒绝，且给出可机器判定的错误码。
     *
     * <p>令牌端点是整套鉴权里唯一能匿名反复调用的地方，没有限速时
     * 一次小规模并发就能带着大量数据库查询把连接池占满。
     */
    @Test
    void tooManyFailuresAreRateLimited() throws Exception {
        int limit = 3;
        for (int i = 0; i < limit; i++) {
            assertEquals(401, tokenRequest(CLIENT_ID, "wrong-" + i, "client_credentials", null)
                    .getResponse().getStatus());
        }

        MvcResult blocked = tokenRequest(CLIENT_ID, "wrong-again", "client_credentials", null);
        assertEquals(429, blocked.getResponse().getStatus());
        assertEquals("temporarily_unavailable", json(blocked, 429).get("error").asString());

        // 正确凭据同样被拒：限速是按来源计的，不该为「这次密码对了」开例外，
        // 否则攻击者可以靠偶尔猜中重置计数
        assertEquals(429, tokenRequest(CLIENT_ID, CLIENT_SECRET, "client_credentials", null)
                .getResponse().getStatus());
    }

    /** 成功的登录不计入失败计数——否则长时间开着的控制台会被自己的刷新流量锁住。 */
    @Test
    void successfulLoginsDoNotCountTowardsTheLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertEquals(200, tokenRequest(CLIENT_ID, CLIENT_SECRET, "client_credentials", null)
                    .getResponse().getStatus(), "第 " + (i + 1) + " 次正常登录被拒了");
        }
    }

    // ------------------------------------------------------------------ 令牌来源

    /**
     * 静态令牌仍然可用，且按 {@code token-principals} 映射成主体名。
     *
     * <p>本次改动撤掉的是「服务端登录用户名密码」，不是静态令牌：引擎侧
     * （Spark / Flink）把静态令牌写在客户端配置里，改动不该波及它们。
     * 这条同时守住「认出来的主体名是映射后的名字，而不是令牌本身」——
     * 授权判定按主体名查链路，映射丢了会让引擎突然没有任何权限。
     */
    @Test
    void staticTokensStillAuthenticateAndMapToTheirPrincipal() throws Exception {
        MvcResult probe = mockMvc.perform(get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STATIC_TOKEN)).andReturn();
        assertEquals(200, probe.getResponse().getStatus(), () -> "body=" + body(probe));

        JsonNode session = json(mockMvc.perform(get(AUTH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STATIC_TOKEN)).andReturn(), 200).get("session");
        assertTrue(session.get("authenticated").asBoolean());
        assertEquals("static-machine-user", session.get("principal").asString());
        assertEquals("static-token", session.get("source").asString());
        assertNull(session.get("expiresAtMillis"), "静态令牌没有失效时刻");
    }

    /**
     * 未登记的令牌被拒。
     *
     * <p>注意尾随空白会被规范化掉：{@code "Bearer <token> "} 里的空格属于传输噪声，
     * 不代表一个不同的令牌。因此这里刻意不把它当成「另一个令牌」来断言——
     * 那会让人以为「加个空格就能绕过鉴权」，而事实相反。
     */
    @Test
    void unknownTokensAreRejected() throws Exception {
        for (String token : List.of("not-a-real-token", "static-token-for-test", "STATIC-TOKEN-FOR-TESTS")) {
            MvcResult result = mockMvc.perform(get(PROTECTED)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
            assertEquals(401, result.getResponse().getStatus(), () -> "token=[" + token + "]");
        }
    }

    /** 令牌前后的空白被规范化，不影响判定。 */
    @Test
    void surroundingWhitespaceIsNormalised() throws Exception {
        assertEquals(200, mockMvc.perform(get(PROTECTED)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer  " + STATIC_TOKEN + "  "))
                .andReturn().getResponse().getStatus());
    }

    /** 伪造的 JWT：结构对、签名不对，必须被拒。 */
    @Test
    void forgedTokensAreRejected() throws Exception {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"paimon-rest\",\"sub\":\"root\",\"exp\":9999999999}");
        String forged = header + "." + payload + "." + base64Url("not-a-real-signature");

        assertEquals(401, mockMvc.perform(get(PROTECTED)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andReturn().getResponse().getStatus());
    }

    /** {@code Authorization} 头的形状不对时一律 401，不抛异常。 */
    @Test
    void malformedAuthorizationHeadersAreRejected() throws Exception {
        for (String header : List.of("", "Bearer", "Bearer ", "Basic abc", "token", "bearer lowercase")) {
            MvcResult result = mockMvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, header))
                    .andReturn();
            assertEquals(401, result.getResponse().getStatus(), () -> "header=[" + header + "]");
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 用 JSON 请求体打登录端点。
     *
     * <p>{@code null} 的字段直接不写进请求体（而不是写成 {@code null} 值）：
     * 这样「没填」与「填了空串」是两种不同的输入，两者都该走到服务层的同一条 400。
     */
    private MvcResult login(String username, String password) throws Exception {
        var requestBody = mapper.createObjectNode();
        if (username != null) {
            requestBody.put("username", username);
        }
        if (password != null) {
            requestBody.put("password", password);
        }
        return mockMvc.perform(post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(requestBody))).andReturn();
    }

    private MvcResult tokenRequest(String clientId, String clientSecret, String grantType, String scope)
            throws Exception {
        var request = post(TOKEN_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (grantType != null) {
            request = request.param("grant_type", grantType);
        }
        if (clientId != null) {
            request = request.param("client_id", clientId);
        }
        if (clientSecret != null) {
            request = request.param("client_secret", clientSecret);
        }
        if (scope != null) {
            request = request.param("scope", scope);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        String text = body(result);
        assertEquals(expectedStatus, result.getResponse().getStatus(), () -> "body=" + text);
        return mapper.readTree(text);
    }

    /**
     * 按 UTF-8 读响应体。
     *
     * <p>不声明 {@code throws}：这个方法要在断言的错误消息里用（lambda 里抛受检异常
     * 编译不过），因此把唯一的受检异常就地转成运行时异常——
     * UTF-8 在 JVM 上必然存在，走到那个分支说明环境本身坏了。
     */
    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    private static List<String> strings(JsonNode array) {
        assertNotNull(array);
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PrincipalEntity newPrincipal(String name, String clientId, String secret) {
        PrincipalEntity entity = new PrincipalEntity();
        entity.setId(Paging.newId());
        entity.setName(name);
        entity.setClientId(clientId);
        entity.setProperties(new LinkedHashMap<>());
        String salt = Secrets.newSalt();
        entity.setSecretSalt(salt);
        entity.setSecretHash(Secrets.hash(salt, secret));
        entity.markCreated("system", System.currentTimeMillis());
        return entity;
    }
}
