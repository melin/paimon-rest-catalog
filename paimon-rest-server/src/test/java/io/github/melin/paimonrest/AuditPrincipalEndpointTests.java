package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.paimonrest.dto.DatabaseDtos;
import io.github.melin.paimonrest.service.DatabaseService;
import io.github.melin.paimonrest.support.Paging;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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
 * 审计主体名从请求走到元数据库的端到端回归。
 *
 * <p><b>复现的是这个故障：</b>门禁模式（{@code auth.enabled=false}，默认部署）下，
 * 认证链认不出的令牌会退化为「令牌即主体名」，而控制台访问令牌长 272 个字符。
 * 审计列是 {@code varchar(255)}，于是建表以
 * {@code Data too long for column 'created_by'} 失败——报错信息里与认证毫无关系，
 * 却要顺着认证链路才找得到原因。这个用例把整条路径钉住：只要它变绿，
 * 「长令牌让写入失败」就不会再回来。
 *
 * <p>走 HTTP 而不是直接调服务层，因为要覆盖的正是拦截器→主体名→实体→JDBC 这一段。
 *
 * <p>使用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paimon-audit-principal;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuditPrincipalEndpointTests {

    private static final String PREFIX = "paimon";

    /** 与实测签发的控制台访问令牌等长（272 字符），且本实例从未签发过它。 */
    private static final String UNKNOWN_LONG_TOKEN = jwtOfLength(272);

    private static final String ADMIN = "admin";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    /** 建库走服务层：本类要验的是「建表时审计列怎么被写」，库只是布景。 */
    @Autowired
    private DatabaseService databases;

    /** 每个用例一个自己的库，互不干扰，也不依赖初始化的种子数据。 */
    private String database;

    @BeforeEach
    void createDatabase() {
        database = "audit_" + Paging.newId().substring(0, 8);
        databases.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(database, Map.of()));
    }

    @Test
    void aLongUnrecognizedTokenNoLongerBreaksWrites() throws Exception {
        MvcResult created = createTable("t_long", UNKNOWN_LONG_TOKEN);

        assertEquals(200, created.getResponse().getStatus(),
                () -> "长令牌不该把建表打挂，响应=" + body(created));
        // 认不出、又长于列宽的令牌，记录为摘要：既不溢出，也不把凭据片段写进元数据库
        assertEquals(expectedLabel(UNKNOWN_LONG_TOKEN), auditOf("t_long", UNKNOWN_LONG_TOKEN).get("createdBy").asString());
    }

    /** 令牌就是主体名这条约定要留着：本地 `Bearer alice` 调试仍然可用。 */
    @Test
    void aNameShapedTokenIsStillRecordedAsThePrincipalName() throws Exception {
        assertEquals(200, createTable("t_alice", "alice").getResponse().getStatus());

        JsonNode audit = auditOf("t_alice", "alice");
        assertEquals("alice", audit.get("createdBy").asString());
        assertEquals("alice", audit.get("owner").asString(), "owner 与 createdBy 应取自同一主体");
        assertEquals("alice", audit.get("updatedBy").asString());
    }

    /** 正常路径不受影响：服务端自己签发的令牌解析出的是真实主体名。 */
    @Test
    void aRecognizedConsoleTokenIsRecordedByItsPrincipalName() throws Exception {
        String issued = consoleAccessToken();

        assertEquals(200, createTable("t_admin", issued).getResponse().getStatus());

        JsonNode audit = auditOf("t_admin", issued);
        assertEquals(ADMIN, audit.get("createdBy").asString(), "签发令牌的主体名应当原样入库");
        assertTrue(audit.get("createdBy").asString().length() <= 255);
    }

    @Test
    void anAnonymousWriteIsRecordedAsAnonymous() throws Exception {
        assertEquals(200, createTable("t_anon", null).getResponse().getStatus());

        assertEquals("anonymous", auditOf("t_anon", null).get("createdBy").asString());
    }

    // ------------------------------------------------------------------ 辅助

    private MvcResult createTable(String tableName, String token) throws Exception {
        var request = post("/v1/" + PREFIX + "/databases/" + database + "/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":{"database":"%s","object":"%s"},
                         "schema":{"fields":[{"id":1,"name":"c1","type":"bigint"}],
                                   "partitionKeys":[],"primaryKeys":[]}}
                        """.formatted(database, tableName));
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** 读回一张表的审计字段。读也要带令牌：不带时主体是 anonymous，不影响读，但保持一致。 */
    private JsonNode auditOf(String tableName, String token) throws Exception {
        var request = get("/v1/" + PREFIX + "/databases/" + database + "/tables/" + tableName);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertEquals(200, result.getResponse().getStatus(), () -> "读表失败，响应=" + body(result));
        JsonNode audit = mapper.readTree(body(result));
        assertNotNull(audit.get("createdBy"), () -> "响应里应当有审计字段，实际=" + audit);
        return audit;
    }

    /** 用默认账号登录控制台，拿一个服务端自己签发的访问令牌。 */
    private String consoleAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/console/v1/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN + "\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), () -> "登录失败，响应=" + body(result));
        return mapper.readTree(body(result)).get("accessToken").asString();
    }

    /**
     * 独立算一遍期望值：用它算期望值就等于把被测实现抄了一遍，错了也一起错。
     */
    private static String expectedLabel(String token) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 12);
    }

    /** 与实测令牌等长、JWT 形状的字符串。按长度构造，避免手抄位数出错。 */
    private static String jwtOfLength(int length) {
        StringBuilder token = new StringBuilder("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.");
        while (token.length() < length) {
            token.append("0fQ7t0mZ2c9V1k8pQ5r4s6u7w8x9y");
        }
        return token.substring(0, length);
    }

    /**
     * 读响应体。
     *
     * <p>这里把 {@code UnsupportedEncodingException} 包成非受检异常：编码恒为 UTF-8，
     * 该分支走不到；而方法声明成 {@code throws} 会让它没法用在断言消息的 lambda 里。
     */
    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("读取响应体失败", e);
        }
    }
}
