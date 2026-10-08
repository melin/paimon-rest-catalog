package com.example.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 按存储类型下发凭据的 HTTP 层验收。
 *
 * <p>为什么需要这一层：{@code StorageCredentialResolutionTests} 证明了解析规则本身对，
 * 但证明不了「读表接口真的读了 catalog 的存储配置」。这两件事之间隔着一段容易断的链路——
 * catalog 的配置是从 JSON 列反序列化出来的，不是内存里的对象；凭据经过缓存再返回。
 * 只有走完整链路才能发现「解析写对了但拿到的是一份空配置」这类问题。
 *
 * <p>配置通过 {@code properties} 注入而不是改 application.yml：
 * 这样这个 Spring 上下文与其它测试的上下文互不影响，也不会给生产配置留下测试用的密钥。
 *
 * <p>{@code test} profile 把数据源换成内存 H2，本类不依赖外部服务，也不真的访问任何云存储——
 * 凭据解析不发出网请求，这一点是被测行为的一部分。
 */
@SpringBootTest(properties = {
        "paimon.rest.storage.aws.access-key=AKIA-CONFIG",
        "paimon.rest.storage.aws.secret-key=SECRET-CONFIG",
        "paimon.rest.storage.aws.storages.named-a.access-key=AKIA-NAMED",
        "paimon.rest.storage.aws.storages.named-a.secret-key=SECRET-NAMED",
        "paimon.rest.storage.gcp.token=ya29.token-from-config",
        "paimon.rest.storage.gcp.lifespan=10m"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StorageCredentialApiTests {

    private static final String M = "/api/management/v1";

    private static final String TABLE = "orders";

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

    // ------------------------------------------------------------------ S3

    @Test
    void s3CatalogVendsConfiguredCredentials() throws Exception {
        String prefix = "s3-cred";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"ap-east-1",
                 "endpoint":"https://s3.example.com:1234",
                 "endpointInternal":"https://s3.internal.example.com:1234",
                 "pathStyleAccess":true}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("AKIA-CONFIG", token.get("s3.access-key-id").asString());
        assertEquals("SECRET-CONFIG", token.get("s3.secret-access-key").asString());
        assertEquals("ap-east-1", token.get("s3.region").asString());
        assertEquals("https://s3.example.com:1234", token.get("s3.endpoint").asString());
        assertEquals("true", token.get("s3.path-style-access").asString());

        // 服务端专用地址绝不能下发
        assertFalse(token.has("s3.endpoint-internal"));
        assertFalse(token.has("s3.sts-endpoint"));
        assertFalse(token.toString().contains("s3.internal.example.com"));
    }

    /** 具名存储优先于默认凭据：catalog 显式指名时，用的必须是那一组密钥。 */
    @Test
    void s3CatalogUsesNamedStorageWhenReferenced() throws Exception {
        String prefix = "s3-named";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://named-bucket/warehouse/"],
                 "storageName":"named-a"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("AKIA-NAMED", token.get("s3.access-key-id").asString());
        assertEquals("SECRET-NAMED", token.get("s3.secret-access-key").asString());
    }

    /** 具名存储写错时读表接口应当明确报错，而不是悄悄换成默认凭据。 */
    @Test
    void s3CatalogWithUnknownStorageNameFailsLoudly() throws Exception {
        String prefix = "s3-bad-name";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://named-bucket/warehouse/"],
                 "storageName":"not-configured"}
                """);
        seedTable(prefix);

        MvcResult result = mockMvc.perform(get(tokenPath(prefix))).andReturn();
        assertEquals(500, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("not-configured"));
    }

    /** {@code stsUnavailable} 表示服务端不代发凭据，但仍应告诉引擎连哪里。 */
    @Test
    void s3CatalogWithStsUnavailableVendsNoSecrets() throws Exception {
        String prefix = "s3-no-sts";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"eu-west-1",
                 "stsUnavailable":true}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertFalse(token.has("s3.access-key-id"));
        assertFalse(token.has("s3.secret-access-key"));
        assertEquals("eu-west-1", token.get("s3.region").asString());
    }

    // ------------------------------------------------------------------ Azure

    @Test
    void azureCatalogVendsTenantAndAccountWithoutSecrets() throws Exception {
        String prefix = "azure-cred";
        createCatalog(prefix, """
                {"storageType":"AZURE",
                 "allowedLocations":["abfss://container@myaccount.dfs.core.windows.net/warehouse/"],
                 "tenantId":"72f988bf-86f1-41af-91ab-2d7cd011db47",
                 "hierarchical":true}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("72f988bf-86f1-41af-91ab-2d7cd011db47", token.get("azure.tenant-id").asString());
        assertEquals("myaccount", token.get("azure.account").asString());
        assertEquals("true", token.get("azure.hierarchical").asString());
        // 没有账户密钥就没有可下发的凭据，这一点必须是显式的，不能靠「大概没有」
        assertFalse(token.has("adls.sas-token"));
        assertFalse(token.has("azure.account-key"));
    }

    // ------------------------------------------------------------------ GCS

    /** {@code lifespan} 比 {@code credential.ttl-seconds} 短时，响应里的过期时刻要跟着收窄。 */
    @Test
    void gcsCatalogVendsTokenAndHonoursLifespan() throws Exception {
        String prefix = "gcs-cred";
        createCatalog(prefix, """
                {"storageType":"GCS",
                 "allowedLocations":["gs://analytics-bucket/warehouse/"],
                 "gcsServiceAccount":"catalog@project.iam.gserviceaccount.com"}
                """);
        seedTable(prefix);

        JsonNode body = token(prefix);
        JsonNode token = body.get("token");
        assertEquals("ya29.token-from-config", token.get("gcs.oauth2.token").asString());
        assertEquals("catalog@project.iam.gserviceaccount.com", token.get("gcs.service-account").asString());

        long remaining = body.get("expiresAt").asLong() - System.currentTimeMillis();
        assertTrue(remaining > 0 && remaining <= 600_000L + 5_000L,
                "gcs lifespan=10m should cap expiresAt well below the 1h default, remaining=" + remaining);
    }

    // ------------------------------------------------------------------ FILE 与缓存

    /** 本地仓库没有凭据，保留既有的自包含令牌与表路径。 */
    @Test
    void fileCatalogKeepsSelfContainedToken() throws Exception {
        String prefix = "file-cred";
        createCatalog(prefix, """
                {"storageType":"FILE","allowedLocations":["file:///tmp/file-cred-warehouse/"]}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertTrue(token.get("accessKeyId").asString().startsWith("PAIMON-"));
        assertTrue(token.has("securityToken"));
        assertTrue(token.get("tablePath").asString().contains("file-cred-warehouse"));
    }

    /**
     * 同一张表在凭据有效期内反复读，拿到的是同一份凭据。
     *
     * <p>断言过期时刻相同，而不是只断言密钥相同：FILE 的密钥本来就是随机的，
     * 若每次都重新签发，过期时刻会往后漂——那说明缓存没生效，
     * 而密钥相同这种断言在配置驱动的云存储上根本看不出区别。
     */
    @Test
    void repeatedRequestsReuseCachedCredential() throws Exception {
        String prefix = "s3-cache";
        createCatalog(prefix, """
                {"storageType":"S3","allowedLocations":["s3://analytics-bucket/warehouse/"]}
                """);
        seedTable(prefix);

        JsonNode first = token(prefix);
        JsonNode second = token(prefix);

        assertEquals(first.get("expiresAt").asLong(), second.get("expiresAt").asLong());
        assertEquals(first.get("token").toString(), second.get("token").toString());
    }

    // ------------------------------------------------------------------ 辅助

    private void createCatalog(String name, String storageConfigInfo) throws Exception {
        created.add(name);
        json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":%s}}
                """.formatted(name, storageConfigInfo));
    }

    /** 建库并建表，使凭据下发接口有对象可查。 */
    private void seedTable(String prefix) throws Exception {
        json(200, "POST", "/v1/" + prefix + "/databases", "{\"name\":\"cred_db\",\"options\":{}}");
        json(200, "POST", "/v1/" + prefix + "/databases/cred_db/tables", """
                {"identifier":{"database":"cred_db","object":"%s"},
                 "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT NOT NULL"}],
                           "primaryKeys":["id"],"options":{"bucket":"1"}}}
                """.formatted(TABLE));
    }

    private String tokenPath(String prefix) {
        return "/v1/" + prefix + "/databases/cred_db/tables/" + TABLE + "/token";
    }

    private JsonNode token(String prefix) throws Exception {
        return json(200, "GET", tokenPath(prefix), null);
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
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request);
    }
}
