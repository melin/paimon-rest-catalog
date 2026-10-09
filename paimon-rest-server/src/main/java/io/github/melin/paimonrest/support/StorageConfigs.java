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
 *
 * <p><b>静态凭据的三个入口在这里收口。</b>规格没有「用户填 AK/SK」的位置，
 * 这对字段是本工程的扩展（见 {@code StorageDtos}），因此它的读写规则也由本类承担：
 * 写路径 {@link #mergeStaticCredentials}（保持/设置/清除）、对外响应
 * {@link #withoutSecrets}（抹掉密文）、内部下发 {@link #staticCredentials}
 * （解密后交给凭据管理器）。加解密本身在 {@link CredentialCipher}，
 * 本类只决定「什么时候该调用它」——这样规则可以脱离 Spring 上下文单测。
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
        requireSingleCredentialSource(info);
    }

    /**
     * {@code storageName} 与静态凭据互斥。
     *
     * <p>两者都在回答「用哪把钥匙」：{@code storageName} 指向服务端配置里的具名存储，
     * 静态凭据则是这个 catalog 自己带着的。同时给出时没有确定语义——按前者取值会让
     * 调用方以为后者生效了，按后者取值则让凭据轮换（改服务端配置）静默失效。
     * 这种「两条路都写了、但只有一条生效」的配置，报错比猜要好。
     */
    private static void requireSingleCredentialSource(StorageConfigInfo info) {
        if (hasStaticCredentials(info) && !isBlank(info.storageName())) {
            throw ApiException.badRequest("storageConfigInfo.storageName and static credentials are"
                    + " mutually exclusive: both select the credentials used to access the storage;"
                    + " keep only one of them");
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
     *
     * <p><b>返回的是库中原样，静态凭据在其中是密文。</b>两条使用路径对它的处理不同：
     * 对外响应必须先过 {@link #withoutSecrets}，内部下发凭据走
     * {@link #staticCredentials}。直接把它交给客户端会把密文当密钥发出去
     * （引擎侧表现为认证失败，而不是「密钥不对」），因此这里不提供「默认已脱敏」的
     * 假象——需要哪一份就显式要哪一份。
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
                    null, null, null, null, null, null, null, null, null, null, null);
            case AZURE -> new AzureStorageConfigInfo(locations, null, null, null, null, null);
            case GCS -> new GcpStorageConfigInfo(locations, null, null);
            case OBS -> new HuaweiObsStorageConfigInfo(locations, null, null, null, null, null);
            case OSS -> new AliyunOssStorageConfigInfo(locations, null, null, null, null, null);
            case FILE -> new FileStorageConfigInfo(locations, null);
        };
    }

    // ------------------------------------------------------------------ 静态凭据

    /**
     * 一份配置里的静态凭据。{@code secretAccessKey} 在实体里是**密文**，
     * 只有 {@link #staticCredentials} 拿到的才是明文。
     */
    public record StaticCredentials(String accessKeyId, String secretAccessKey) {
    }

    /** 该配置能否承载静态凭据：只有 S3 / OBS / OSS 三种子类型有这对字段。 */
    public static boolean supportsStaticCredentials(StorageConfigInfo info) {
        return info instanceof AwsStorageConfigInfo
                || info instanceof HuaweiObsStorageConfigInfo
                || info instanceof AliyunOssStorageConfigInfo;
    }

    /** 该配置是否配了静态凭据（以 {@code accessKeyId} 为准，两者由写路径保证成对）。 */
    public static boolean hasStaticCredentials(StorageConfigInfo info) {
        return supportsStaticCredentials(info) && !isBlank(accessKeyIdOf(info));
    }

    /**
     * 取出可下发的静态凭据；没有配置时返回 {@code null}。
     *
     * <p>解密在这里发生，而不是在 DTO 装配时：DTO 是要发给客户端的，而凭据只在
     * 服务端内部消费。把解密收在唯一的读取点上，就不会出现「某个响应顺手把明文带出去」。
     *
     * @throws ApiException 500 密文无法用当前密钥解开
     */
    public static StaticCredentials staticCredentials(StorageConfigInfo info, CredentialCipher cipher) {
        if (!hasStaticCredentials(info)) {
            return null;
        }
        return new StaticCredentials(accessKeyIdOf(info), cipher.reveal(secretAccessKeyOf(info)));
    }

    /**
     * 抹掉密文，供对外响应使用。
     *
     * <p>{@code accessKeyId} 保留：它不是秘密，且管理台要显示「这个 catalog 配的是哪把钥匙」。
     * 密钥本身一律不回显——它一旦能被读出来，加密落库就只剩「防备份外泄」这一层意义了。
     */
    public static StorageConfigInfo withoutSecrets(StorageConfigInfo info) {
        if (!supportsStaticCredentials(info)) {
            return info;
        }
        return withCredentials(info, accessKeyIdOf(info), null);
    }

    /**
     * 把请求里的静态凭据与库中已有的合并成可落库的形态。
     *
     * <p><b>为什么这里不能照搬 {@code PUT} 的整体替换语义。</b>规格的 {@code PUT}
     * 对未给出的字段一律置空（见 {@code StorageConfigApiTests} 的
     * {@code updatingStorageReplacesTheWholeConfiguration}），这条规则对
     * {@code secretAccessKey} 是不成立的：它只写不读，调用方**拿不到旧值回填**，
     * 按整体替换处理就等于「每次改 endpoint 都得重新输一遍密钥」，
     * 而更糟的是别人用 SDK 做读-改-写时会静默把密钥清空、直到引擎读写数据才报错。
     * 因此密钥是整体替换的唯一例外，规则如下：
     *
     * <ul>
     *   <li>两个字段都没给（{@code null}）→ 保持库中原值；
     *   <li>两个字段都给了且都非空 → 换新（{@code accessKeyId} 与旧值相同也照换，
     *       此时 {@code secretAccessKey} 必须一起给，避免「只换 id 不换密钥」的半份配置）；
     *   <li>两个字段都给了且都是空串 → 清除静态凭据，回到服务端配置的取密钥路径；
     *   <li>只给一个 → 400。读-改-写回显旧 {@code accessKeyId} 是唯一的例外：
     *       它与库中一致时不要求同时给密钥。
     * </ul>
     *
     * <p>存储类型换了（例如 S3 → GCS）时凭据不迁移：AK/SK 是签发到具体云厂商与
     * 具体密钥空间的，跨类型沿用只会让另一家云的密钥留在配置里。
     *
     * @param requested 本次请求里的配置
     * @param existing  库中已有的配置，新建时为 {@code null}
     */
    public static StorageConfigInfo mergeStaticCredentials(StorageConfigInfo requested,
                                                           StorageConfigInfo existing,
                                                           CredentialCipher cipher) {
        if (!supportsStaticCredentials(requested)) {
            return requested;
        }
        String givenId = accessKeyIdOf(requested);
        String givenSecret = secretAccessKeyOf(requested);
        String storedId = existing != null && requested.storageType() == existing.storageType()
                ? accessKeyIdOf(existing) : null;
        String storedSecret = existing != null && requested.storageType() == existing.storageType()
                ? secretAccessKeyOf(existing) : null;

        if (givenId == null && givenSecret == null) {
            return withCredentials(requested, storedId, storedSecret);
        }
        if (givenId != null && givenSecret != null) {
            if (isBlank(givenId) && isBlank(givenSecret)) {
                return withCredentials(requested, null, null);
            }
            if (!isBlank(givenId) && !isBlank(givenSecret)) {
                return withCredentials(requested, givenId.trim(), cipher.seal(givenSecret.trim()));
            }
            throw ApiException.badRequest("storageConfigInfo.accessKeyId and secretAccessKey must be"
                    + " provided together: both filled to set, both empty to remove");
        }
        // 只给了一个：允许「原样回显已有的 accessKeyId」，其余一律拒绝
        if (givenId != null && givenSecret == null && !isBlank(givenId)
                && givenId.trim().equals(storedId)) {
            return withCredentials(requested, storedId, storedSecret);
        }
        throw ApiException.badRequest("storageConfigInfo.secretAccessKey is required when accessKeyId"
                + " is set or changed; it is never returned by the API, so it must be re-supplied"
                + " whenever the key changes");
    }

    private static String accessKeyIdOf(StorageConfigInfo info) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return s3.accessKeyId();
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return obs.accessKeyId();
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return oss.accessKeyId();
        }
        return null;
    }

    private static String secretAccessKeyOf(StorageConfigInfo info) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return s3.secretAccessKey();
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return obs.secretAccessKey();
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return oss.secretAccessKey();
        }
        return null;
    }

    /**
     * 换掉静态凭据、保留其余字段。
     *
     * <p>与 {@link #copyWith} 同样写成分类型的分支：字段增删时编译期报错，
     * 而反射写法会静默漏字段。无静态凭据字段的类型原样返回。
     */
    private static StorageConfigInfo withCredentials(StorageConfigInfo info,
                                                     String accessKeyId,
                                                     String secretAccessKey) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return new AwsStorageConfigInfo(s3.allowedLocations(), s3.storageName(),
                    s3.roleArn(), s3.externalId(), s3.userArn(),
                    s3.encryptionKeys(), s3.decryptionKeys(), s3.region(),
                    s3.endpoint(), s3.stsEndpoint(), s3.stsUnavailable(),
                    s3.endpointInternal(), s3.pathStyleAccess(), s3.kmsUnavailable(),
                    accessKeyId, secretAccessKey);
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return new HuaweiObsStorageConfigInfo(obs.allowedLocations(), obs.storageName(),
                    obs.endpoint(), obs.stsUnavailable(), accessKeyId, secretAccessKey);
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return new AliyunOssStorageConfigInfo(oss.allowedLocations(), oss.storageName(),
                    oss.endpoint(), oss.stsUnavailable(), accessKeyId, secretAccessKey);
        }
        return info;
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
                    s3.endpointInternal(), s3.pathStyleAccess(), s3.kmsUnavailable(),
                    s3.accessKeyId(), s3.secretAccessKey());
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
                    obs.endpoint(), obs.stsUnavailable(), obs.accessKeyId(), obs.secretAccessKey());
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return new AliyunOssStorageConfigInfo(locations, storageName,
                    oss.endpoint(), oss.stsUnavailable(), oss.accessKeyId(), oss.secretAccessKey());
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
