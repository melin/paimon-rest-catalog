package com.example.paimonrest.support;

import com.example.paimonrest.dto.ManagementEnums.StorageType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code paimon.rest.file-io.type} 的取值：本部署接入了哪些存储实现。
 *
 * <p>对应 Polaris 的 {@code polaris.file-io.type}——那里是注册在
 * {@code FileIOFactory} 上的实现标识，本工程没有可插拔的 FileIO 工厂，
 * 因此把它的实际后果保留下来：<b>决定服务端接受哪些 {@code storageType}</b>。
 *
 * <p>为什么值得保留这一层：catalog 的存储类型是管理 API 写进去的，
 * 而能否读写这个仓库取决于部署里装了哪个 FileIO。两者不对齐时，
 * 报错会推迟到引擎真正读写数据的那一刻，且现象是引擎侧的文件系统异常，
 * 没人会去怀疑是 catalog 建错了。这里前移到创建/修改 catalog 时拒绝。
 *
 * <p>{@code FILE} 在所有取值下都可用：规格注明它仅供测试，本地部署与
 * 端到端脚本都依赖它，把它一起关掉会让「只想限制云存储」的部署失去退路。
 */
public enum FileIoType {

    /** 全部四种存储类型。 */
    DEFAULT("default", EnumSet.allOf(StorageType.class)),

    /** 本地文件系统与 S3（含任何兼容 S3 协议的对象存储）。 */
    S3("s3", EnumSet.of(StorageType.FILE, StorageType.S3)),

    /** 本地文件系统与 Azure Blob Storage。 */
    AZURE("azure", EnumSet.of(StorageType.FILE, StorageType.AZURE)),

    /** 本地文件系统与 Google Cloud Storage。 */
    GCS("gcs", EnumSet.of(StorageType.FILE, StorageType.GCS)),

    /** 仅本地文件系统。 */
    LOCAL("local", EnumSet.of(StorageType.FILE));

    private final String wireName;

    private final Set<StorageType> supported;

    FileIoType(String wireName, Set<StorageType> supported) {
        this.wireName = wireName;
        this.supported = supported;
    }

    /** 配置中的取值。 */
    public String wireName() {
        return wireName;
    }

    /** 本取值允许的存储类型；{@code FILE} 恒在其中。 */
    public Set<StorageType> supportedStorageTypes() {
        return supported;
    }

    /** 是否接受该存储类型。 */
    public boolean supports(StorageType type) {
        return supported.contains(type);
    }

    /** 按配置取值解析，未知取值返回空。 */
    public static Optional<FileIoType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.wireName.equalsIgnoreCase(value.trim()))
                .findFirst();
    }

    /**
     * 按配置取值解析，未知取值直接抛异常。
     *
     * <p>启动路径用它：取值非法属于配置错误，让服务带着默认实现跑起来
     * 只会把问题推到运行时，且现象会变成「明明配了却不按配的走」。
     */
    public static FileIoType require(String value) {
        return parse(value).orElseThrow(() -> new IllegalStateException(
                "paimon.rest.file-io.type=" + value + " is not a known FileIO type; expected one of "
                        + Arrays.stream(values()).map(FileIoType::wireName)
                        .collect(Collectors.joining(", "))));
    }

    /** 全部合法取值，供错误信息与文档复用。 */
    public static Set<String> wireNames() {
        Set<String> names = new LinkedHashSet<>();
        for (FileIoType type : values()) {
            names.add(type.wireName);
        }
        return names;
    }
}
