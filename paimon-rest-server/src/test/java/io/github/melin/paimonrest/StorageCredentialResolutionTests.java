package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.dto.ManagementEnums.StorageType;
import io.github.melin.paimonrest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.service.DefaultStorageCredentialManager;
import io.github.melin.paimonrest.service.StorageCredentialCache;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.CredentialCipher;
import io.github.melin.paimonrest.support.CredentialManagerType;
import io.github.melin.paimonrest.support.FileIoType;
import io.github.melin.paimonrest.support.StorageConfigs;
import io.github.melin.paimonrest.support.VendedStorageCredential;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** base64 的 32 字节：静态凭据在库里是密文，本类要用真密钥把它解出来。 */
    private static final String CREDENTIAL_SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = configuredCipher(properties);

    private final DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(properties, cipher);

    /**
     * 配好密钥的 cipher。
     *
     * <p>不配密钥时 {@code seal} 会拒绝——这正是写路径想要的失败方向，
     * 但本类测的是下发优先级，需要能把凭据密封进配置里。密钥与密文的对应关系
     * 由 {@code CredentialCipherTests} 单独守住，这里只把它当工具用。
     */
    private static CredentialCipher configuredCipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(CREDENTIAL_SECRET_KEY);
        return new CredentialCipher(properties);
    }

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
                Boolean.TRUE, null, null, null, null, null);

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
                "https://s3.internal.example.com", Boolean.TRUE, null, null, null);

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

    // ------------------------------------------------------------------ 静态凭据

    /**
     * catalog 自带的静态凭据优先于服务端配置的一切来源。
     *
     * <p>同时配好默认凭据与具名存储，才能证明这一级真的排在最前——
     * 只配一个时，取值相同也可能是别的分支命中的。
     */
    @Test
    void s3PrefersCatalogStaticCredentialsOverEveryServerSideSource() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");
        namedKeys("warehouse-a", "AKIA-NAMED", "SECRET-NAMED");
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix", "warehouse-a",
                "AKIA-CATALOG", cipher.seal("SECRET-CATALOG"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals("AKIA-CATALOG", vended.token().get("s3.access-key-id"));
        assertEquals("SECRET-CATALOG", vended.token().get("s3.secret-access-key"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertTrue(vended.hasSecrets(), "静态凭据属于「真的下发了密钥」");
        assertFalse(vended.namedStorage());
    }

    /**
     * {@code stsUnavailable} 不阻断静态凭据的下发。
     *
     * <p>这条是「兼容 S3 的对象存储能不能用起来」的关键：MinIO / Ceph / Ozone / FlashBlade
     * 这类存储没有 STS，配置里照惯例会写 {@code stsUnavailable: true}。
     * 若它优先于静态凭据，用户填了 AK/SK 却收不到，现象是「功能好像没生效」——
     * 而 {@code stsUnavailable} 表达的是「服务端不去向云申请临时凭据」，
     * 与「下发用户自己填进来的长期密钥」是两件事。定位配置照旧下发。
     */
    @Test
    void s3StaticCredentialsSurviveStsUnavailable() {
        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://minio-bucket/wh"), null,
                null, null, null, null, null, "us-east-1", "http://minio.internal:9000", null,
                Boolean.TRUE, null, Boolean.TRUE, null,
                "minioadmin", cipher.seal("minioadmin-secret"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals("minioadmin", vended.token().get("s3.access-key-id"));
        assertEquals("minioadmin-secret", vended.token().get("s3.secret-access-key"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertEquals("http://minio.internal:9000", vended.token().get("s3.endpoint"));
        assertEquals("true", vended.token().get("s3.path-style-access"));
    }

    /** OBS 用自己那套键名族下发静态凭据，不能串到 S3 或 OSS 的键上。 */
    @Test
    void obsVendsStaticCredentialsWithItsOwnKeyFamily() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");
        HuaweiObsStorageConfigInfo config = obsConfig("obs://analytics-bucket/warehouse/", null,
                "obs.cn-north-4.myhuaweicloud.com", "OBS-AK", cipher.seal("OBS-SK"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals("OBS-AK", vended.token().get("fs.obs.access.key"));
        assertEquals("OBS-SK", vended.token().get("fs.obs.secret.key"));
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertFalse(hasAnyKeyStartingWith(vended.token(), "s3."), "OBS 不应下发 s3.* 键: " + vended.token());
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.oss."), "OBS 不应下发 OSS 键: " + vended.token());
    }

    /** OSS 同理，且键名是驼峰那一套。 */
    @Test
    void ossVendsStaticCredentialsWithItsOwnKeyFamily() {
        AliyunOssStorageConfigInfo config = ossConfig("oss://analytics-bucket/warehouse/", null,
                "oss-cn-hangzhou.aliyuncs.com", "OSS-AK", cipher.seal("OSS-SK"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals("OSS-AK", vended.token().get("fs.oss.accessKeyId"));
        assertEquals("OSS-SK", vended.token().get("fs.oss.accessKeySecret"));
        assertEquals("oss-cn-hangzhou.aliyuncs.com", vended.token().get("fs.oss.endpoint"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.obs."), "OSS 不应下发 OBS 键: " + vended.token());
    }

    /**
     * 密文解不开时直接失败，不退回服务端默认凭据。
     *
     * <p>退回的后果是把「这个 catalog 该用哪把钥匙」从调用方明确指定降级成部署方默认——
     * 一次加密密钥轮换会静默变成权限扩大，而且真因（换过密钥）被掩盖成「权限不对」。
     */
    @Test
    void staticCredentialsFailLoudlyWhenTheyCannotBeDecrypted() {
        setDefaultAwsKeys("AKIA-DEFAULT", "SECRET-DEFAULT");
        RestServerProperties other = new RestServerProperties();
        other.getStorage().setCredentialSecretKey("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix", null,
                "AKIA-CATALOG", new CredentialCipher(other).seal("SECRET-CATALOG"));

        ApiException failure = assertThrows(ApiException.class, () -> manager.vend(config, "t1", null));

        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("credential-secret-key"), failure.getMessage());
    }

    /** 明文混进配置里（历史数据或手改）同样拒绝，不把它当密钥发出去。 */
    @Test
    void staticCredentialsRejectUnsealedSecrets() {
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix", null, "AKIA-CATALOG", "plaintext-secret");

        assertEquals(500, assertThrows(ApiException.class, () -> manager.vend(config, "t1", null)).getStatus());
    }

    /** 取凭据的读取点与脱敏点分工：对外那份必须丢掉密钥，内部那份才解得出明文。 */
    @Test
    void maskingKeepsTheKeyIdAndDropsTheSecret() {
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix", null,
                "AKIA-CATALOG", cipher.seal("SECRET-CATALOG"));

        AwsStorageConfigInfo masked = (AwsStorageConfigInfo) StorageConfigs.withoutSecrets(config);

        assertEquals("AKIA-CATALOG", masked.accessKeyId(), "accessKeyId 不是秘密，保留给控制台显示");
        assertNull(masked.secretAccessKey(), "对外那份不应带出密文");
        assertEquals(List.of("s3://bucket-a/prefix"), masked.allowedLocations(), "脱敏不应动其他字段");
        // 内部那份（库中原样）才解得出明文
        assertEquals("SECRET-CATALOG",
                StorageConfigs.staticCredentials(config, cipher).secretAccessKey());
        assertEquals("AKIA-CATALOG", StorageConfigs.staticCredentials(config, cipher).accessKeyId());
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

    // ------------------------------------------------------------------ OBS

    /**
     * 华为 OBS 下发的必须是 OBS 自己的键名族。
     *
     * <p>这一条断言的是「键名本身」，不是「有没有返回值」。OBS 与 OSS 都兼容 S3 协议，
     * 用 {@code s3.*} 或对方的键名去配，调用链上没有任何一处会报错——引擎只是静默地
     * 拿不到凭据，然后在第一次读数据时以一个看似无关的文件系统异常失败。
     */
    @Test
    void obsVendsItsOwnKeyFamilyIncludingSessionToken() {
        RestServerProperties.Storage.CloudCredentials obs = properties.getStorage().getObs();
        obs.setAccessKey("OBS-AK");
        obs.setSecretKey("OBS-SK");
        obs.setSessionToken("OBS-SESSION-TOKEN");

        VendedStorageCredential vended = manager.vend(
                obsConfig("obs://analytics/warehouse", null, "obs.cn-north-4.myhuaweicloud.com"), "t1", null);

        assertEquals("OBS-AK", vended.token().get("fs.obs.access.key"));
        assertEquals("OBS-SK", vended.token().get("fs.obs.secret.key"));
        assertEquals("OBS-SESSION-TOKEN", vended.token().get("fs.obs.session.token"));
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
        assertEquals(VendedStorageCredential.SOURCE_CONFIGURATION, vended.source());
        assertTrue(vended.hasSecrets());

        // 不得混入另外两套键名族
        assertFalse(hasAnyKeyStartingWith(vended.token(), "s3."));
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.oss."));
    }

    /** 长期凭据没有安全令牌，此时不该出现一个空值的 session token 键。 */
    @Test
    void obsOmitsSessionTokenForLongTermCredentials() {
        properties.getStorage().getObs().setAccessKey("OBS-AK");
        properties.getStorage().getObs().setSecretKey("OBS-SK");

        VendedStorageCredential vended =
                manager.vend(obsConfig("obs://analytics/warehouse", null, null), "t1", null);

        assertFalse(vended.token().containsKey("fs.obs.session.token"));
        assertEquals("OBS-AK", vended.token().get("fs.obs.access.key"));
    }

    /**
     * 具名存储优先，且安全令牌跟着它所属的那一级走。
     *
     * <p>临时凭据的 AK、SK、令牌是一次签发的同一组，混搭（具名存储的 AK/SK 配默认配置的
     * 令牌）只会得到一份签名不过的组合，不如让两级各自成套。
     */
    @Test
    void obsPrefersNamedStorageTogetherWithItsOwnSessionToken() {
        RestServerProperties.Storage.CloudCredentials obs = properties.getStorage().getObs();
        obs.setAccessKey("OBS-DEFAULT-AK");
        obs.setSecretKey("OBS-DEFAULT-SK");
        obs.setSessionToken("OBS-DEFAULT-TOKEN");
        namedObsKeys("warehouse-a", "OBS-NAMED-AK", "OBS-NAMED-SK", "OBS-NAMED-TOKEN");

        VendedStorageCredential vended =
                manager.vend(obsConfig("obs://analytics/warehouse", "warehouse-a", null), "t1", null);

        assertEquals("OBS-NAMED-AK", vended.token().get("fs.obs.access.key"));
        assertEquals("OBS-NAMED-TOKEN", vended.token().get("fs.obs.session.token"));
        assertEquals("named-storage:warehouse-a", vended.source());
    }

    /** 错误信息要指向 OBS 那一组配置，否则运维会去翻 aws.storages 找半天。 */
    @Test
    void obsFailsWhenStorageNameIsNotConfigured() {
        properties.getStorage().getObs().setAccessKey("OBS-AK");
        properties.getStorage().getObs().setSecretKey("OBS-SK");

        ApiException failure = assertThrows(ApiException.class, () -> manager.vend(
                obsConfig("obs://analytics/warehouse", "typo-storage", null), "t1", null));

        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("typo-storage"));
        assertTrue(failure.getMessage().contains("paimon.rest.storage.obs.storages"), failure.getMessage());
    }

    /** 声明不下发凭据时端点仍要发：引擎得知道连哪里，它可能自带 ECS 委托身份。 */
    @Test
    void obsKeepsEndpointWhenStsUnavailable() {
        properties.getStorage().getObs().setAccessKey("OBS-AK");
        properties.getStorage().getObs().setSecretKey("OBS-SK");

        VendedStorageCredential vended = manager.vend(
                new HuaweiObsStorageConfigInfo(List.of("obs://analytics/warehouse"), null,
                        "obs.cn-north-4.myhuaweicloud.com", Boolean.TRUE, null, null),
                "t1", null);

        assertFalse(vended.token().containsKey("fs.obs.access.key"));
        assertFalse(vended.hasSecrets());
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
    }

    @Test
    void obsFallsBackToEnvironmentWhenNothingConfigured() {
        VendedStorageCredential vended = manager.vend(
                obsConfig("obs://analytics/warehouse", null, "obs.cn-north-4.myhuaweicloud.com"), "t1", null);

        assertFalse(vended.token().containsKey("fs.obs.access.key"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
        // 没有凭据不等于没有端点
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
    }

    // ------------------------------------------------------------------ OSS

    /**
     * 阿里云 OSS 的键名族与 OBS 不同名：{@code accessKeyId} 是驼峰，
     * 临时凭据叫 {@code securityToken} 而不是 {@code session.token}。
     *
     * <p>这两套命名风格的差异是真实存在的（分别来自 {@code hadoop-aliyun} 与
     * {@code hadoop-huaweicloud}），不是笔误。按「两边应该长得一样」去改，
     * 会让其中一侧静默失效。
     */
    @Test
    void ossVendsCamelCaseKeysAndSecurityToken() {
        RestServerProperties.Storage.CloudCredentials oss = properties.getStorage().getOss();
        oss.setAccessKey("OSS-AK");
        oss.setSecretKey("OSS-SK");
        oss.setSessionToken("OSS-STS-TOKEN");

        VendedStorageCredential vended = manager.vend(
                ossConfig("oss://analytics/warehouse", null, "oss-cn-hangzhou.aliyuncs.com"), "t1", null);

        assertEquals("OSS-AK", vended.token().get("fs.oss.accessKeyId"));
        assertEquals("OSS-SK", vended.token().get("fs.oss.accessKeySecret"));
        assertEquals("OSS-STS-TOKEN", vended.token().get("fs.oss.securityToken"));
        assertEquals("oss-cn-hangzhou.aliyuncs.com", vended.token().get("fs.oss.endpoint"));
        assertEquals(VendedStorageCredential.SOURCE_CONFIGURATION, vended.source());
        assertTrue(vended.hasSecrets());

        assertFalse(hasAnyKeyStartingWith(vended.token(), "s3."));
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.obs."));
    }

    /**
     * 两家的凭据互不可见。
     *
     * <p>它们是独立签发、独立授权的密钥，配了一家的不该让另一家的 catalog 也拿到——
     * 否则「给 OBS 配了钥匙」会顺带把权限扩到 OSS 上，而这正是把配置组分开要防的事。
     */
    @Test
    void obsCredentialsAreNotVisibleToOssCatalogs() {
        properties.getStorage().getObs().setAccessKey("OBS-AK");
        properties.getStorage().getObs().setSecretKey("OBS-SK");

        VendedStorageCredential vended =
                manager.vend(ossConfig("oss://analytics/warehouse", null, null), "t1", null);

        assertFalse(vended.hasSecrets());
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    @Test
    void ossFailsWhenStorageNameIsNotConfigured() {
        properties.getStorage().getOss().setAccessKey("OSS-AK");
        properties.getStorage().getOss().setSecretKey("OSS-SK");

        ApiException failure = assertThrows(ApiException.class, () -> manager.vend(
                ossConfig("oss://analytics/warehouse", "typo-storage", null), "t1", null));

        assertTrue(failure.getMessage().contains("paimon.rest.storage.oss.storages"), failure.getMessage());
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
        assertEquals(6, FileIoType.DEFAULT.supportedStorageTypes().size());
        // OBS / OSS 各自只放开自己那一种云存储，不是「所有对象存储」
        assertTrue(FileIoType.OBS.supports(StorageType.OBS));
        assertFalse(FileIoType.OBS.supports(StorageType.OSS));
        assertFalse(FileIoType.OBS.supports(StorageType.S3));
        assertTrue(FileIoType.OSS.supports(StorageType.OSS));
        assertFalse(FileIoType.OSS.supports(StorageType.OBS));
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
        return s3(location, storageName, null, null);
    }

    /** 带静态凭据的 S3 配置。{@code sealedSecret} 是库内的形态，明文由本类自行密封。 */
    private static AwsStorageConfigInfo s3(String location, String storageName,
                                           String accessKeyId, String sealedSecret) {
        return new AwsStorageConfigInfo(new ArrayList<>(List.of(location)), storageName,
                null, null, null, null, null, null, null, null, null, null, null, null,
                accessKeyId, sealedSecret);
    }

    private void namedObsKeys(String name, String accessKey, String secretKey, String sessionToken) {
        RestServerProperties.Storage.Keys keys = new RestServerProperties.Storage.Keys();
        keys.setAccessKey(accessKey);
        keys.setSecretKey(secretKey);
        keys.setSessionToken(sessionToken);
        properties.getStorage().getObs().getStorages().put(name, keys);
    }

    private static HuaweiObsStorageConfigInfo obsConfig(String location, String storageName, String endpoint) {
        return obsConfig(location, storageName, endpoint, null, null);
    }

    private static HuaweiObsStorageConfigInfo obsConfig(String location, String storageName, String endpoint,
                                                       String accessKeyId, String sealedSecret) {
        return new HuaweiObsStorageConfigInfo(List.of(location), storageName, endpoint, null,
                accessKeyId, sealedSecret);
    }

    private static AliyunOssStorageConfigInfo ossConfig(String location, String storageName, String endpoint) {
        return ossConfig(location, storageName, endpoint, null, null);
    }

    private static AliyunOssStorageConfigInfo ossConfig(String location, String storageName, String endpoint,
                                                       String accessKeyId, String sealedSecret) {
        return new AliyunOssStorageConfigInfo(List.of(location), storageName, endpoint, null,
                accessKeyId, sealedSecret);
    }

    /** 键名族是否越界：断言「不含某前缀的键」比逐个断言「含哪些键」更能抓住串族。 */
    private static boolean hasAnyKeyStartingWith(Map<String, String> token, String prefix) {
        return token.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }
}
