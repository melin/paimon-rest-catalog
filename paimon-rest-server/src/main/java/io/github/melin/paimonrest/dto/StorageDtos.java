package io.github.melin.paimonrest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * 存储配置 DTO，对应管理规格的 {@code StorageConfigInfo} 及其四个实现。
 *
 * <p><b>为什么用判别联合而不是一个大对象。</b>规格把
 * {@code StorageConfigInfo} 声明为带 {@code discriminator} 的多态基类，
 * {@code storageType} 决定其余字段属于哪个子类型：
 * {@code S3} → {@code AwsStorageConfigInfo}、{@code AZURE} → {@code AzureStorageConfigInfo}、
 * {@code GCS} → {@code GcpStorageConfigInfo}、{@code FILE} → {@code FileStorageConfigInfo}。
 * 若把它们拍平成一个「所有字段都可空」的 record，就会出现
 * 「{@code storageType=GCS} 却带着 {@code roleArn}」这类规格不允许的状态，
 * 而且 {@code AZURE} 必填 {@code tenantId} 这样的约束无处安放。
 * 这里按子类型建模，非法组合在类型层面就不成立。
 *
 * <p><b>判别字段不在子类型里。</b>{@code storageType} 由 Jackson 的类型信息机制读写
 * （{@code include = As.PROPERTY}），因此子类型 record 刻意不含该字段：
 * 序列化时由 Jackson 写入，反序列化时被 Jackson 消费，避免出现重复属性。
 * 子类型注册名见 {@link #WIRE_S3} 等常量，必须与
 * {@link ManagementEnums.StorageType} 的对外取值一致，由
 * {@code StorageConfigDtosTests} 断言守住，防止改名时两侧漂移。
 *
 * <p><b>两个非规格子类型。</b>{@code OBS} 与 {@code OSS} 不在 Polaris 规格的
 * {@code discriminator.mapping} 里，是本工程为华为云 OBS 与阿里云 OSS 加的扩展，
 * 理由见 {@link ManagementEnums.StorageType}。它们的字段刻意保持最小：
 * 只接收凭据下发真正要用的定位信息（{@code endpoint}），密钥一律留在服务端配置里，
 * 与 S3 子类型不接收密钥字段的处理保持一致。
 *
 * <p><b>已废弃字段未建模。</b>{@code AwsStorageConfigInfo} 的 {@code currentKmsKey} 与
 * {@code allowedKmsKeys} 在规格里标了 {@code deprecated}，这里不接收也不返回，
 * 统一用 {@code encryptionKeys} / {@code decryptionKeys}。
 * 传入这两个字段会被 Jackson 静默忽略（Spring Boot 默认关闭
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}），不影响既有调用方。
 */
public final class StorageDtos {

    /** 子类型注册名：S3。 */
    static final String WIRE_S3 = "S3";

    /** 子类型注册名：Azure Blob Storage。 */
    static final String WIRE_AZURE = "AZURE";

    /** 子类型注册名：Google Cloud Storage。 */
    static final String WIRE_GCS = "GCS";

    /** 子类型注册名：华为云 OBS。非规格取值，本工程扩展。 */
    static final String WIRE_OBS = "OBS";

    /** 子类型注册名：阿里云 OSS。非规格取值，本工程扩展。 */
    static final String WIRE_OSS = "OSS";

    /** 子类型注册名：本地文件系统。 */
    static final String WIRE_FILE = "FILE";

    private StorageDtos() {
    }

    /**
     * {@code StorageConfigInfo}：按 {@code storageType} 判别的存储配置基类。
     *
     * <p>{@code allowedLocations} 与 {@code storageName} 是所有子类型共有的字段，
     * 但 Java 的 record 不能继承字段，因此每个子类型各自声明一份。这是为换取
     * 「每种存储类型只暴露自己的字段」而付出的代价，由本类的测试守住一致性。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.PROPERTY,
            property = "storageType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AwsStorageConfigInfo.class, name = WIRE_S3),
            @JsonSubTypes.Type(value = AzureStorageConfigInfo.class, name = WIRE_AZURE),
            @JsonSubTypes.Type(value = GcpStorageConfigInfo.class, name = WIRE_GCS),
            @JsonSubTypes.Type(value = HuaweiObsStorageConfigInfo.class, name = WIRE_OBS),
            @JsonSubTypes.Type(value = AliyunOssStorageConfigInfo.class, name = WIRE_OSS),
            @JsonSubTypes.Type(value = FileStorageConfigInfo.class, name = WIRE_FILE)
    })
    public sealed interface StorageConfigInfo
            permits AwsStorageConfigInfo, AzureStorageConfigInfo, GcpStorageConfigInfo,
                    HuaweiObsStorageConfigInfo, AliyunOssStorageConfigInfo, FileStorageConfigInfo {

        /** 该 catalog 允许写入的位置白名单；首项同时用作仓库根。 */
        List<String> allowedLocations();

