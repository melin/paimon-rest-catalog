package com.example.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.dto.ManagementEnums.StorageType;
import com.example.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import com.example.paimonrest.service.DefaultStorageCredentialManager;
import com.example.paimonrest.service.StorageCredentialCache;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.CredentialManagerType;
import com.example.paimonrest.support.FileIoType;
import com.example.paimonrest.support.VendedStorageCredential;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 存储凭据解析与缓存的纯单元测试，不起 Spring 上下文。
 *
 * <p>为什么把这一层单独测：HTTP 层的测试只能看到「最后返回的键有没有配对」，
 * 而真正容易写错的是取值优先级与边界——具名存储找不到时该失败还是该退回默认、
 * {@code stsUnavailable} 时该不该给密钥、哪些服务端专用地址不能发给客户端。
 * 这些都在这里逐条钉住，HTTP 层就只负责证明「这层被接上了」。
 *
 * <p>用真实的上限值构造 {@code RestServerProperties} 而不是 mock：
 * 这些配置项就是普通的可变对象，直接设值比 mock 掉取值链更能暴露绑定层面的问题。
 */
class StorageCredentialResolutionTests {

    private final RestServerProperties properties = new RestServerProperties();

    private final DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(properties);

    private StorageCredentialCache cache() {
        return new StorageCredentialCache(properties);
    }

    // ------------------------------------------------------------------ S3

    @Test
    void s3PrefersNamedStorageOverDefaultCredentials() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");
        namedKeys("warehouse-a", "AKIA-NAMED", "SECRET-NAMED");

        VendedStorageCredential vended = manager.vend(s3("s3://bucket-a/prefix", "warehouse-a"), "t1", "s3://bucket-a/prefix/t1");

