package com.example.paimonrest.service;

import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.dto.ManagementEnums.StorageType;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.CredentialManagerType;
import com.example.paimonrest.support.FileIoType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 存储相关配置的解析与自检：把 {@code paimon.rest.storage.*}、
 * {@code paimon.rest.credential-manager.type}、{@code paimon.rest.file-io.type}
 * 解析成可直接使用的对象，并在启动时把非法取值挡下来。
 *
 * <p><b>为什么集中在一处。</b>这些配置项分散在四处，但它们的错误形态是同一种：
 * 取值非法、引用了不存在的实现。若在每个使用点各自解析，非法配置只会在
 * 第一次真的走到那条路径时才暴露，而「第一次走到」在生产环境里可能是上线后
 * 某个低频操作。放在启动路径上解析一次，问题在服务起不来的时候就暴露。
 *
 * <p>顺带把生效值打进日志：配置项有默认值，光看配置文件看不出实际用的是哪个。
 * 排查「明明配了却不生效」时，第一件事就是确认生效值。
 */
@Component
public class StorageRuntimePolicy {

    private static final Logger log = LoggerFactory.getLogger(StorageRuntimePolicy.class);

    private final FileIoType fileIo;

    private final StorageCredentialManager credentialManager;

    public StorageRuntimePolicy(RestServerProperties properties, List<StorageCredentialManager> managers) {
        this.fileIo = FileIoType.require(properties.getFileIo().getType());
        this.credentialManager = selectCredentialManager(properties, managers);
        logEffective(properties);
    }

    /** 本部署接入的存储实现。 */
    public FileIoType fileIo() {
        return fileIo;
    }

    /** 本部署使用的凭据下发策略。 */
    public StorageCredentialManager credentialManager() {
        return credentialManager;
    }

    /**
     * 校验该存储类型在本部署可用。
     *
     * @throws ApiException 400 本部署未接入该存储实现
     */
    public void requireSupported(StorageType type, String catalogName) {
        if (type == null) {
            return;
        }
        if (!fileIo.supports(type)) {
            throw ApiException.badRequest("storageType " + type.wireName()
                    + " is not available in this deployment: paimon.rest.file-io.type=" + fileIo.wireName()
                    + " supports " + fileIo.supportedStorageTypes().stream()
                    .map(StorageType::wireName).collect(Collectors.joining(", ")));
        }
        log.debug("catalog {} uses storageType {}, accepted by file-io.type={}",
                catalogName, type.wireName(), fileIo.wireName());
    }

    /**
     * 按配置取值挑出凭据管理器。
     *
     * <p>不用注入名字匹配：把实现标识写进 bean 名会让「改个常量名就悄悄换了实现」
     * 这种事发生。按 {@code type()} 的值匹配，标识由实现自己声明，改名会编译不过。
     */
    private static StorageCredentialManager selectCredentialManager(RestServerProperties properties,
                                                                   List<StorageCredentialManager> managers) {
        CredentialManagerType wanted = CredentialManagerType.require(properties.getCredentialManager().getType());
        return managers.stream()
                .filter(manager -> manager.type() == wanted)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "paimon.rest.credential-manager.type=" + wanted.wireName()
                                + " has no registered implementation"));
    }

    /**
     * 打印生效的存储运行时配置，并校验时长类取值。
     *
     * <p>负数时长不会被 Spring 的绑定拦下（{@code Duration} 不是 {@code Number}，
     * 用不上 {@code @Min}），但一个负的超时会让底层直接抛异常或立即超时，
     * 现象与配置写法完全对不上。这里在启动时拦下来。
     */
    private void logEffective(RestServerProperties properties) {
        RestServerProperties.Storage storage = properties.getStorage();
        Map<String, Duration> durations = new LinkedHashMap<>();
        durations.put("read-timeout", storage.getReadTimeout());
        durations.put("connect-timeout", storage.getConnectTimeout());
        durations.put("connection-acquisition-timeout", storage.getConnectionAcquisitionTimeout());
        durations.put("connection-max-idle-time", storage.getConnectionMaxIdleTime());
        durations.put("connection-time-to-live", storage.getConnectionTimeToLive());
        durations.forEach((name, value) -> {
            if (value != null && (value.isNegative() || value.isZero())) {
                throw new IllegalStateException(
                        "paimon.rest.storage." + name + " must be positive, got " + value);
            }
        });

        log.info("storage runtime: file-io.type={} (supports {}), credential-manager.type={}, "
                        + "credential-cache.max-entries={}, aws.default-credentials={}, aws.named-storages={}, "
                        + "gcp.token={}, obs={}, oss={}, read-timeout={}, connect-timeout={}",
                fileIo.wireName(),
                fileIo.supportedStorageTypes().stream().map(StorageType::wireName).collect(Collectors.joining(",")),
                credentialManager.type().wireName(),
                properties.getStorageCredentialCache().getMaxEntries(),
                storage.getAws().getAccessKey() == null || storage.getAws().getAccessKey().isBlank()
                        ? "absent" : "configured",
                storage.getAws().getStorages().keySet(),
                storage.getGcp().getToken() == null || storage.getGcp().getToken().isBlank()
                        ? "absent" : "configured",
                summarize(storage.getObs()),
                summarize(storage.getOss()),
                storage.getReadTimeout(),
                storage.getConnectTimeout());
    }

    /**
     * 凭据组的可读描述，形如 {@code configured/[warehouse-a]}。
     *
     * <p>只给 OBS / OSS 用了这个写法，AWS 与 GCP 仍是展开的两项。不统一是为了不动
     * 既有的日志行——它已经被抄进了运维记录与本文档，改格式等于让那些记录对不上号。
     */
    private static String summarize(RestServerProperties.Storage.CloudCredentials credentials) {
        boolean configured = credentials.getAccessKey() != null && !credentials.getAccessKey().isBlank()
                && credentials.getSecretKey() != null && !credentials.getSecretKey().isBlank();
        return (configured ? "configured" : "absent") + "/" + credentials.getStorages().keySet();
    }
}
