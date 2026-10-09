package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.support.CredentialCipher;
import io.github.melin.paimonrest.support.StorageConfigs;
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
 * 用户填写的静态凭据在 HTTP 层的验收。
 *
 * <p>这一层要证明的不是「字段能解析」——那是 {@code StorageConfigDtosTests} 的事——
 * 而是三条只有走完整链路才看得见的约定：
 *
 * <ol>
 *   <li><b>密钥永不回显。</b>创建、读取、列表三处响应里都不出现明文，
 *       库里存的也不是明文（能解开的密文才算数）；
 *   <li><b>密钥省略即保持。</b>{@code PUT} 对其余字段是整体替换，唯独只写不读的密钥
 *       不能被替换掉——否则「改个 endpoint」会顺手清掉密钥，而失败会推迟到引擎读写数据时；
 *   <li><b>它真的被下发。</b>读表凭据接口回来的必须是 catalog 里那把钥匙，
 *       而不是服务端配置的默认凭据。
 * </ol>
 *
 * <p>加密密钥通过 {@code properties} 注入而非改 application.yml：这个上下文与其它测试
 * 互不影响，也不会给生产配置留下测试密钥。未配置密钥时的行为由
 * {@code StaticCredentialDisabledApiTests} 单独覆盖。
 */
@SpringBootTest(properties = {
        "paimon.rest.storage.credential-secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "paimon.rest.storage.aws.access-key=AKIA-CONFIG",
        "paimon.rest.storage.aws.secret-key=SECRET-CONFIG",
        // 控制台端点默认要求先登录（console.required=true），元数据也不例外；
        // 登记一个静态令牌，省得在一条「顺便验一下元数据」的用例里走一遍登录
        "paimon.rest.auth.tokens[0]=static-cred-token"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StaticCredentialApiTests {

    private static final String M = "/api/management/v1";

    private static final String TABLE = "orders";

    private static final String CONSOLE_TOKEN = "static-cred-token";

    private static final String ACCESS_KEY_ID = "AKIA-USER-SUPPLIED";

    private static final String SECRET = "USER-SUPPLIED-SECRET-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private CredentialCipher cipher;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            call(204, delete(M + "/catalogs/" + name));
        }
        created.clear();
    }

    // ------------------------------------------------------------------ 回显与落库

    @Test
    void createsCatalogWithStaticCredentialsWithoutEchoingTheSecret() throws Exception {
        String prefix = "static-s3";
        JsonNode created = createCatalog(prefix, staticS3Config());

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("S3", storage.get("storageType").asString());
        assertEquals(ACCESS_KEY_ID, storage.get("accessKeyId").asString(), "accessKeyId 不是秘密，用于显示");
        assertFalse(storage.has("secretAccessKey"), "响应里不应出现 secretAccessKey: " + storage);
        assertFalse(created.toString().contains(SECRET), "响应体任何位置都不应出现明文密钥");

        // 重新读取与列表：同样不回显
        JsonNode fetched = json(200, "GET", M + "/catalogs/" + prefix, null);
        assertFalse(fetched.toString().contains(SECRET), "单查也不应回显明文密钥");
        assertFalse(fetched.get("storageConfigInfo").has("secretAccessKey"));
        assertFalse(json(200, "GET", M + "/catalogs", null).toString().contains(SECRET),
                "列表更不应该回显明文密钥");

        // 库里存的是密文，且能解开
        StorageConfigInfo stored = storedConfig(prefix);
        AwsStorageConfigInfo s3 = (AwsStorageConfigInfo) stored;
        assertTrue(CredentialCipher.sealed(s3.secretAccessKey()),
                "落库必须是密文，实际: " + s3.secretAccessKey());
        assertFalse(s3.secretAccessKey().contains(SECRET), "密文里不应出现明文");
        assertEquals(SECRET, cipher.reveal(s3.secretAccessKey()));
    }

    /** 端到端：读表凭据接口下发的必须是 catalog 里那把钥匙，而不是服务端配置的默认凭据。 */
    @Test
    void vendsCatalogStaticCredentialsInsteadOfServerConfiguration() throws Exception {
        String prefix = "static-s3-vend";
        createCatalog(prefix, staticS3Config());
        seedTable(prefix);

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");

        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString());
        // 定位配置照旧下发，静态凭据不改变这一点
        assertEquals("us-east-1", token.get("s3.region").asString());
        assertEquals("http://minio.internal:9000", token.get("s3.endpoint").asString());
        assertEquals("true", token.get("s3.path-style-access").asString());
    }

    // ------------------------------------------------------------------ 更新语义

    /**
     * 省略密钥即保持（{@code PUT} 整体替换的唯一例外）。
     *
     * <p>用「再下发一次凭据」验证，而不是只读回响应：响应里本来就没有密钥，
     * 只断言响应等于没断言。
     */
    @Test
    void omittedSecretKeepsTheStoredOneAcrossUpdates() throws Exception {
        String prefix = "static-s3-keep";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        // 只改端点，并原样回显 accessKeyId（读-改-写的常见形态）
        JsonNode updated = json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"eu-west-1",
                                      "endpoint":"http://minio2.internal:9000",
                                      "pathStyleAccess":true,
                                      "accessKeyId":"%s"}}
                """.formatted(version, ACCESS_KEY_ID));

        assertEquals("eu-west-1", updated.get("storageConfigInfo").get("region").asString());
        assertFalse(updated.toString().contains(SECRET), "更新响应也不应回显明文密钥");

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString(),
                "省略 secretAccessKey 应保持库中原值");
        assertEquals("http://minio2.internal:9000", token.get("s3.endpoint").asString());
    }

    /** 换端点、换区域都不该动密钥；换 accessKeyId 则必须连密钥一起给（见下一条）。 */
    @Test
    void updatingWithoutAnyCredentialFieldKeepsWhatIsStored() throws Exception {
        String prefix = "static-s3-untouched";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "pathStyleAccess":true}}
                """.formatted(version));

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString());
    }

    /** 一对空串是显式清除：之后回到服务端配置的取密钥路径。 */
    @Test
    void emptyCredentialPairClearsStaticCredentials() throws Exception {
        String prefix = "static-s3-clear";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        JsonNode updated = json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "accessKeyId":"",
                                      "secretAccessKey":""}}
                """.formatted(version));

        assertFalse(updated.get("storageConfigInfo").has("accessKeyId"), "清除后不应再给出 accessKeyId");
        assertFalse(StorageConfigs.hasStaticCredentials(storedConfig(prefix)), "库里不应再留有凭据");

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals("AKIA-CONFIG", token.get("s3.access-key-id").asString(),
                "清除后应退回服务端配置的默认凭据");
        assertEquals("SECRET-CONFIG", token.get("s3.secret-access-key").asString());
    }

    // ------------------------------------------------------------------ 校验

    /** 只给密钥不给 ID：没有确定语义，拒绝。 */
    @Test
    void secretWithoutAccessKeyIdIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-bad-1","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "secretAccessKey":"only-secret"}}}
                """);

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("accessKeyId"),
                "错误信息应指出该字段: " + error);
    }

    /**
     * 换了 accessKeyId 却没给新密钥：拒绝。
     *
     * <p>这类请求多半来自「想轮换密钥但不知道服务端不回显」，静默接受会得到一个
     * 「新 ID 配旧密钥」的组合，认证失败现场在引擎侧，排查成本很高。
     */
    @Test
    void changingAccessKeyIdWithoutSecretIsRejected() throws Exception {
        String prefix = "static-s3-rotate";
        JsonNode created = createCatalog(prefix, staticS3Config());
        int version = created.get("entityVersion").asInt();

        JsonNode error = json(400, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "endpoint":"http://minio.internal:9000",
                                      "accessKeyId":"AKIA-ROTATED"}}
                """.formatted(version));

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("secretAccessKey"),
                "错误信息应指出缺的是密钥: " + error);
    }

    @Test
    void emptyAccessKeyIdWithSecretIsRejected() throws Exception {
        json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-bad-2","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "accessKeyId":"","secretAccessKey":"half-a-pair"}}}
                """);
    }

    /** 两把钥匙不能同时出现：具名存储与静态凭据都在回答「用哪一组密钥」。 */
    @Test
    void staticCredentialsAndNamedStorageAreMutuallyExclusive() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-bad-3","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "storageName":"named-a",
                                      "accessKeyId":"AKIA-X","secretAccessKey":"SECRET-X"}}}
                """);

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("storageName"),
                "错误信息应点出两个互斥字段: " + error);
    }

    /** OBS / OSS 复用同一套字段与语义，别只给 S3 接上。 */
    @Test
    void obsAndOssAlsoAcceptStaticCredentials() throws Exception {
        createCatalog("static-obs", """
                {"storageType":"OBS",
                 "allowedLocations":["obs://analytics-bucket/warehouse/"],
                 "endpoint":"obs.cn-north-4.myhuaweicloud.com",
                 "accessKeyId":"OBS-AK","secretAccessKey":"OBS-SK"}
                """);
        createCatalog("static-oss", """
                {"storageType":"OSS",
                 "allowedLocations":["oss://analytics-bucket/warehouse/"],
                 "endpoint":"oss-cn-hangzhou.aliyuncs.com",
                 "accessKeyId":"OSS-AK","secretAccessKey":"OSS-SK"}
                """);

        JsonNode obs = json(200, "GET", M + "/catalogs/static-obs", null).get("storageConfigInfo");
        assertEquals("OBS-AK", obs.get("accessKeyId").asString());
        assertFalse(obs.has("secretAccessKey"));
        HuaweiObsStorageConfigInfo storedObs = (HuaweiObsStorageConfigInfo) storedConfig("static-obs");
        assertTrue(CredentialCipher.sealed(storedObs.secretAccessKey()));

        JsonNode oss = json(200, "GET", M + "/catalogs/static-oss", null).get("storageConfigInfo");
        assertEquals("OSS-AK", oss.get("accessKeyId").asString());
        assertFalse(oss.has("secretAccessKey"));
    }

    /** 控制台元数据要如实报告「本部署能保存静态凭据」，否则表单一律禁用输入。 */
    @Test
    void metaReportsStaticCredentialsAsEnabled() throws Exception {
        JsonNode enums = metaEnums();

        assertTrue(enums.get("staticCredentialsEnabled").asBoolean(),
                "配了加密密钥就应报告为可用: " + enums);
    }

    // ------------------------------------------------------------------ 辅助

    private JsonNode metaEnums() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/console/v1/meta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CONSOLE_TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body).get("enums");
    }

    /** 一段带静态凭据的 S3 配置：S3 兼容对象存储的典型形态（自定义端点 + 路径风格 + 无 STS）。 */
    private static String staticS3Config() {
        return """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"us-east-1",
                 "endpoint":"http://minio.internal:9000",
                 "pathStyleAccess":true,
                 "stsUnavailable":true,
                 "accessKeyId":"%s",
                 "secretAccessKey":"%s"}
                """.formatted(ACCESS_KEY_ID, SECRET);
    }

    private JsonNode createCatalog(String name, String storageConfigInfo) throws Exception {
        created.add(name);
        return json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":%s}}
                """.formatted(name, storageConfigInfo));
    }

    /** 直接读库，验「落库的形态」而不是「接口回显了什么」。 */
    private StorageConfigInfo storedConfig(String prefix) {
        CatalogEntity entity = catalogRepository.findByPrefix(prefix).orElseThrow();
        StorageConfigInfo config = entity.getStorageConfig();
        assertNotNull(config, "存储配置应随 catalog 一起落库");
        return config;
    }

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
