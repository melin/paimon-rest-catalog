package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
 * 未配置落库加密密钥时，静态凭据功能的失败方向。
 *
 * <p><b>为什么单列一个类：</b>它必须运行在一个没配
 * {@code paimon.rest.storage.credential-secret-key} 的上下文里，
 * 而 {@code StaticCredentialApiTests} 恰恰要配。两者的配置互斥，
 * 只能各占一个 Spring 上下文（本类复用 test profile 的既有上下文，代价很小）。
 *
 * <p>要钉住的是一句话：<b>宁可拒绝保存，也不明文落库</b>。
 * 保存不下来是一个立刻可见、可解释的失败；明文躺进数据库则是一个没人会发现的承诺被打破。
 * 同时，其余功能必须不受影响——没有静态凭据的 catalog 一个字节都不经过加解密。
 */
@SpringBootTest(properties = {
        // 控制台端点默认要求先登录（console.required=true），元数据也不例外
        "paimon.rest.auth.tokens[0]=static-cred-disabled-token"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StaticCredentialDisabledApiTests {

    private static final String M = "/api/management/v1";

    private static final String CONSOLE_TOKEN = "static-cred-disabled-token";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            call(204, delete(M + "/catalogs/" + name));
        }
        created.clear();
    }

    @Test
    void savingStaticCredentialsIsRejectedWithAnActionableMessage() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-disabled","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "accessKeyId":"AKIA-X","secretAccessKey":"SECRET-X"}}}
                """);

        assertEquals(400, error.get("code").asInt());
        String message = error.get("message").asString();
        assertTrue(message.contains("credential-secret-key"), "应点明缺的是哪个配置: " + message);
        assertTrue(message.contains("openssl rand -base64 32"), "应给出生成密钥的命令: " + message);
    }

    /** 同一个上下文里，不带静态凭据的 catalog 照常建得起来——功能关闭是局部的。 */
    @Test
    void catalogsWithoutStaticCredentialsStillWork() throws Exception {
        created.add("no-static-s3");
        JsonNode created = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"no-static-s3","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "region":"us-east-1"}}}
                """);

        assertEquals("S3", created.get("storageConfigInfo").get("storageType").asString());
        assertFalse(created.get("storageConfigInfo").has("accessKeyId"));
    }

    /** 控制台据此禁用输入并说明原因，而不是让用户填完再吃 400。 */
    @Test
    void metaReportsStaticCredentialsAsDisabled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/console/v1/meta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CONSOLE_TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        JsonNode enums = mapper.readTree(body).get("enums");

        assertFalse(enums.get("staticCredentialsEnabled").asBoolean(),
                "没配加密密钥就应报告为不可用: " + enums);
    }

    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status, body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request);
    }
}
