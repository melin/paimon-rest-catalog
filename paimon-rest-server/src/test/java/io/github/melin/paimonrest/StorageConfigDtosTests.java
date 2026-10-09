package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.dto.ManagementDtos;
import io.github.melin.paimonrest.dto.ManagementEnums.StorageType;
import io.github.melin.paimonrest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.support.Json;
import io.github.melin.paimonrest.support.StorageConfigs;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 存储配置判别联合的绑定测试。
 *
 * <p>这一层测的是「Java 模型与规格的判别约定是否对齐」，不涉及 HTTP 与数据库：
 * 子类型注册名与 {@link StorageType} 的对外取值是否一致、每种存储各自的字段能否
 * 正确往返、非法 {@code storageType} 是否被拒绝。这些都只在序列化层面才能验证，
 * 走 HTTP 的用例看不到。
 */
class StorageConfigDtosTests {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 子类型注册名硬编码在注解里，{@link StorageType} 的取值是另一处定义，
     * 两者一旦漂移，客户端发来的 {@code storageType} 就会解析失败。
     *
     * <p>这里用行为断言而不是读注解元数据：{@code @JsonSubTypes.Type} 同时有
     * {@code name} 与 {@code names} 两种写法，读数组会在只设单个名字时拿到空数组
     * （本测试第一版就踩了这个坑）。改成「每种类型序列化一次、再读回来」，
     * 验的是真正生效的注册名。
     */
    @Test
    void everyStorageTypeRoundTripsThroughItsOwnDiscriminator() {
        for (StorageType type : StorageType.values()) {
            StorageConfigInfo blank = StorageConfigs.blank(type, List.of("s3://b/", "file:///tmp/w"));

            String written = mapper.writeValueAsString(blank);
            assertTrue(written.contains("\"storageType\":\"" + type.wireName() + "\""),
                    type + " 的判别字段应写成 " + type.wireName() + "，实际: " + written);

            StorageConfigInfo parsed = mapper.readValue(written, StorageConfigInfo.class);
            assertEquals(type, parsed.storageType(),
                    "序列化后应读回同一类型，实际 " + parsed.getClass());
        }
    }

    @Test
    void s3ConfigCarriesItsOwnFieldsOnly() {
        String json = """
                {"storageType":"S3",
                 "allowedLocations":["s3://bucket/prefix/"],
                 "storageName":"named-s3",
                 "roleArn":"arn:aws:iam::123456789001:principal/abc",
                 "externalId":"external-id-1234",
                 "userArn":"arn:aws:iam::123456789001:user/abc",
                 "region":"us-east-2",
                 "endpoint":"https://s3.example.com:1234",
                 "stsEndpoint":"https://sts.example.com:1234",
                 "endpointInternal":"https://s3.internal.example.com:1234",
                 "pathStyleAccess":true,
                 "stsUnavailable":false,
                 "kmsUnavailable":false,
                 "encryptionKeys":["arn:aws:kms:us-east-1:1:key/a"],
                 "decryptionKeys":["arn:aws:kms:us-east-1:1:key/b"]}
                """;

        AwsStorageConfigInfo s3 = assertInstanceOf(AwsStorageConfigInfo.class,
                mapper.readValue(json, StorageConfigInfo.class));

        assertEquals(List.of("s3://bucket/prefix/"), s3.allowedLocations());
        assertEquals("named-s3", s3.storageName());
        assertEquals("arn:aws:iam::123456789001:principal/abc", s3.roleArn());
        assertEquals("external-id-1234", s3.externalId());
        assertEquals("arn:aws:iam::123456789001:user/abc", s3.userArn());
        assertEquals("us-east-2", s3.region());
        assertEquals("https://s3.example.com:1234", s3.endpoint());
        assertEquals("https://sts.example.com:1234", s3.stsEndpoint());
        assertEquals("https://s3.internal.example.com:1234", s3.endpointInternal());
        assertEquals(Boolean.TRUE, s3.pathStyleAccess());
        assertEquals(Boolean.FALSE, s3.stsUnavailable());
        assertEquals(Boolean.FALSE, s3.kmsUnavailable());
        assertEquals(List.of("arn:aws:kms:us-east-1:1:key/a"), s3.encryptionKeys());
        assertEquals(List.of("arn:aws:kms:us-east-1:1:key/b"), s3.decryptionKeys());
        assertEquals(StorageType.S3, s3.storageType());
    }

