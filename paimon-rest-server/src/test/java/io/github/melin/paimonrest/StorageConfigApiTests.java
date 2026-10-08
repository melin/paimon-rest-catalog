package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * {@code storageConfigInfo} 每种存储类型的 HTTP 层验收。
 *
 * <p>为什么单独一层：DTO 层的测试（{@code StorageConfigDtosTests}）只能证明
 * 「JSON 与 Java 模型对得上」，证明不了「服务端真的按类型校验、真的把配置存下来、
 * 真的用它推导仓库位置」。这里走完整链路，重点验三件事：
 *
 * <ol>
 *   <li>每种 {@code storageType} 各自能建、能读、能改，类型专属字段不丢也不串；
 *   <li>按类型校验生效——{@code AZURE} 缺 {@code tenantId} 应当被拒，而不是静默写成半份配置；
 *   <li>存储位置真的传导到数据面——改过 {@code allowedLocations} 之后，
 *       该 catalog 下新建的库位于新位置之下。
 * </ol>
 *
 * <p>第三点是这一层最不可替代的部分：管理面与 catalog 面共用同一个 catalog 对象，
 * 只有把两边串起来跑才能发现「配置存对了但仓库没跟着变」这类断链。
 *
 * <p>{@code test} profile 把数据源换成内存 H2，本类不依赖外部服务。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StorageConfigApiTests {

    private static final String M = "/api/management/v1";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 本类建过的 catalog，用例结束后逐个删除，避免污染共享的 Spring 上下文。 */
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            call(204, delete(M + "/catalogs/" + name));
        }
        created.clear();
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

    /** 建一个 catalog 并登记以便清理。 */
    private JsonNode createCatalog(String name, String storageConfigInfo) throws Exception {
        created.add(name);
        return json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":%s}}
                """.formatted(name, storageConfigInfo));
    }

    /** 列出字符串数组字段，省去每条断言都写一遍类型工厂。 */
    private List<String> strings(JsonNode node) {
        return mapper.convertValue(node,
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    // ------------------------------------------------------------------ 各类型各自往返

    @Test
    void s3CatalogKeepsEveryS3SpecificField() throws Exception {
        JsonNode created = createCatalog("s3-catalog", """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "roleArn":"arn:aws:iam::123456789001:role/polaris-s3",
                 "externalId":"external-id-1234",
                 "userArn":"arn:aws:iam::123456789001:user/polaris",
                 "region":"ap-east-1",
                 "endpoint":"https://s3.example.com:1234",
                 "stsEndpoint":"https://sts.example.com:1234",
                 "endpointInternal":"https://s3.internal.example.com:1234",
                 "pathStyleAccess":true,
                 "stsUnavailable":false,
                 "kmsUnavailable":false,
                 "encryptionKeys":["arn:aws:kms:ap-east-1:123456789001:key/enc"],
                 "decryptionKeys":["arn:aws:kms:ap-east-1:123456789001:key/dec"]}
                """);

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("S3", storage.get("storageType").asString());
        assertEquals("arn:aws:iam::123456789001:role/polaris-s3", storage.get("roleArn").asString());
        assertEquals("external-id-1234", storage.get("externalId").asString());
        assertEquals("arn:aws:iam::123456789001:user/polaris", storage.get("userArn").asString());
        assertEquals("ap-east-1", storage.get("region").asString());
        assertEquals("https://s3.example.com:1234", storage.get("endpoint").asString());
        assertEquals("https://sts.example.com:1234", storage.get("stsEndpoint").asString());
        assertEquals("https://s3.internal.example.com:1234", storage.get("endpointInternal").asString());
        assertTrue(storage.get("pathStyleAccess").asBoolean());
        assertFalse(storage.get("stsUnavailable").asBoolean());
        assertFalse(storage.get("kmsUnavailable").asBoolean());
        assertEquals(List.of("arn:aws:kms:ap-east-1:123456789001:key/enc"),
                strings(storage.get("encryptionKeys")));
        assertEquals(List.of("arn:aws:kms:ap-east-1:123456789001:key/dec"),
                strings(storage.get("decryptionKeys")));

        // 重新读取：字段必须真的落库了，而不只是回显请求
        JsonNode fetched = json(200, "GET", M + "/catalogs/s3-catalog", null);
        assertEquals("arn:aws:iam::123456789001:role/polaris-s3",
                fetched.get("storageConfigInfo").get("roleArn").asString());
        assertEquals("https://s3.internal.example.com:1234",
                fetched.get("storageConfigInfo").get("endpointInternal").asString());

        // 非 S3 字段不应出现
        assertFalse(fetched.get("storageConfigInfo").has("tenantId"));
        assertFalse(fetched.get("storageConfigInfo").has("gcsServiceAccount"));
    }

    @Test
    void azureCatalogKeepsItsOwnFields() throws Exception {
        JsonNode created = createCatalog("azure-catalog", """
                {"storageType":"AZURE",
                 "allowedLocations":["abfss://container@account.blob.core.windows.net/warehouse/"],
                 "tenantId":"72f988bf-86f1-41af-91ab-2d7cd011db47",
                 "multiTenantAppName":"polaris-multitenant",
                 "consentUrl":"https://login.microsoftonline.com/tenant/adminconsent",
                 "hierarchical":true}
                """);

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("AZURE", storage.get("storageType").asString());
        assertEquals("72f988bf-86f1-41af-91ab-2d7cd011db47", storage.get("tenantId").asString());
        assertEquals("polaris-multitenant", storage.get("multiTenantAppName").asString());
        assertEquals("https://login.microsoftonline.com/tenant/adminconsent",
                storage.get("consentUrl").asString());
        assertTrue(storage.get("hierarchical").asBoolean());
        assertFalse(storage.has("roleArn"), "Azure 配置不应带出 S3 字段");
    }

    @Test
    void gcsCatalogKeepsServiceAccount() throws Exception {
        String serviceAccount = "{\"type\":\"service_account\",\"project_id\":\"demo\"}";
        JsonNode created = createCatalog("gcs-catalog", """
                {"storageType":"GCS",
                 "allowedLocations":["gs://demo-lake/warehouse/"],
                 "gcsServiceAccount":%s}
                """.formatted(mapper.writeValueAsString(serviceAccount)));

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("GCS", storage.get("storageType").asString());
        assertEquals(serviceAccount, storage.get("gcsServiceAccount").asString());
        assertEquals(List.of("gs://demo-lake/warehouse/"), strings(storage.get("allowedLocations")));
        assertFalse(storage.has("tenantId"));
    }

    @Test
    void fileCatalogCarriesNoProviderFields() throws Exception {
        JsonNode created = createCatalog("file-catalog", """
                {"storageType":"FILE","allowedLocations":["file:///tmp/storage-config-wh"]}
                """);

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("FILE", storage.get("storageType").asString());
        assertEquals(List.of("file:///tmp/storage-config-wh"), strings(storage.get("allowedLocations")));
        assertFalse(storage.has("roleArn"), "FILE 配置不应带出 S3 字段: " + storage);
        assertFalse(storage.has("tenantId"), "FILE 配置不应带出 Azure 字段: " + storage);
        assertFalse(storage.has("gcsServiceAccount"), "FILE 配置不应带出 GCS 字段: " + storage);
    }

    /**
     * OBS / OSS 两个扩展类型的往返，并把「换存储类型」也放在这里验。
     *
     * <p>合并成一条是因为两者的字段高度相似（都只有 {@code endpoint} 一项定位），
     * 「换了类型但端点没跟着换」这类错误在单类型往返里看不出来。
     */
    @Test
    void obsAndOssCatalogsKeepTheirEndpointAcrossUpdates() throws Exception {
        JsonNode created = createCatalog("obs-catalog", """
                {"storageType":"OBS",
                 "allowedLocations":["obs://analytics-bucket/warehouse/"],
                 "endpoint":"obs.cn-north-4.myhuaweicloud.com"}
                """);
        int version = created.get("entityVersion").asInt();

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("OBS", storage.get("storageType").asString());
        assertEquals("obs.cn-north-4.myhuaweicloud.com", storage.get("endpoint").asString());
        assertEquals(List.of("obs://analytics-bucket/warehouse/"), strings(storage.get("allowedLocations")));
        assertFalse(storage.has("accessKeyId"), "OBS 配置不应带出 OSS 字段: " + storage);
        assertFalse(storage.has("roleArn"), "OBS 配置不应带出 S3 字段: " + storage);

        // 换成 OSS：类型、端点、位置三者一起换
        JsonNode updated = json(200, "PUT", M + "/catalogs/obs-catalog", """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"OSS",
                                      "allowedLocations":["oss://analytics-bucket/warehouse/"],
                                      "endpoint":"oss-cn-hangzhou.aliyuncs.com"}}
                """.formatted(version));

        JsonNode after = updated.get("storageConfigInfo");
        assertEquals("OSS", after.get("storageType").asString());
        assertEquals("oss-cn-hangzhou.aliyuncs.com", after.get("endpoint").asString());
        assertEquals("oss://analytics-bucket/warehouse/", strings(after.get("allowedLocations")).get(0));
        assertFalse(after.has("roleArn"), "换类型后不该混入 S3 字段: " + after);
    }

    // ------------------------------------------------------------------ 按类型校验

    /** {@code tenantId} 是规格里唯一一处 {@code required}，缺了必须拒。 */
    @Test
    void azureWithoutTenantIdIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"azure-bad","properties":{},
                 "storageConfigInfo":{"storageType":"AZURE","allowedLocations":["abfss://c@a/"]}}}
                """);
        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("tenantId"),
                "错误信息应指出缺的是 tenantId，实际: " + error);
    }

    @Test
    void blankAllowedLocationIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"blank-loc","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/ok","   "]}}}
                """);
        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("allowedLocations"),
                "错误信息应指出是 allowedLocations，实际: " + error);
    }

    /**
     * 规格里 {@code storageType} 是必填。缺了就不能猜——猜错会让配置落到完全不同的存储上。
     */
    @Test
    void missingStorageTypeIsRejected() throws Exception {
        json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"no-type","properties":{},
                 "storageConfigInfo":{"allowedLocations":["file:///tmp/wh"]}}}
                """);
    }

    @Test
    void unknownStorageTypeIsRejected() throws Exception {
        json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"hdfs-catalog","properties":{},
                 "storageConfigInfo":{"storageType":"HDFS","allowedLocations":["hdfs://nn/wh"]}}}
                """);
    }

    /**
     * 跨类型字段被忽略而不是报错。
     *
     * <p>这是有意的宽松：Spring Boot 默认关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
     * 全站都依赖这一点（请求体里带多余字段不会被拒）。为存储配置单独打开会变成
     * 「只有这个接口严格」的不一致行为。代价是拼错的字段名会被静默忽略，
     * 因此这里把它作为契约固定下来，避免以后被无意改成 400。
     */
    @Test
    void fieldsBelongingToAnotherStorageTypeAreIgnored() throws Exception {
        JsonNode created = createCatalog("mixed-catalog", """
                {"storageType":"FILE",
                 "allowedLocations":["file:///tmp/mixed-wh"],
                 "tenantId":"should-be-ignored",
                 "roleArn":"arn:aws:iam::1:role/ignored"}
                """);

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("FILE", storage.get("storageType").asString());
        assertFalse(storage.has("tenantId"), "FILE 不该接收 Azure 字段: " + storage);
        assertFalse(storage.has("roleArn"), "FILE 不该接收 S3 字段: " + storage);
    }

    // ------------------------------------------------------------------ 更新与数据面传导

    /**
     * 存储位置必须真的传导到数据面。
     *
     * <p>只断言「PUT 回显了新位置」是弱验证：投影列改了、仓库没改也能通过。
     * 因此这里在建库之后读它的 {@code location}——数据库位置由 catalog 的仓库推导，
     * 只有真的换了仓库才会落到新路径下。
     */
    @Test
    void switchingStorageMovesNewNamespacesToTheNewLocation() throws Exception {
        JsonNode created = createCatalog("move-catalog", """
                {"storageType":"FILE","allowedLocations":["file:///tmp/move-wh-a"]}
                """);
        int version = created.get("entityVersion").asInt();

        JsonNode before = json(200, "GET", "/v1/config?warehouse=move-catalog", null);
        assertEquals("file:///tmp/move-wh-a", before.get("defaults").get("warehouse").asString());

        json(200, "POST", "/v1/move-catalog/databases", "{\"name\":\"before_switch\",\"options\":{}}");
        JsonNode beforeDb = json(200, "GET", "/v1/move-catalog/databases/before_switch", null);
        assertEquals("file:///tmp/move-wh-a/before_switch.db", beforeDb.get("location").asString());

        // 换成 S3 存储：位置与类型应当一起变
        JsonNode updated = json(200, "PUT", M + "/catalogs/move-catalog", """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://moved-bucket/warehouse/"],
                                      "region":"us-west-2"}}
                """.formatted(version));

        JsonNode storage = updated.get("storageConfigInfo");
        assertEquals("S3", storage.get("storageType").asString());
        assertEquals("us-west-2", storage.get("region").asString());
        assertEquals(version + 1, updated.get("entityVersion").asInt());

        JsonNode after = json(200, "GET", "/v1/config?warehouse=move-catalog", null);
        assertEquals("s3://moved-bucket/warehouse/", after.get("defaults").get("warehouse").asString());

        json(200, "POST", "/v1/move-catalog/databases", "{\"name\":\"after_switch\",\"options\":{}}");
        JsonNode afterDb = json(200, "GET", "/v1/move-catalog/databases/after_switch", null);
        assertEquals("s3://moved-bucket/warehouse/after_switch.db", afterDb.get("location").asString());
    }

    /**
     * 同一类型内只改位置时，类型专属字段必须原样保留。
     *
     * <p>管理规格的 {@code PUT} 是**整体替换**：请求里没写 {@code roleArn}，
     * 语义上就是「置空」而不是「保持不变」。这里把这条语义固定下来，
     * 免得以后误以为它是 PATCH 而改错。
     */
    @Test
    void updatingStorageReplacesTheWholeConfiguration() throws Exception {
        JsonNode created = createCatalog("replace-catalog", """
                {"storageType":"S3",
                 "allowedLocations":["s3://bucket-a/warehouse/"],
                 "roleArn":"arn:aws:iam::1:role/r","region":"us-east-1"}
                """);
        int version = created.get("entityVersion").asInt();

        JsonNode updated = json(200, "PUT", M + "/catalogs/replace-catalog", """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3","allowedLocations":["s3://bucket-b/warehouse/"]}}
                """.formatted(version));

        JsonNode storage = updated.get("storageConfigInfo");
        assertEquals("s3://bucket-b/warehouse/", strings(storage.get("allowedLocations")).get(0));
        assertFalse(storage.has("roleArn"), "PUT 是整体替换，未给出的字段应被清空: " + storage);
        assertFalse(storage.has("region"), "PUT 是整体替换，未给出的字段应被清空: " + storage);
    }

    /** 只改 properties 时不能把存储配置冲掉。 */
    @Test
    void updatingPropertiesLeavesStorageUntouched() throws Exception {
        JsonNode created = createCatalog("keep-storage-catalog", """
                {"storageType":"GCS","allowedLocations":["gs://keep-bucket/warehouse/"],
                 "gcsServiceAccount":"{\\"project_id\\":\\"demo\\"}"}
                """);
        int version = created.get("entityVersion").asInt();

        JsonNode updated = json(200, "PUT", M + "/catalogs/keep-storage-catalog",
                "{\"currentEntityVersion\":" + version + ",\"properties\":{\"tier\":\"gold\"}}");

        JsonNode storage = updated.get("storageConfigInfo");
        assertNotNull(storage);
        assertEquals("GCS", storage.get("storageType").asString());
        assertEquals("{\"project_id\":\"demo\"}", storage.get("gcsServiceAccount").asString());
        assertEquals("gs://keep-bucket/warehouse/", strings(storage.get("allowedLocations")).get(0));
        assertEquals("gold", updated.get("properties").get("tier").asString());
    }

    /**
     * 没给 {@code storageConfigInfo} 时退回 FILE + 默认仓库。
     *
     * <p>规格把 {@code storageConfigInfo} 声明为必填，但服务端刻意保持宽松：
     * 既有调用方（以及 catalog 面的自动登记）都不带这个字段。
     * 这里固定住该兼容行为，同时要求位置有个确定值——否则表路径会拼出空串。
     */
    @Test
    void omittedStorageConfigDefaultsToFileWithConfiguredWarehouse() throws Exception {
        created.add("no-storage-catalog");
        JsonNode createdCatalog = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"no-storage-catalog","properties":{}}}
                """);

        JsonNode storage = createdCatalog.get("storageConfigInfo");
        assertEquals("FILE", storage.get("storageType").asString());
        assertFalse(strings(storage.get("allowedLocations")).isEmpty(),
                "应补上默认仓库位置: " + storage);

        JsonNode config = json(200, "GET", "/v1/config?warehouse=no-storage-catalog", null);
        assertEquals(strings(storage.get("allowedLocations")).get(0),
                config.get("defaults").get("warehouse").asString());
    }

    /** 空 {@code allowedLocations} 同样要补默认仓库，不能留下建不了表的 catalog。 */
    @Test
    void emptyAllowedLocationsFallBackToConfiguredWarehouse() throws Exception {
        JsonNode created = createCatalog("empty-locations", """
                {"storageType":"FILE","allowedLocations":[]}
                """);

        List<String> locations = strings(created.get("storageConfigInfo").get("allowedLocations"));
        assertEquals(1, locations.size(), "应补一条默认位置: " + created);
        assertFalse(locations.get(0).isBlank());
    }
}
