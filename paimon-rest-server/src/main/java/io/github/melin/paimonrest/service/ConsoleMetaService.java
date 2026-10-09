package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.dto.ConsoleDtos;
import io.github.melin.paimonrest.dto.ManagementEnums.CatalogType;
import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.dto.ManagementEnums.StorageType;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.support.CredentialManagerType;
import io.github.melin.paimonrest.support.FileIoType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 控制台元数据的组装。
 *
 * <p>取值全部现取现用（枚举 {@code values()}、配置对象、{@link FileIoType#supportedStorageTypes()}），
 * 不缓存也不落库：这些值只有在服务端重启后才会变，而重启后本进程的缓存自然也没了。
 * 唯一的例外是版本号——它来自 jar 清单，进程生命周期内不变。
 */
@Service
@RequiredArgsConstructor
public class ConsoleMetaService {

    /** 从类目录（IDE、测试）运行时读不到清单，用这个占位而不是空串。 */
    private static final String DEVELOPMENT_VERSION = "development";

    private final RestServerProperties properties;

    @Value("${spring.application.name:paimon-rest-server}")
    private String applicationName;

    public ConsoleDtos.ConsoleMeta meta() {
        return new ConsoleDtos.ConsoleMeta(service(), enums());
    }

    private ConsoleDtos.Service service() {
        FileIoType fileIoType = FileIoType.require(properties.getFileIo().getType());
        CredentialManagerType credentialManagerType =
                CredentialManagerType.require(properties.getCredentialManager().getType());
        return new ConsoleDtos.Service(
                applicationName,
                version(),
                properties.getAuth().isEnabled(),
                properties.getAuthorization().isEnabled(),
                credentialManagerType.wireName(),
                fileIoType.wireName(),
                properties.getDefaultPrefix(),
                properties.getDefaultWarehouse(),
                properties.isAutoCreateCatalog(),
                properties.getDefaultPageSize(),
                properties.getMaxPageSize(),
                properties.getPathTemplate(),
                properties.getStorageCredentialCache().getMaxEntries(),
                properties.getCredential().getTtlSeconds());
    }

    /**
     * 构建版本。
     *
     * <p>读 jar 清单里的 {@code Implementation-Version}——由 maven-jar-plugin 的
     * {@code addDefaultImplementationEntries} 写入（见 paimon-rest-server/pom.xml）。
     * 从 {@code target/classes} 直接运行时该值为空，此时返回 {@code development}：
     * 报告一个「看起来像版本号」的默认值会让人误以为在跑发布版。
     */
    private String version() {
        String implementationVersion = ConsoleMetaService.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? DEVELOPMENT_VERSION
                : implementationVersion;
    }

    private ConsoleDtos.Enums enums() {
        FileIoType fileIoType = FileIoType.require(properties.getFileIo().getType());
        Map<String, List<String>> privilegesByGrantType = new LinkedHashMap<>();
        for (String resourceType : Privilege.resourceTypes()) {
            List<String> privileges = new ArrayList<>();
            for (Privilege privilege : Privilege.allowedFor(resourceType)) {
                privileges.add(privilege.name());
            }
            // 集合本身来自不可变 LinkedHashSet，顺序稳定；这里排序是为了让控制台的
            // 下拉框有一个可预期的顺序，而不是依赖静态初始化块的书写顺序
            privileges.sort(String::compareTo);
            privilegesByGrantType.put(resourceType, privileges);
        }

        List<String> supported = new ArrayList<>();
        for (StorageType type : StorageType.values()) {
            if (fileIoType.supports(type)) {
                supported.add(type.wireName());
            }
        }

        return new ConsoleDtos.Enums(
                names(CatalogType.values()),
                names(StorageType.values()),
                supported,
                Arrays.stream(CredentialManagerType.values()).map(CredentialManagerType::wireName).toList(),
                List.copyOf(FileIoType.wireNames()),
                Arrays.stream(GrantType.values()).map(GrantType::wireName).toList(),
                privilegesByGrantType);
    }

    private static <E extends Enum<E>> List<String> names(E[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (E value : values) {
            names.add(value.name());
        }
        return names;
    }
}