    @Test
    void azureConfigCarriesItsOwnFieldsOnly() {
        String json = """
                {"storageType":"AZURE",
                 "allowedLocations":["abfss://container@account.blob.core.windows.net/prefix/"],
                 "tenantId":"tenant-1",
                 "multiTenantAppName":"polaris-app",
                 "consentUrl":"https://login.microsoftonline.com/tenant-1/adminconsent",
                 "hierarchical":true}
                """;

        AzureStorageConfigInfo azure = assertInstanceOf(AzureStorageConfigInfo.class,
                mapper.readValue(json, StorageConfigInfo.class));

        assertEquals("tenant-1", azure.tenantId());
        assertEquals("polaris-app", azure.multiTenantAppName());
        assertEquals("https://login.microsoftonline.com/tenant-1/adminconsent", azure.consentUrl());
        assertEquals(Boolean.TRUE, azure.hierarchical());
        assertEquals(StorageType.AZURE, azure.storageType());
    }

    @Test
    void gcsConfigCarriesServiceAccount() {
        String json = """
                {"storageType":"GCS",
                 "allowedLocations":["gs://bucket/prefix/"],
                 "gcsServiceAccount":"{\\"type\\":\\"service_account\\",\\"project_id\\":\\"demo\\"}"}
                """;

        GcpStorageConfigInfo gcs = assertInstanceOf(GcpStorageConfigInfo.class,
                mapper.readValue(json, StorageConfigInfo.class));

        assertEquals("gs://bucket/prefix/", gcs.allowedLocations().get(0));
        assertEquals("{\"type\":\"service_account\",\"project_id\":\"demo\"}", gcs.gcsServiceAccount());
        assertEquals(StorageType.GCS, gcs.storageType());
    }

    @Test
    void fileConfigRoundTripsWithNothingButTheCommonFields() {
        FileStorageConfigInfo file = assertInstanceOf(FileStorageConfigInfo.class,
                mapper.readValue("{\"storageType\":\"FILE\",\"allowedLocations\":[\"file:///tmp/wh\"]}",
                        StorageConfigInfo.class));

        assertEquals(List.of("file:///tmp/wh"), file.allowedLocations());
        assertEquals(StorageType.FILE, file.storageType());

        String written = mapper.writeValueAsString(file);
        assertTrue(written.contains("\"storageType\":\"FILE\""), written);
        assertFalse(written.contains("roleArn"), "FILE 不应带出 S3 字段: " + written);
        assertFalse(written.contains("tenantId"), "FILE 不应带出 Azure 字段: " + written);
    }

    /**
     * OBS / OSS 是本工程的扩展子类型，注册名不在规格的 {@code discriminator.mapping} 里。
     * 它们最容易出的错不是解析失败，而是「两个都兼容 S3 的对象存储被写混」——
     * 所以除了往返，还要断言各自不把对方的字段带出来。
     */
    @Test
    void obsConfigCarriesEndpointAndItsOwnDiscriminator() {
        String json = """
                {"storageType":"OBS",
                 "allowedLocations":["obs://analytics-bucket/warehouse/"],
                 "storageName":"named-obs",
                 "endpoint":"obs.cn-north-4.myhuaweicloud.com",
                 "stsUnavailable":false}
                """;

        HuaweiObsStorageConfigInfo obs = assertInstanceOf(HuaweiObsStorageConfigInfo.class,
                mapper.readValue(json, StorageConfigInfo.class));

        assertEquals(List.of("obs://analytics-bucket/warehouse/"), obs.allowedLocations());
        assertEquals("named-obs", obs.storageName());
        assertEquals("obs.cn-north-4.myhuaweicloud.com", obs.endpoint());
        assertEquals(Boolean.FALSE, obs.stsUnavailable());
        assertEquals(StorageType.OBS, obs.storageType());

        String written = mapper.writeValueAsString(obs);
        assertTrue(written.contains("\"storageType\":\"OBS\""), written);
        assertFalse(written.contains("roleArn"), "OBS 不该带出 S3 字段: " + written);
        assertFalse(written.contains("tenantId"), "OBS 不该带出 Azure 字段: " + written);
        assertFalse(written.contains("gcsServiceAccount"), "OBS 不该带出 GCS 字段: " + written);
    }

