package com.example.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code file-io.type} 与 {@code credential-manager.type} 两个策略开关的验收。
 *
 * <p>这两个开关的价值都在于「把错误提前」，因此测的重点不是生效后的正向行为，
 * 而是它们挡住了什么：不接受本部署没接入的存储类型、不发放已声明停发的凭据。
 *
 * <p>单独起一份 Spring 上下文：两个开关都在启动期读取，改不了，只能通过
 * {@code properties} 另起一份。代价是多一次上下文启动，换来的是这两个开关
 * 真的按配置取值走，而不是被代码里的默认值掩盖住——默认值恰好是最宽松的那个，
 * 不换上下文根本测不出「配了限制却没生效」。
 *
 * <p>注意 {@code test} profile 用的是同一个具名内存 H2 库，因此本类自建的 catalog
 * 在用例结束时要删掉，尤其是 {@code .../token} 那条用例会往库里写真实数据。
 */
@SpringBootTest(properties = {
        "paimon.rest.file-io.type=s3",
        "paimon.rest.credential-manager.type=noop"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StoragePolicyApiTests {

    private static final String M = "/api/management/v1";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            MvcResult result = perform(delete(M + "/catalogs/" + name));
            assertEquals(204, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        }
        created.clear();
    }

    // ------------------------------------------------------------------ file-io.type

    /**
     * {@code file-io.type=s3} 时创建 Azure / GCS catalog 应当被拒。
     *
     * <p>若不拦，这份配置会一路存进库，直到引擎真的去读写时才以文件系统异常暴露——
     * 到那一步，没人会往「catalog 的存储类型选错了」这个方向查。
     */
    @Test
    void storageTypesOutsideFileIoAreRejected() throws Exception {
        MvcResult azure = perform(createCatalog("policy-azure", """
                {"storageType":"AZURE",
                 "allowedLocations":["abfss://container@acct.dfs.core.windows.net/wh/"],
                 "tenantId":"tenant-1"}
                """));
        assertEquals(400, azure.getResponse().getStatus(), azure.getResponse().getContentAsString());
        // 错误信息要指向真正的原因：不是配置不合法，而是本部署没接入这个实现
        String azureMessage = azure.getResponse().getContentAsString();
        assertTrue(azureMessage.contains("paimon.rest.file-io.type"), azureMessage);
        assertTrue(azureMessage.contains("AZURE"), azureMessage);

        MvcResult gcs = perform(createCatalog("policy-gcs", """
                {"storageType":"GCS","allowedLocations":["gs://bucket/wh/"]}
                """));
        assertEquals(400, gcs.getResponse().getStatus(), gcs.getResponse().getContentAsString());
    }

    /** 本部署接入了 S3，创建 S3 catalog 与本地仓库都应当通过——限制的是没有的实现，不是云存储本身。 */
    @Test
    void fileIoTypeAcceptsItsOwnStorageTypeAndLocal() throws Exception {
        JsonNode s3 = json(201, createCatalog("policy-s3", """
                {"storageType":"S3","allowedLocations":["s3://bucket/wh/"]}
                """));
        created.add("policy-s3");
        assertEquals("S3", s3.get("storageConfigInfo").get("storageType").asString());

        // 本地仓库是任何取值下的退路：用未登记的 prefix 触发自动创建即可
        JsonNode config = json(200, perform(get("/v1/config?warehouse=policy-local")));
        created.add("policy-local");
        assertEquals("policy-local", config.get("defaults").get("prefix").asString());
    }

    // ------------------------------------------------------------------ credential-manager.type

    /**
     * {@code credential-manager.type=noop} 时不发放凭据，且状态码要说清是哪一类拒绝。
     *
     * <p>501 而不是 403：这是「本部署没有这项能力」，不是「你这个身份不够格」。
     * 用 403 会让调用方去申请权限，而真正该做的是改用引擎自带的云凭据。
     */
    @Test
    void noopCredentialManagerRefusesToVend() throws Exception {
        json(201, createCatalog("policy-noop", """
                {"storageType":"S3","allowedLocations":["s3://bucket/wh/"]}
                """));
        created.add("policy-noop");
        json(200, perform(post("/v1/policy-noop/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"cred_db\",\"options\":{}}")));
        json(200, perform(post("/v1/policy-noop/databases/cred_db/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":{"database":"cred_db","object":"orders"},
                         "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT NOT NULL"}],
                                   "primaryKeys":["id"],"options":{"bucket":"1"}}}
                        """)));

        MvcResult token = perform(get("/v1/policy-noop/databases/cred_db/tables/orders/token"));
        assertEquals(501, token.getResponse().getStatus(), token.getResponse().getContentAsString());
        assertTrue(token.getResponse().getContentAsString().contains("noop"),
                token.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 建 catalog 请求。刻意不在此处登记清理名单：调用方在断言成功之后才登记，
     * 否则被拒的请求也会进名单，清理阶段会去删一个从未存在的 catalog 而报 404，
     * 掩盖掉真正的失败原因。
     */
    private MockHttpServletRequestBuilder createCatalog(String name, String storageConfigInfo) {
        return post(M + "/catalogs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                         "storageConfigInfo":%s}}
                        """.formatted(name, storageConfigInfo));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(int expected, MockHttpServletRequestBuilder request) throws Exception {
        return json(expected, perform(request));
    }

    private JsonNode json(int expected, MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "unexpected status, body=" + body);
        return mapper.readTree(body);
    }
}
