package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.CredentialCipher;
import io.github.melin.paimonrest.support.CredentialManagerType;
import io.github.melin.paimonrest.support.StorageConfigs;
import io.github.melin.paimonrest.support.VendedStorageCredential;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认的凭据下发实现：按 {@code storageType} 产出引擎 FileIO 认识的键。
 *
 * <p>键名族不是随手起的，而是各引擎 FileIO 的真实属性名：
 * S3 用 {@code s3.*}，GCS 用 {@code gcs.oauth2.*}，本地文件系统无凭据。
 * 键名写错不会报错，只会让引擎静默地拿不到凭据——因此每种类型都配了测试，
 * 断言的是键名本身，而不是「调用了没抛异常」。
 *
 * <p><b>OBS 与 OSS 的键名族不能互相套用。</b>两家云的对象存储都兼容 S3 协议，
 * 容易以为用 {@code s3.*} 或对方的键名都能跑通，实际不行：Paimon 的
 * {@code paimon-obs} 与 {@code paimon-oss} 是两个独立的 FileIO，各认自己的一套键，
 * 而且两套的命名风格还不一致——华为是点分隔小写的
 * {@code fs.obs.access.key} / {@code fs.obs.secret.key} / {@code fs.obs.session.token}，
 * 阿里是驼峰的 {@code fs.oss.accessKeyId} / {@code fs.oss.accessKeySecret} /
 * {@code fs.oss.securityToken}。就连「临时凭据的令牌」这个东西，两边都不同名。
 * 这层差异在 {@link #OBS_KEYS} 与 {@link #OSS_KEYS} 里集中表达，便于对照。
 *
 * <p><b>刻意不下发的字段。</b>{@code AwsStorageConfigInfo} 的
 * {@code endpointInternal} 与 {@code stsEndpoint} 是服务端自己访问对象存储时用的地址，
 * 规格明确写了 {@code endpointInternal} 客户端永远看不到。把它们发给引擎，
 * 轻则引擎连到一个不可达的内网地址，重则把内网拓扑泄给调用方。
 * {@code roleArn}/{@code externalId}/{@code userArn} 同理不下发：
 * 那是服务端去 assume 角色的材料，不是给客户端的凭据。
 *
 * <p><b>{@code stsUnavailable} 的语义。</b>规格写的是「设为 true 时 Polaris 服务端
 * 不再为访问该 catalog 下发凭据」。这里照此执行：仍然下发定位配置（区域、端点），
 * 但不给密钥，由引擎用自己的身份访问。这是配置意图，不是失败。
 *
 * <p><b>catalog 自带的静态凭据是这一层的最高优先来源</b>，且排在
 * {@code stsUnavailable} 之前——两者的判断依据与取舍写在
 * {@link #s3} 的方法注释里。密钥在库中是密文，这里是唯一需要解密的消费点；
 * 解密失败（换了加密密钥）直接 500，不退回服务端配置：退回等于把
 * 「这个 catalog 该用哪把钥匙」从调用方明确指定降级成部署方默认，
 * 而密钥无法解开的真实原因（配置变更）会因此被掩盖成「权限不对」。
 */
@Component
@RequiredArgsConstructor
public class DefaultStorageCredentialManager implements StorageCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultStorageCredentialManager.class);

    /**
     * 从 {@code abfss://container@account.dfs.core.windows.net/prefix/} 里取出账户名。
     *
     * <p>刻意只认 {@code dfs.core.windows.net} 与 {@code blob.core.windows.net} 这两种后缀。
     * 更宽松的写法（取 {@code @} 与第一个点之间那段）在自定义域名上会解出一个似是而非的名字，
     * 而错误的账户名比没有账户名更糟：引擎会拿着它去认证一个不存在的账户，
     * 报出来的错与真正的原因（地址写错了）隔着一层。解不出来就不给这个键，
     * 让错误停在「地址格式不认识」这一层。
     */
    private static final Pattern AZURE_ACCOUNT = Pattern.compile(
            "^[a-zA-Z0-9]+://[^@/]+@([^.@/]+)\\.(?:dfs|blob)\\.core\\.windows\\.net(?:[/:?#]|$)");

    private static final String KEY_S3_ACCESS = "s3.access-key-id";

    private static final String KEY_S3_SECRET = "s3.secret-access-key";

    private static final String KEY_GCS_TOKEN = "gcs.oauth2.token";

    /**
     * 华为云 OBS 的凭据键名族，取自 OBSA（{@code hadoop-huaweicloud}）的配置项。
     *
     * <p>{@code session.token} 是临时 AK/SK 配套的安全令牌，不是 {@code security.token}——
     * 华为与阿里的命名不同，写错了不会报错，只会让引擎在临时凭据场景下签名失败。
     */
    private static final KeyFamily OBS_KEYS = new KeyFamily(
            "fs.obs.access.key", "fs.obs.secret.key", "fs.obs.session.token", "fs.obs.endpoint");

    /**
     * 阿里云 OSS 的凭据键名族，取自 {@code hadoop-aliyun} 的配置项。
     *
     * <p>注意 {@code accessKeyId} / {@code accessKeySecret} 是驼峰，与 OBS 的点分隔小写
     * 不是同一套风格；临时凭据的键也不同（{@code securityToken} 对 {@code session.token}）。
     */
    private static final KeyFamily OSS_KEYS = new KeyFamily(
            "fs.oss.accessKeyId", "fs.oss.accessKeySecret", "fs.oss.securityToken", "fs.oss.endpoint");

    private final RestServerProperties properties;

    private final CredentialCipher cipher;

    private final SecureRandom random = new SecureRandom();

    @Override
    public CredentialManagerType type() {
        return CredentialManagerType.DEFAULT;
    }

    @Override
    public VendedStorageCredential vend(StorageConfigInfo storage, String tableId, String tablePath) {
        if (storage == null) {
            throw new ApiException(500, null, null,
                    "catalog has no storage configuration; cannot vend data access credentials");
        }
        Map<String, String> token = new LinkedHashMap<>();
        String source = switch (storage.storageType()) {
            case S3 -> s3((AwsStorageConfigInfo) storage, token);
            case AZURE -> azure((AzureStorageConfigInfo) storage, token);
            case GCS -> gcs((GcpStorageConfigInfo) storage, token);
            case OBS -> obs((HuaweiObsStorageConfigInfo) storage, token);
            case OSS -> oss((AliyunOssStorageConfigInfo) storage, token);
            case FILE -> file((FileStorageConfigInfo) storage, tableId, tablePath, token);
        };
        VendedStorageCredential vended =
                new VendedStorageCredential(token, ttlSeconds(storage), storage.storageType(), source);
        log.debug("vended {} credentials for table {} from {}", storage.storageType(), tableId, source);
        return vended;
    }

    /** 一个对象存储 FileIO 的凭据键名族。四个键里只有前三个是密钥，最后一个只是定位。 */
    record KeyFamily(String accessKey, String secretKey, String sessionToken, String endpoint) {
    }

    // ------------------------------------------------------------------ S3

    /**
     * S3：四级密钥来源，优先级为 catalog 静态凭据 → 具名存储 → 默认配置 → 环境凭据链。
     *
     * <p>具名存储优先于默认配置是必须的：catalog 显式写了 {@code storageName}，
     * 说明它要的是那一组密钥，而不是服务端的默认密钥。
     * 找不到该名字时直接失败（500），不退回默认凭据——
     * 退回会把「本来只能读 a 桶的身份」静默升级成「服务端默认身份」，
     * 一个拼错的配置不该带来权限扩大。静态凭据与 {@code storageName} 互斥，
     * 由 {@link StorageConfigs#validate} 在写入口拒绝，所以两者不会真的一起出现。
     *
     * <p><b>静态凭据排在 {@code stsUnavailable} 之前。</b>{@code stsUnavailable}
     * 表达的是「服务端不去向云申请临时凭据」（规格原文：不再为该 catalog 下发凭据），
     * 而静态凭据是**建 catalog 的人自己填进这份配置的**，下发它既不是申请临时凭据，
     * 也不涉及服务端的云侧信任关系。顺序反过来的后果是：兼容 S3 的对象存储
     * （MinIO / Ceph / Ozone / FlashBlade）通常没有 STS，配置里会照惯例写上
     * {@code stsUnavailable: true}，于是「填了密钥却收不到密钥」——
     * 一个看起来像功能坏了的静默行为。
     */
    private String s3(AwsStorageConfigInfo s3, Map<String, String> token) {
        // 定位配置与密钥有无无关：即使不下发密钥，引擎也需要知道该连哪里
        putIfPresent(token, "s3.region", s3.region());
        putIfPresent(token, "s3.endpoint", s3.endpoint());
        if (s3.pathStyleAccess() != null) {
            token.put("s3.path-style-access", s3.pathStyleAccess().toString());
        }
        StorageConfigs.StaticCredentials own = StorageConfigs.staticCredentials(s3, cipher);
        if (own != null) {
            token.put(KEY_S3_ACCESS, own.accessKeyId());
            token.put(KEY_S3_SECRET, own.secretAccessKey());
            return VendedStorageCredential.SOURCE_STATIC_CREDENTIALS;
        }
        if (Boolean.TRUE.equals(s3.stsUnavailable())) {
            return VendedStorageCredential.SOURCE_STS_UNAVAILABLE;
        }

        RestServerProperties.Storage.Aws aws = properties.getStorage().getAws();
        RestServerProperties.Storage.Keys named = namedKeys(aws.getStorages(), s3.storageName(), "aws");
        if (named != null) {
            token.put(KEY_S3_ACCESS, named.getAccessKey());
            token.put(KEY_S3_SECRET, named.getSecretKey());
            return VendedStorageCredential.SOURCE_NAMED_STORAGE_PREFIX + s3.storageName();
        }
        if (configured(aws.getAccessKey(), aws.getSecretKey())) {
            token.put(KEY_S3_ACCESS, aws.getAccessKey());
            token.put(KEY_S3_SECRET, aws.getSecretKey());
            return VendedStorageCredential.SOURCE_CONFIGURATION;
        }
        return VendedStorageCredential.SOURCE_ENVIRONMENT;
    }

    // ------------------------------------------------------------------ OBS / OSS

    /**
     * 华为云 OBS：端点照发，密钥按四级来源取（catalog 静态凭据 → 具名 → 默认 → 环境）。
     *
     * <p>端点放在前面且不受密钥来源影响：即使服务端没有任何 OBS 凭据，
     * 引擎也需要知道连哪个端点——它可能用自己的 ECS 委托身份或
     * {@code fs.obs.security.provider} 去取凭据。
     */
    private String obs(HuaweiObsStorageConfigInfo obs, Map<String, String> token) {
        putIfPresent(token, OBS_KEYS.endpoint(), obs.endpoint());
        StorageConfigs.StaticCredentials own = StorageConfigs.staticCredentials(obs, cipher);
        if (own != null) {
            putSecrets(token, OBS_KEYS, own.accessKeyId(), own.secretAccessKey(), null);
            return VendedStorageCredential.SOURCE_STATIC_CREDENTIALS;
        }
        if (Boolean.TRUE.equals(obs.stsUnavailable())) {
            return VendedStorageCredential.SOURCE_STS_UNAVAILABLE;
        }
        return objectStoreSecrets("obs", properties.getStorage().getObs(), obs.storageName(), OBS_KEYS, token);
    }

    /** 阿里云 OSS：处理与 {@link #obs} 相同，只是键名族换成 {@link #OSS_KEYS}。 */
    private String oss(AliyunOssStorageConfigInfo oss, Map<String, String> token) {
        putIfPresent(token, OSS_KEYS.endpoint(), oss.endpoint());
        StorageConfigs.StaticCredentials own = StorageConfigs.staticCredentials(oss, cipher);
        if (own != null) {
            putSecrets(token, OSS_KEYS, own.accessKeyId(), own.secretAccessKey(), null);
            return VendedStorageCredential.SOURCE_STATIC_CREDENTIALS;
        }
        if (Boolean.TRUE.equals(oss.stsUnavailable())) {
            return VendedStorageCredential.SOURCE_STS_UNAVAILABLE;
        }
        return objectStoreSecrets("oss", properties.getStorage().getOss(), oss.storageName(), OSS_KEYS, token);
    }

    /**
     * OBS / OSS 共用的三级凭据来源：具名存储 → 默认配置 → 环境凭据链。
     *
     * <p>与 S3 同构，包括「具名存储找不到时失败而非退回默认」这条取舍。
     * 静态凭据那一级不在这里：它在调用点先于 {@code stsUnavailable} 处理，
     * 而本方法只管服务端配置面。
     *
     * <p>临时凭据（{@code sessionToken}）跟着它所属的那一级一起下发：具名存储里配了
     * 令牌就用那个，否则用默认配置的。混搭（具名存储的 AK/SK 配默认配置的令牌）
     * 没有任何合理场景——临时凭据的 AK、SK、令牌是一次签发的同一组。
     */
    private String objectStoreSecrets(String group,
                                      RestServerProperties.Storage.CloudCredentials credentials,
                                      String storageName,
                                      KeyFamily keys,
                                      Map<String, String> token) {
        RestServerProperties.Storage.Keys named = namedKeys(credentials.getStorages(), storageName, group);
        if (named != null) {
            putSecrets(token, keys, named.getAccessKey(), named.getSecretKey(), named.getSessionToken());
            return VendedStorageCredential.SOURCE_NAMED_STORAGE_PREFIX + storageName;
        }
        if (configured(credentials.getAccessKey(), credentials.getSecretKey())) {
            putSecrets(token, keys, credentials.getAccessKey(), credentials.getSecretKey(),
                    credentials.getSessionToken());
            return VendedStorageCredential.SOURCE_CONFIGURATION;
        }
        return VendedStorageCredential.SOURCE_ENVIRONMENT;
    }

    /** 写入一组密钥；安全令牌只在非空时写入，长期凭据没有它。 */
    private static void putSecrets(Map<String, String> token,
                                   KeyFamily keys,
                                   String accessKey,
                                   String secretKey,
                                   String sessionToken) {
        token.put(keys.accessKey(), accessKey);
        token.put(keys.secretKey(), secretKey);
        putIfPresent(token, keys.sessionToken(), sessionToken);
    }

    /**
     * 取出具名存储的密钥。
     *
     * @param group 配置组名，仅用于拼错误信息（{@code aws} / {@code obs} / {@code oss}）
     * @throws ApiException 500 catalog 引用了配置里不存在的具名存储
     */
    private RestServerProperties.Storage.Keys namedKeys(Map<String, RestServerProperties.Storage.Keys> storages,
                                                        String storageName,
                                                        String group) {
        if (storageName == null || storageName.isBlank()) {
            return null;
        }
        RestServerProperties.Storage.Keys keys = storages.get(storageName);
        if (keys == null || keys.getAccessKey() == null || keys.getAccessKey().isBlank()) {
            throw new ApiException(500, null, null,
                    "catalog references storageName '" + storageName
                            + "' but paimon.rest.storage." + group + ".storages has no such entry");
        }
        return keys;
    }

    private static boolean configured(String accessKey, String secretKey) {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    // ------------------------------------------------------------------ Azure

    /**
     * Azure：只下发定位元数据。
     *
     * <p>Polaris 的 {@code polaris.storage.*} 没有 Azure 账户密钥这一项，
     * 它用服务进程自身的标识去签 SAS。本工程没有那层标识，硬造一个 SAS
     * 只会得到一个签名错误的字符串，比不给更糟。因此这里把租户、账户、
     * 是否分层命名空间传下去，由引擎用它自己的身份换取访问权。
     */
    private String azure(AzureStorageConfigInfo azure, Map<String, String> token) {
        putIfPresent(token, "azure.tenant-id", azure.tenantId());
        putIfPresent(token, "azure.account", accountOf(azure.allowedLocations()));
        putIfPresent(token, "azure.multi-tenant-app-name", azure.multiTenantAppName());
        putIfPresent(token, "azure.consent-url", azure.consentUrl());
        if (azure.hierarchical() != null) {
            token.put("azure.hierarchical", azure.hierarchical().toString());
        }
        return VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY;
    }

    /** 从位置白名单的首项里取 Azure 账户名；取不到返回 {@code null}。 */
    static String accountOf(List<String> allowedLocations) {
        if (allowedLocations == null || allowedLocations.isEmpty()) {
            return null;
        }
        String first = allowedLocations.get(0);
        if (first == null) {
            return null;
        }
        Matcher matcher = AZURE_ACCOUNT.matcher(first);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ------------------------------------------------------------------ GCS

    /**
     * GCS：访问令牌来自服务端配置，未配置时由引擎用自己的凭据链。
     *
     * <p>{@code lifespan} 会同时收窄令牌自身的有效期与响应里的 {@code expiresAt}
     * （见 {@link #ttlSeconds}）：配了 10 分钟的 lifespan 却告诉调用方令牌一小时后过期，
     * 调用方会在第 11 分钟拿到 401，而它没有任何理由预期这件事。
     */
    private String gcs(GcpStorageConfigInfo gcs, Map<String, String> token) {
        putIfPresent(token, "gcs.service-account", gcs.gcsServiceAccount());
        String accessToken = properties.getStorage().getGcp().getToken();
        if (accessToken == null || accessToken.isBlank()) {
            return VendedStorageCredential.SOURCE_ENVIRONMENT;
        }
        token.put(KEY_GCS_TOKEN, accessToken);
        Duration lifespan = properties.getStorage().getGcp().getLifespan();
        if (lifespan != null) {
            token.put("gcs.oauth2.token-expires-at",
                    Long.toString(System.currentTimeMillis() + lifespan.toMillis()));
        }
        return VendedStorageCredential.SOURCE_CONFIGURATION;
    }

    // ------------------------------------------------------------------ FILE

    /**
     * 本地文件系统：没有凭据，保留服务端自签的自包含令牌。
     *
     * <p>为什么不是空映射：这是凭据下发接口的既有行为，且部分调用方按
     * 「token 非空」判断下发是否成功。本地仓库确实不需要凭据，
     * 但一个自包含令牌仍有意义——它把这次授权的范围（表 id）与过期时间
     * 落在一处，便于审计。文件系统本身不校验它。
     */
    private String file(FileStorageConfigInfo file, String tableId, String tablePath, Map<String, String> token) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds(file) * 1000L;
        token.put("accessKeyId", "PAIMON-" + tableId);
        token.put("accessKeySecret", randomSecret(24));
        token.put("securityToken", randomSecret(48));
        token.put("expiration", Instant.ofEpochMilli(expiresAt).toString());
        if (tablePath != null) {
            token.put("tablePath", tablePath);
        }
        return VendedStorageCredential.SOURCE_FILESYSTEM;
    }

    // ------------------------------------------------------------------ 公共

    /**
     * 本次下发的有效期（秒）。
     *
     * <p>基准是 {@code paimon.rest.credential.ttl-seconds}；GCS 配置了
     * {@code lifespan} 时取两者较小值，理由见 {@link #gcs}。
     */
    private long ttlSeconds(StorageConfigInfo storage) {
        long ttl = properties.getCredential().getTtlSeconds();
        if (storage instanceof GcpStorageConfigInfo) {
            Duration lifespan = properties.getStorage().getGcp().getLifespan();
            if (lifespan != null) {
                return Math.max(1, Math.min(ttl, lifespan.toSeconds()));
            }
        }
        return ttl;
    }

    private static void putIfPresent(Map<String, String> token, String key, String value) {
        if (value != null && !value.isBlank()) {
            token.put(key, value);
        }
    }

    private String randomSecret(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
