package io.github.melin.paimonrest.support;

import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.dto.ManagementEnums.StorageType;
import io.github.melin.paimonrest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code StorageConfigInfo} 的校验、归一化，以及实体与 DTO 之间的搬运。
 *
 * <p>判别联合的「按类型校验」只能写在 Java 侧：规格里只有
 * {@code AzureStorageConfigInfo} 声明了 {@code required: [tenantId]}，
 * 其余五种都没有必填字段，这一点用注解表达不了。
 *
 * <p><b>OBS / OSS 的 {@code endpoint} 不设为必填。</b>它落空时引擎会需要一个端点，
 * 看起来像该拦下来的配置错误，但服务端无从判断引擎侧是否已经在
 * {@code core-site.xml} 里配好了 {@code fs.obs.endpoint}——这是常见部署形态。
 * 硬判会把合法配置拒之门外，与下面对 {@code allowedLocations} 前缀的处理同一个取舍：
 * 服务端只拒绝它确知非法的输入。
 *
 * <p>刻意**不**校验 {@code allowedLocations} 的 URI 前缀。规格里
 * {@code s3://} / {@code abfss://} / {@code gs://} 是 example 而不是约束，
 * 而现实中 S3 兼容存储常用自定义协议、Azure 还有 {@code wasb://} 这类历史形态。
 * 按前缀硬判会把合法配置拒之门外，因此这里只拒绝空串。
 */
public final class StorageConfigs {

    private StorageConfigs() {
    }

    /**
     * 按 {@code storageType} 校验类型专属的必填字段。
     *
     * @throws ApiException 400，字段缺失或取值非法
     */
    public static void validate(StorageConfigInfo info) {
        if (info == null) {
            return;
        }
        requireLocationsNotBlank(info);

        if (info instanceof AzureStorageConfigInfo azure && isBlank(azure.tenantId())) {
            throw ApiException.badRequest(
                    "storageConfigInfo.tenantId is required for storageType AZURE");
        }
    }

    /**
     * 归一化位置列表：列表为空时退回到给定位置，使落库的 JSON 与投影列完全一致。
     *
     * <p>{@code allowedLocations} 在规格里是可选的，但服务端要用它的首项当仓库根，
     * 因此这里补一个默认值，而不是让下游各自处理空列表。
     */
    public static StorageConfigInfo normalize(StorageConfigInfo info, String fallbackLocation) {
        if (info == null) {
            return null;
        }
        List<String> locations = info.allowedLocations();
        if (locations == null || locations.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            if (fallbackLocation != null && !fallbackLocation.isBlank()) {
                fallback.add(fallbackLocation);
            }
            return copyWith(info, fallback, info.storageName());
        }
        return copyWith(info, new ArrayList<>(locations), info.storageName());
    }

    /** 该配置的仓库根，即 {@code allowedLocations} 的首项；无位置时返回 {@code null}。 */
    public static String warehouseOf(StorageConfigInfo info) {
        if (info == null || info.allowedLocations() == null || info.allowedLocations().isEmpty()) {
            return null;
        }
        String first = info.allowedLocations().get(0);
        return first == null || first.isBlank() ? null : first;
    }

    /** 只含共有字段的 {@code FILE} 配置，位置为给定仓库。供服务端自建 catalog 使用。 */
    public static StorageConfigInfo fileStorage(String warehouse) {
        List<String> locations = new ArrayList<>();
        if (warehouse != null && !warehouse.isBlank()) {
            locations.add(warehouse);
        }
        return new FileStorageConfigInfo(locations, null);
    }

    /**
     * 把存储配置写进实体，同时同步它的两个投影列与仓库位置。
     *
     * <p><b>所有写 catalog 存储的地方都必须走这里</b>：JSON 与投影列由同一处一起落库，
     * 才不会出现「JSON 说是 S3、投影列说是 FILE」这种不一致。
     */
    public static void apply(CatalogEntity entity, StorageConfigInfo config) {
        entity.setStorageConfig(config);
        entity.setStorageType(config.storageType().wireName());
        entity.setAllowedLocations(config.allowedLocations() == null
                ? new ArrayList<>()
                : new ArrayList<>(config.allowedLocations()));
        String warehouse = warehouseOf(config);
        if (warehouse != null) {
            entity.setWarehouse(warehouse);
        }
    }

    /**
     * 还原实体的存储配置。
     *
     * <p>优先用 {@link CatalogEntity#getStorageConfig()}；该列为空时退回投影列重建，
     * 以便读取本特性上线前写入的历史行。重建只能得到共有字段，
     * 类型专属字段一律为空——这是历史数据的固有损失，不是 bug。
     */
    public static StorageConfigInfo of(CatalogEntity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getStorageConfig() != null) {
            return entity.getStorageConfig();
        }
        StorageType type = StorageType.parse(entity.getStorageType()).orElse(StorageType.FILE);
        List<String> locations = entity.getAllowedLocations() == null
                ? new ArrayList<>()
                : new ArrayList<>(entity.getAllowedLocations());
        if (locations.isEmpty() && entity.getWarehouse() != null && !entity.getWarehouse().isBlank()) {
            locations.add(entity.getWarehouse());
        }
        return blank(type, locations);
    }

    /** 按存储类型构造只有共有字段的配置。 */
    public static StorageConfigInfo blank(StorageType type, List<String> allowedLocations) {
        List<String> locations = allowedLocations == null ? new ArrayList<>() : allowedLocations;
        return switch (type) {
            case S3 -> new AwsStorageConfigInfo(locations, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            case AZURE -> new AzureStorageConfigInfo(locations, null, null, null, null, null);
            case GCS -> new GcpStorageConfigInfo(locations, null, null);
            case OBS -> new HuaweiObsStorageConfigInfo(locations, null, null, null);
            case OSS -> new AliyunOssStorageConfigInfo(locations, null, null, null);
            case FILE -> new FileStorageConfigInfo(locations, null);
        };
    }

    private static void requireLocationsNotBlank(StorageConfigInfo info) {
        List<String> locations = info.allowedLocations();
        if (locations == null) {
            return;
        }
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                throw ApiException.badRequest(
                        "storageConfigInfo.allowedLocations must not contain blank entries");
            }
        }
    }

    /**
     * 换掉共有字段、保留类型专属字段。
     *
     * <p>写成分类型的分支而不是反射或 Map 改写：字段一旦增删，
     * 这里会编译期报错，而反射写法会静默漏字段。
     */
    private static StorageConfigInfo copyWith(StorageConfigInfo info,
                                              List<String> locations,
                                              String storageName) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return new AwsStorageConfigInfo(locations, storageName,
                    s3.roleArn(), s3.externalId(), s3.userArn(),
                    s3.encryptionKeys(), s3.decryptionKeys(), s3.region(),
                    s3.endpoint(), s3.stsEndpoint(), s3.stsUnavailable(),
                    s3.endpointInternal(), s3.pathStyleAccess(), s3.kmsUnavailable());
        }
        if (info instanceof AzureStorageConfigInfo azure) {
            return new AzureStorageConfigInfo(locations, storageName,
                    azure.tenantId(), azure.multiTenantAppName(),
                    azure.consentUrl(), azure.hierarchical());
        }
        if (info instanceof GcpStorageConfigInfo gcs) {
            return new GcpStorageConfigInfo(locations, storageName, gcs.gcsServiceAccount());
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return new HuaweiObsStorageConfigInfo(locations, storageName,
                    obs.endpoint(), obs.stsUnavailable());
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return new AliyunOssStorageConfigInfo(locations, storageName,
                    oss.endpoint(), oss.stsUnavailable());
        }
        if (info instanceof FileStorageConfigInfo) {
            return new FileStorageConfigInfo(locations, storageName);
        }
        throw ApiException.badRequest("Unsupported storageConfigInfo implementation: " + info.getClass());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