        assertEquals("AKIA-NAMED", vended.token().get("s3.access-key-id"));
        assertEquals("SECRET-NAMED", vended.token().get("s3.secret-access-key"));
        assertEquals("named-storage:warehouse-a", vended.source());
        assertTrue(vended.namedStorage());
    }

    @Test
    void s3FallsBackToDefaultCredentialsWhenNoStorageName() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");

        VendedStorageCredential vended = manager.vend(s3("s3://bucket-a/prefix", null), "t1", null);

        assertEquals("AKIA-DEFAULT", vended.token().get("s3.access-key-id"));
        assertEquals(VendedStorageCredential.SOURCE_CONFIGURATION, vended.source());
    }

    @Test
    void s3FallsBackToEnvironmentCredentialChainWhenNothingConfigured() {
        VendedStorageCredential vended = manager.vend(s3("s3://bucket-a/prefix", null), "t1", null);

        assertFalse(vended.token().containsKey("s3.access-key-id"));
        assertFalse(vended.token().containsKey("s3.secret-access-key"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    /**
     * 具名存储写错时必须失败，不能退回默认凭据。
     *
     * <p>这是权限方向上的关键取舍：退回默认意味着「本来只能读某个桶的身份」
     * 被静默升级成「服务端默认身份」，一个拼写错误不该带来权限扩大。
     */
    @Test
    void s3FailsWhenStorageNameIsNotConfigured() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");

        ApiException failure = assertThrows(ApiException.class,
                () -> manager.vend(s3("s3://bucket-a/prefix", "typo-storage"), "t1", null));

        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("typo-storage"));
    }

    @Test
    void s3DoesNotVendSecretsWhenStsUnavailable() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");
        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://bucket-a/prefix"), null,
                null, null, null, null, null, "us-east-1", null, null,
                Boolean.TRUE, null, null, null);

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertFalse(vended.token().containsKey("s3.access-key-id"));
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        // 定位配置仍要下发：不下发密钥不等于让引擎不知道连哪里
        assertEquals("us-east-1", vended.token().get("s3.region"));
    }

    /**
     * 服务端专用地址与 assume 角色材料不得出现在下发结果里。
     *
     * <p>规格写明 {@code endpointInternal} 客户端永远看不到；{@code roleArn} 系列
     * 是服务端去换临时凭据的材料。发出去轻则引擎连到内网地址，
     * 重则把内网拓扑与账户结构泄露给调用方。
     */
    @Test
    void s3DoesNotLeakServerSideOnlyFields() {
        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://bucket-a/prefix"), null,
                "arn:aws:iam::123:role/r", "ext-id", "arn:aws:iam::123:user/u",
                List.of("arn:aws:kms:key/1"), List.of("arn:aws:kms:key/2"), "us-east-1",
                "https://s3.example.com", "https://sts.example.com", null,
                "https://s3.internal.example.com", Boolean.TRUE, null);

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertFalse(vended.token().containsKey("s3.sts-endpoint"));
        assertFalse(vended.token().containsKey("s3.endpoint-internal"));
        assertFalse(vended.token().containsKey("s3.role-arn"));
        assertFalse(vended.token().values().contains("https://s3.internal.example.com"));
        assertFalse(vended.token().values().contains("https://sts.example.com"));
        // 客户端该看到的三项
        assertEquals("us-east-1", vended.token().get("s3.region"));
        assertEquals("https://s3.example.com", vended.token().get("s3.endpoint"));
        assertEquals("true", vended.token().get("s3.path-style-access"));
    }

    // ------------------------------------------------------------------ Azure

    @Test
    void azureReturnsTenantAccountAndHierarchicalFlag() {
        AzureStorageConfigInfo config = new AzureStorageConfigInfo(
                List.of("abfss://container@myaccount.dfs.core.windows.net/prefix"), null,
                "tenant-1", "app-name", "https://consent.example.com", Boolean.TRUE);

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals(StorageType.AZURE, vended.storageType());
        assertEquals("tenant-1", vended.token().get("azure.tenant-id"));
        assertEquals("myaccount", vended.token().get("azure.account"));
        assertEquals("true", vended.token().get("azure.hierarchical"));
        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source());
        // 没有账户密钥就不该出现任何凭据类键
        assertFalse(vended.hasSecrets());
    }

    /**
     * 账户名只在认得出来的地址上解出。
     *
     * <p>自定义域名（内网代理、私有云）解不出账户名，此时不给这个键而不是给一个猜的名字——
     * 错的账户名会让引擎去认证一个不存在的账户，报出来的错与真正原因隔着好几层。
     */
    @Test
    void azureAccountIsParsedOnlyForKnownEndpointSuffixes() {
        AzureStorageConfigInfo classic = new AzureStorageConfigInfo(
                List.of("wasbs://container@myaccount.blob.core.windows.net/prefix"), null,
                "tenant-1", null, null, null);
        assertEquals("myaccount", manager.vend(classic, "t1", null).token().get("azure.account"));

        AzureStorageConfigInfo abfss = new AzureStorageConfigInfo(
                List.of("abfss://container@acct2.dfs.core.windows.net"), null,
                "tenant-1", null, null, null);
        assertEquals("acct2", manager.vend(abfss, "t1", null).token().get("azure.account"));

        AzureStorageConfigInfo custom = new AzureStorageConfigInfo(
                List.of("abfss://container@storage.internal.example/prefix"), null,
                "tenant-1", null, null, null);
        assertFalse(manager.vend(custom, "t1", null).token().containsKey("azure.account"));
    }

    // ------------------------------------------------------------------ GCS

    @Test
    void gcsVendsConfiguredTokenWithLifespan() {
        properties.getStorage().getGcp().setToken("ya29.fake-token");
        properties.getStorage().getGcp().setLifespan(Duration.ofMinutes(10));
        properties.getCredential().setTtlSeconds(3600);

        VendedStorageCredential vended = manager.vend(
                new GcpStorageConfigInfo(List.of("gs://bucket/prefix"), null, "svc@project.iam.gserviceaccount.com"),
                "t1", null);

        assertEquals("ya29.fake-token", vended.token().get("gcs.oauth2.token"));
        assertEquals("svc@project.iam.gserviceaccount.com", vended.token().get("gcs.service-account"));
        // lifespan 比 ttl 短，有效期必须收窄到 lifespan
        assertEquals(600, vended.ttlSeconds());
        assertNotNull(vended.token().get("gcs.oauth2.token-expires-at"));
    }

    @Test
    void gcsKeepsCredentialTtlWhenLifespanIsLonger() {
        properties.getStorage().getGcp().setToken("ya29.fake-token");
        properties.getStorage().getGcp().setLifespan(Duration.ofHours(5));
        properties.getCredential().setTtlSeconds(3600);

        VendedStorageCredential vended = manager.vend(
                new GcpStorageConfigInfo(List.of("gs://bucket/prefix"), null, null), "t1", null);

        assertEquals(3600, vended.ttlSeconds());
    }

    @Test
    void gcsWithoutConfiguredTokenFallsBackToEnvironment() {
        VendedStorageCredential vended = manager.vend(
                new GcpStorageConfigInfo(List.of("gs://bucket/prefix"), null, null), "t1", null);

        assertFalse(vended.token().containsKey("gcs.oauth2.token"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    // ------------------------------------------------------------------ FILE

    @Test
    void fileKeepsSelfContainedTokenAndTablePath() {
        VendedStorageCredential vended = manager.vend(
                new FileStorageConfigInfo(List.of("file:///tmp/wh"), null), "table-7", "file:///tmp/wh/db.db/table-7");

        assertEquals("PAIMON-table-7", vended.token().get("accessKeyId"));
        assertNotNull(vended.token().get("securityToken"));
        assertNotNull(vended.token().get("expiration"));
        assertEquals("file:///tmp/wh/db.db/table-7", vended.token().get("tablePath"));
        assertEquals(VendedStorageCredential.SOURCE_FILESYSTEM, vended.source());
    }

    @Test
    void missingStorageConfigIsAServerError() {
        ApiException failure = assertThrows(ApiException.class, () -> manager.vend(null, "t1", null));
        assertEquals(500, failure.getStatus());
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    void cacheReusesCredentialUntilItExpires() {
        StorageCredentialCache cache = cache();
        StorageConfigInfo storage = s3("s3://bucket-a/prefix", null);
        String key = StorageCredentialCache.key("catalog-1", storage, "s3://bucket-a/prefix");
        VendedStorageCredential vended =
                new VendedStorageCredential(java.util.Map.of("k", "v"), 60, StorageType.S3, "x");
        // 缓存按真实时钟判断过期，签发时刻因此必须取自同一个时钟
        long issuedAt = System.currentTimeMillis();

        StorageCredentialCache.Cached first = cache.put(key, vended, issuedAt);

        assertEquals(issuedAt + 60_000L, first.expiresAtMillis());
        assertEquals(1, cache.size());
        assertEquals(first.expiresAtMillis(), cache.get(key).orElseThrow().expiresAtMillis());
    }

    @Test
    void cacheKeyVariesWithStorageConfigAndPath() {
        StorageConfigInfo a = s3("s3://bucket-a/prefix", null);
        StorageConfigInfo b = s3("s3://bucket-b/prefix", null);

        assertFalse(StorageCredentialCache.key("c1", a, "p").equals(StorageCredentialCache.key("c2", a, "p")));
        assertFalse(StorageCredentialCache.key("c1", a, "p").equals(StorageCredentialCache.key("c1", b, "p")));
        assertFalse(StorageCredentialCache.key("c1", a, "p").equals(StorageCredentialCache.key("c1", a, "q")));
    }

    /** 超过上限时淘汰最久未使用的条目，而不是拒绝新增。 */
    @Test
    void cacheEvictsLeastRecentlyUsedBeyondMaxEntries() {
        properties.getStorageCredentialCache().setMaxEntries(2);
        StorageCredentialCache cache = cache();
        VendedStorageCredential vended =
                new VendedStorageCredential(java.util.Map.of("k", "v"), 600, StorageType.S3, "x");
        long issuedAt = System.currentTimeMillis();

        cache.put("a", vended, issuedAt);
        cache.put("b", vended, issuedAt);
        // 触碰 a，使 b 成为最久未使用
        assertTrue(cache.get("a").isPresent());
        cache.put("c", vended, issuedAt);

        assertEquals(2, cache.size());
        assertTrue(cache.get("a").isPresent());
        assertFalse(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
    }

    /** TTL 非正的凭据不入缓存，但调用方仍拿得到过期时刻。 */
    @Test
    void cacheSkipsNonPositiveTtl() {
        StorageCredentialCache cache = cache();
        VendedStorageCredential vended = new VendedStorageCredential(java.util.Map.of(), 0, StorageType.FILE, "x");

        StorageCredentialCache.Cached cached = cache.put("a", vended, 5_000L);

        assertEquals(0, cache.size());
        assertEquals(5_000L, cached.expiresAtMillis());
    }

    // ------------------------------------------------------------------ 策略取值

    @Test
    void fileIoTypeParsesAndRejectsUnknownValues() {
        assertEquals(FileIoType.DEFAULT, FileIoType.parse("DEFAULT").orElseThrow());
        assertEquals(FileIoType.LOCAL, FileIoType.parse(" local ").orElseThrow());
        assertTrue(FileIoType.parse("nope").isEmpty());

        // 未知取值必须启动即失败，且错误信息要列出合法取值
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> FileIoType.require("nope"));
        assertTrue(failure.getMessage().contains("s3"));
        assertTrue(failure.getMessage().contains("local"));
    }

    @Test
    void fileIoTypeRestrictsStorageTypes() {
        assertTrue(FileIoType.DEFAULT.supports(StorageType.AZURE));
        assertTrue(FileIoType.S3.supports(StorageType.S3));
        assertFalse(FileIoType.S3.supports(StorageType.AZURE));
        assertFalse(FileIoType.S3.supports(StorageType.GCS));
        // FILE 是本地的退路，任何取值下都保留
        for (FileIoType type : FileIoType.values()) {
            assertTrue(type.supports(StorageType.FILE), type + " must keep FILE available");
        }
        assertEquals(1, FileIoType.LOCAL.supportedStorageTypes().size());
        assertEquals(4, FileIoType.DEFAULT.supportedStorageTypes().size());
    }

    @Test
    void credentialManagerTypeRejectsUnknownValues() {
        assertEquals(CredentialManagerType.DEFAULT, CredentialManagerType.parse("default").orElseThrow());
        assertEquals(CredentialManagerType.NOOP, CredentialManagerType.parse("NOOP").orElseThrow());
        assertTrue(CredentialManagerType.parse("off").isEmpty());

        // 拼错的 noop 不能退回 default：那会让本该停发凭据的部署继续发
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> CredentialManagerType.require("nooop"));
        assertTrue(failure.getMessage().contains("noop"));
        assertTrue(failure.getMessage().contains("default"));
    }

    // ------------------------------------------------------------------ 辅助

    private void setDefaultAwsKeys(String accessKey, String secretKey) {
        properties.getStorage().getAws().setAccessKey(accessKey);
        properties.getStorage().getAws().setSecretKey(secretKey);
    }

    private void namedKeys(String name, String accessKey, String secretKey) {
        RestServerProperties.Storage.Keys keys = new RestServerProperties.Storage.Keys();
        keys.setAccessKey(accessKey);
        keys.setSecretKey(secretKey);
        properties.getStorage().getAws().getStorages().put(name, keys);
    }

    private static AwsStorageConfigInfo s3(String location, String storageName) {
        return new AwsStorageConfigInfo(new ArrayList<>(List.of(location)), storageName,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