    @Test
    void ossConfigCarriesEndpointAndItsOwnDiscriminator() {
        String json = """
                {"storageType":"OSS",
                 "allowedLocations":["oss://analytics-bucket/warehouse/"],
                 "storageName":"named-oss",
                 "endpoint":"oss-cn-hangzhou.aliyuncs.com"}
                """;

        AliyunOssStorageConfigInfo oss = assertInstanceOf(AliyunOssStorageConfigInfo.class,
                mapper.readValue(json, StorageConfigInfo.class));

        assertEquals("oss-cn-hangzhou.aliyuncs.com", oss.endpoint());
        assertEquals("named-oss", oss.storageName());
        assertEquals(StorageType.OSS, oss.storageType());

        String written = mapper.writeValueAsString(oss);
        assertTrue(written.contains("\"storageType\":\"OSS\""), written);
        assertFalse(written.contains("gcsServiceAccount"), "OSS 不该带出 GCS 字段: " + written);
    }

    /** 两个扩展子类型也要走一遍落库编解码——它们与规格内类型的差别只在注册名。 */
    @Test
    void extensionStorageConfigsSurvivePersistence() {
        AliyunOssStorageConfigInfo oss = new AliyunOssStorageConfigInfo(
                List.of("oss://analytics-bucket/warehouse/"), "named-oss",
                "oss-cn-hangzhou.aliyuncs.com", null, null, null);

        String stored = Json.write(oss);

        assertTrue(stored.contains("\"storageType\":\"OSS\""), stored);
        assertEquals(oss, Json.read(stored, StorageConfigInfo.class), "落库再读回应逐字段相等");

        HuaweiObsStorageConfigInfo obs = new HuaweiObsStorageConfigInfo(
                List.of("obs://analytics-bucket/warehouse/"), null,
                "obs.cn-north-4.myhuaweicloud.com", Boolean.TRUE, null, null);
        assertEquals(obs, Json.read(Json.write(obs), StorageConfigInfo.class));
    }

    /**
     * 静态凭据字段要能落库往返。
     *
     * <p>它记的是**库内形态**：{@code secretAccessKey} 在库里是密文，加解密是
     * {@code CredentialCipher} 的职责，本层只负责「这串字符不丢」。脱敏与合并规则
     * 由 {@code StorageConfigs} 与 {@code StaticCredentialApiTests} 覆盖。
     */
    @Test
    void staticCredentialFieldsSurvivePersistence() {
        AwsStorageConfigInfo s3 = new AwsStorageConfigInfo(
                List.of("s3://bucket/warehouse/"), null, null, null, null, null, null,
                "us-east-1", "http://minio.internal:9000", null, Boolean.TRUE,
                null, Boolean.TRUE, null, "minioadmin", "v1:c2VhbGVkLXNlY3JldA==");

        String stored = Json.write(s3);

        assertTrue(stored.contains("\"accessKeyId\":\"minioadmin\""), stored);
        assertTrue(stored.contains("\"secretAccessKey\""), stored);
        assertEquals(s3, Json.read(stored, StorageConfigInfo.class));

        // OBS / OSS 的字段与 S3 同名同义，各自往返一次
        HuaweiObsStorageConfigInfo obs = new HuaweiObsStorageConfigInfo(
                List.of("obs://b/warehouse/"), null, null, null, "obs-ak", "v1:obss");
        assertEquals(obs, Json.read(Json.write(obs), StorageConfigInfo.class));
        AliyunOssStorageConfigInfo oss = new AliyunOssStorageConfigInfo(
                List.of("oss://b/warehouse/"), null, null, null, "oss-ak", "v1:osss");
        assertEquals(oss, Json.read(Json.write(oss), StorageConfigInfo.class));

        // 没有这对字段的类型不该凭空长出它们
        String file = Json.write(new FileStorageConfigInfo(List.of("file:///tmp/wh"), null));
        assertFalse(file.contains("accessKeyId"), file);
        assertFalse(file.contains("secretAccessKey"), file);
    }