        /** 服务端具名存储配置的引用名，可选。 */
        String storageName();

        /**
         * 该配置对应的存储类型。
         *
         * <p>标 {@link JsonIgnore} 是必须的：JSON 里的 {@code storageType} 由 Jackson
         * 的类型信息机制负责读写，若再把这个方法当普通属性序列化，报文里会出现两个
         * 同名字段。
         */
        @JsonIgnore
        ManagementEnums.StorageType storageType();
    }

    /** {@code AwsStorageConfigInfo}：S3 或任何兼容 S3 协议的对象存储。 */
    public record AwsStorageConfigInfo(List<String> allowedLocations,
                                       String storageName,
                                       String roleArn,
                                       String externalId,
                                       String userArn,
                                       List<String> encryptionKeys,
                                       List<String> decryptionKeys,
                                       String region,
                                       String endpoint,
                                       String stsEndpoint,
                                       Boolean stsUnavailable,
                                       String endpointInternal,
                                       Boolean pathStyleAccess,
                                       Boolean kmsUnavailable)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.S3;
        }
    }

    /** {@code AzureStorageConfigInfo}：Azure Blob Storage / ADLS Gen2。{@code tenantId} 必填。 */
    public record AzureStorageConfigInfo(List<String> allowedLocations,
                                         String storageName,
                                         String tenantId,
                                         String multiTenantAppName,
                                         String consentUrl,
                                         Boolean hierarchical)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.AZURE;
        }
    }

    /** {@code GcpStorageConfigInfo}：Google Cloud Storage。 */
    public record GcpStorageConfigInfo(List<String> allowedLocations,
                                       String storageName,
                                       String gcsServiceAccount)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.GCS;
        }
    }

    /**
     * 华为云 OBS。**非规格子类型，本工程扩展**（见 {@link ManagementEnums.StorageType}）。
     *
     * <p>字段与 {@link AliyunOssStorageConfigInfo} 当前完全相同，但没有合并成一个
     * record：判别联合的成员身份由类型本身承担，合并后 {@code storageType()} 就无法
     * 自证；且两家云的 FileIO 配置面并不一致（OBS 有 {@code fs.obs.security.provider}
     * 凭据提供器，OSS 有服务端加密选项），后续任一侧新增字段时，合并的写法要被迫拆开。
     *
     * <p><b>没有 {@code region} 字段，是有意的。</b>华为云 OBSA 的配置表里不存在
     * {@code fs.obs.region} 这一项——区域信息已经包含在 {@code endpoint} 里
     * （形如 {@code obs.cn-north-4.myhuaweicloud.com}）。造一个下发后无人识别的键，
     * 比不给更容易误导：运维会以为填了就生效。
     */
    public record HuaweiObsStorageConfigInfo(List<String> allowedLocations,
                                             String storageName,
                                             String endpoint,
                                             Boolean stsUnavailable)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.OBS;
        }
    }

    /**
     * 阿里云 OSS。**非规格子类型，本工程扩展**（见 {@link ManagementEnums.StorageType}）。
     *
     * <p>没有 {@code region} 字段的理由同 {@link HuaweiObsStorageConfigInfo}：
     * Hadoop 的 aliyun-oss 官方配置表里只有 {@code fs.oss.endpoint} 与两个密钥项，
     * 区域由 endpoint 表达（形如 {@code oss-cn-hangzhou.aliyuncs.com}）。
     */
    public record AliyunOssStorageConfigInfo(List<String> allowedLocations,
                                             String storageName,
                                             String endpoint,
                                             Boolean stsUnavailable)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.OSS;
        }
    }

    /** {@code FileStorageConfigInfo}：本地或已挂载文件系统，规格注明仅供测试。 */
    public record FileStorageConfigInfo(List<String> allowedLocations,
                                        String storageName)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.FILE;
        }
    }
}
