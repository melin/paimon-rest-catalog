package com.example.paimonrest.service;

import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.CredentialManagerType;
import com.example.paimonrest.support.VendedStorageCredential;
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

    private final RestServerProperties properties;

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
            case FILE -> file((FileStorageConfigInfo) storage, tableId, tablePath, token);
        };
        VendedStorageCredential vended =
                new VendedStorageCredential(token, ttlSeconds(storage), storage.storageType(), source);
        log.debug("vended {} credentials for table {} from {}", storage.storageType(), tableId, source);
        return vended;
    }

    // ------------------------------------------------------------------ S3

    /**
     * S3：三级密钥来源，优先级为具名存储 → 默认配置 → 环境凭据链。
     *
     * <p>具名存储优先是必须的：catalog 显式写了 {@code storageName}，
     * 说明它要的是那一组密钥，而不是服务端的默认密钥。
     * 找不到该名字时直接失败（500），不退回默认凭据——
     * 退回会把「本来只能读 a 桶的身份」静默升级成「服务端默认身份」，
     * 一个拼错的配置不该带来权限扩大。
     */
    private String s3(AwsStorageConfigInfo s3, Map<String, String> token) {
        String source = VendedStorageCredential.SOURCE_ENVIRONMENT;
        boolean vendsSecrets = !Boolean.TRUE.equals(s3.stsUnavailable());

        if (!vendsSecrets) {
            source = VendedStorageCredential.SOURCE_STS_UNAVAILABLE;
        } else {
            RestServerProperties.Storage.Aws aws = properties.getStorage().getAws();
            RestServerProperties.Storage.Keys keys = namedKeys(aws, s3.storageName());
            if (keys != null) {
                source = VendedStorageCredential.SOURCE_NAMED_STORAGE_PREFIX + s3.storageName();
            } else if (aws.getAccessKey() != null && !aws.getAccessKey().isBlank()
                    && aws.getSecretKey() != null && !aws.getSecretKey().isBlank()) {
                keys = new RestServerProperties.Storage.Keys();
                keys.setAccessKey(aws.getAccessKey());
                keys.setSecretKey(aws.getSecretKey());
                source = VendedStorageCredential.SOURCE_CONFIGURATION;
            }
            if (keys != null) {
                token.put(KEY_S3_ACCESS, keys.getAccessKey());
                token.put(KEY_S3_SECRET, keys.getSecretKey());
            }
        }

        // 定位配置与密钥有无无关：即使不下发密钥，引擎也需要知道该连哪里
        putIfPresent(token, "s3.region", s3.region());
        putIfPresent(token, "s3.endpoint", s3.endpoint());
        if (s3.pathStyleAccess() != null) {
            token.put("s3.path-style-access", s3.pathStyleAccess().toString());
        }
        return source;
    }

    /**
     * 取出具名存储的密钥。
     *
     * @throws ApiException 500 catalog 引用了配置里不存在的具名存储
     */
    private RestServerProperties.Storage.Keys namedKeys(RestServerProperties.Storage.Aws aws, String storageName) {
        if (storageName == null || storageName.isBlank()) {
            return null;
        }
        RestServerProperties.Storage.Keys keys = aws.getStorages().get(storageName);
        if (keys == null || keys.getAccessKey() == null || keys.getAccessKey().isBlank()) {
            throw new ApiException(500, null, null,
                    "catalog references storageName '" + storageName
                            + "' but paimon.rest.storage.aws.storages has no such entry");
        }
        return keys;
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