    /**
     * 判别字段必须恰好出现一次。
     *
     * <p>{@code StorageConfigInfo.storageType()} 是 Java 侧的类型访问器，
     * 若被当成普通属性序列化，报文里就会有两个 {@code storageType}——
     * 客户端按哪个取值不确定，是难查的故障。这里显式守住。
     */
    @Test
    void discriminatorIsWrittenExactlyOnce() {
        AwsStorageConfigInfo s3 = new AwsStorageConfigInfo(
                List.of("s3://bucket/"), "named", "arn:aws:iam::1:role/r",
                null, null, null, null, "us-east-1", null, null, null, null, null, null, null, null);

        String written = mapper.writeValueAsString(s3);
        int first = written.indexOf("\"storageType\"");
        assertTrue(first >= 0, written);
        assertEquals(-1, written.indexOf("\"storageType\"", first + 1),
                "storageType 只能出现一次: " + written);
        assertTrue(written.contains("\"storageType\":\"S3\""), written);
    }

    @Test
    void unknownStorageTypeIsRejected() {
        assertThrows(RuntimeException.class, () -> mapper.readValue(
                "{\"storageType\":\"HDFS\",\"allowedLocations\":[]}", StorageConfigInfo.class));
    }

    @Test
    void missingStorageTypeIsRejected() {
        assertThrows(RuntimeException.class, () -> mapper.readValue(
                "{\"allowedLocations\":[\"file:///tmp/wh\"]}", StorageConfigInfo.class));
    }

    /** 带判别字段的存储配置嵌在 catalog 里也要能往返。 */
    @Test
    void storageConfigInsideCatalogBindsPolymorphically() {
        String json = """
                {"type":"INTERNAL","name":"c1","properties":{"owner":"qa"},
                 "storageConfigInfo":{"storageType":"S3","allowedLocations":["s3://bucket/prefix/"],
                                      "roleArn":"arn:aws:iam::1:role/r","region":"ap-east-1"}}
                """;

        ManagementDtos.Catalog catalog = mapper.readValue(json, ManagementDtos.Catalog.class);
        AwsStorageConfigInfo s3 = assertInstanceOf(AwsStorageConfigInfo.class,
                catalog.storageConfigInfo());
        assertEquals("ap-east-1", s3.region());
        assertEquals("c1", catalog.name());
    }

    /**
     * 落库走的是 {@code Json}（内部编解码入口），不是 MVC 的消息转换器，
     * 两条路径的对象映射器配置不同。这里单独验一遍，
     * 否则「接口能接收但存不进库」这类问题要到集成测试才暴露。
     */
    @Test
    void persistenceRoundTripKeepsEveryStorageField() {
        AzureStorageConfigInfo azure = new AzureStorageConfigInfo(
                List.of("abfss://container@account.blob.core.windows.net/prefix/"),
                "named-azure", "tenant-1", "polaris-app",
                "https://login.microsoftonline.com/tenant-1/adminconsent", true);

        String stored = Json.write(azure);
        StorageConfigInfo restored = Json.read(stored, StorageConfigInfo.class);

        assertEquals(azure, restored, "落库再读回应逐字段相等");
        assertFalse(stored.contains("roleArn"), "Azure 配置不应混入 S3 字段: " + stored);
    }
}
