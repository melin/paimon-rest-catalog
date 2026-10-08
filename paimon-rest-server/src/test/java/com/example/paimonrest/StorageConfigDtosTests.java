package com.example.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.paimonrest.dto.ManagementDtos;
import com.example.paimonrest.dto.ManagementEnums.StorageType;
import com.example.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import com.example.paimonrest.support.Json;
import com.example.paimonrest.support.StorageConfigs;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 存储配置判别联合的绑定测试。
 *
 * <p>这一层测的是「Java 模型与规格的判别约定是否对齐」，不涉及 HTTP 与数据库：
 * 子类型注册名与 {@link StorageType} 的对外取值是否一致、四种存储各自的字段能否
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
                null, null, null, null, "us-east-1", null, null, null, null, null, null);

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
