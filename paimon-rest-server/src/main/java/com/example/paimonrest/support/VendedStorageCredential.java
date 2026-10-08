package com.example.paimonrest.support;

import com.example.paimonrest.dto.ManagementEnums.StorageType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次存储凭据下发的产物。
 *
 * <p>不直接返回 {@code Map} 而是带上几个元信息，是因为这三件事在日志与测试里都要看：
 * 凭据属于哪种存储（决定了键名族）、有效期多久（缓存按它失效）、
 * 以及密钥是从哪一级取到的（配置、具名存储、还是环境凭据链）。
 *
 * <p>{@link #source()} 刻意不进 {@link #token()}：那是引擎消费的配置映射，
 * 混入一个非 FileIO 的键，轻则被引擎当成未知属性打印警告，
 * 重则在某些实现里触发「未知键即报错」的校验。它只用于诊断。
 */
public record VendedStorageCredential(Map<String, String> token,
                                      long ttlSeconds,
                                      StorageType storageType,
                                      String source) {

    public VendedStorageCredential {
        token = token == null ? new LinkedHashMap<>() : new LinkedHashMap<>(token);
    }

    /** 密钥来源为服务端配置里的默认凭据。 */
    public static final String SOURCE_CONFIGURATION = "configuration";

    /** 密钥来源为服务端配置里的具名存储，实际取值形如 {@code named-storage:<name>}。 */
    public static final String SOURCE_NAMED_STORAGE_PREFIX = "named-storage:";

    /** 服务端无凭据，交给引擎的环境凭据链。 */
    public static final String SOURCE_ENVIRONMENT = "environment";

    /** 本地文件系统，没有凭据可下发。 */
    public static final String SOURCE_FILESYSTEM = "filesystem";

    /** 该存储的 {@code stsUnavailable} 为真，按规格不下发凭据。 */
    public static final String SOURCE_STS_UNAVAILABLE = "sts-unavailable";

    /**
     * Azure 只下发定位元数据。
     *
     * <p>Polaris 的 {@code polaris.storage.*} 里没有 Azure 账户密钥这一项——它靠服务进程
     * 自身的 Azure 标识签 SAS。本工程没有那层标识，因此只能把库、账户、租户传下去，
     * 由引擎用自己那份身份去换 SAS。这是明确的缺口，不是遗漏。
     */
    public static final String SOURCE_AZURE_METADATA_ONLY = "metadata-only";

    /** 取值是否来自具名存储。 */
    public boolean namedStorage() {
        return source != null && source.startsWith(SOURCE_NAMED_STORAGE_PREFIX);
    }

    /** 是否真的下发了密钥（而非只给定位信息）。 */
    public boolean hasSecrets() {
        return token.containsKey("s3.secret-access-key") || token.containsKey("gcs.oauth2.token");
    }
}
