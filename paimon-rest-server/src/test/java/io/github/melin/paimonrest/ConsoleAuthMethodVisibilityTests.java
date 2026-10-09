package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 登录方式的可见性：**没开的登录方式不出现在登录引导里**。
 *
 * <p>登录页的页签完全由 {@code GET /api/console/v1/auth} 的 {@code methods} 生成，
 * 所以「该不该出现」这件事只在服务端一处决定，前端不做二次判断。
 * 列一个用不了的方式比不列更糟：使用者会照着填，然后收到一条与服务端配置无关的错误
 * （静态令牌没配时，填什么都会被拒），排查方向从一开始就是错的。
 *
 * <p>本类的配置刻意与 {@link ConsoleApiTests}、{@link ConsoleAuthEndpointTests} 相反。
 * 那两个类都登记了静态令牌，覆盖的是「配了就该出现」；这里不登记任何令牌、
 * 并显式关掉客户端凭据，覆盖的是另一半——不配、关掉，就不出现。
 * 两块合起来，四种方式的每一个开关才算都被钉住。
 */
@SpringBootTest(properties = {
        // 关掉客户端凭据：登录页不该再出现「主体凭据」页签
        "paimon.rest.auth.console.client-credentials.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConsoleAuthMethodVisibilityTests {

    private static final String AUTH = "/api/console/v1/auth";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 关掉的方式与没配置的方式都不出现，开着的那一个照旧。
     *
     * <p>三条断言各对应一种判据：{@code client-credentials} 看配置开关，
     * {@code static-token} 看 {@code auth.tokens} 里有没有值，{@code password}
     * 是默认开启的对照组——少了它，一个「无脑返回空列表」的实现也能让前两条通过。
     */
    @Test
    void methodsOnlyListWhatIsActuallyAvailable() throws Exception {
        JsonNode body = mapper.readTree(json());

        assertTrue(body.get("consoleRequired").asBoolean(),
                "控制台默认要求登录，本次改动不影响这一点");
        assertEquals(List.of("password"), strings(body.get("methods")),
                "关掉客户端凭据、又没登记静态令牌时，只剩默认开启的用户名密码");
        assertNull(body.get("oidc"), "未启用 OIDC 时不下发客户端参数");
    }

    /**
     * 静态令牌的开启只看「配置里有没有令牌」。
     *
     * <p>单独断言一次而不是靠上一条的列表相等：上一条失败时无法区分是
     * 「客户端凭据没藏住」还是「静态令牌冒出来了」，而这两件事的修法完全不同。
     */
    @Test
    void staticTokenIsHiddenUntilItIsConfigured() throws Exception {
        List<String> methods = strings(mapper.readTree(json()).get("methods"));
        assertFalse(methods.contains("static-token"),
                () -> "paimon.rest.auth.tokens 为空时，降级入口不该出现在登录页上；methods=" + methods);
    }

    /** 按 UTF-8 读响应体；非 200 时把响应内容带进断言消息，省一次重跑。 */
    private String json() throws Exception {
        MvcResult result = mockMvc.perform(get(AUTH)).andReturn();
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + text);
        return text;
    }

    private static List<String> strings(JsonNode array) {
        assertTrue(array != null && array.isArray(), "期望一个 JSON 数组");
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }
}
