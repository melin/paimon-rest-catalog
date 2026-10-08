package com.example.paimonrest.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * 管理 API 中需要校验取值的字符串枚举。
 *
 * <p>规格把这些字段声明为自由字符串加 {@code enum} 约束而非引用具名 schema，
 * 这里为它们建 Java 枚举以获得编译期校验，同时保留 {@link #wireName()}
 * 作为对外取值，保证序列化与规格逐字一致。
 */
public final class ManagementEnums {

    private ManagementEnums() {
    }

    /** 管理规格 {@code Catalog.type}。 */
    public enum CatalogType {
        INTERNAL,
        EXTERNAL;

        /** 按规格取值解析，未知取值返回空。 */
        public static Optional<CatalogType> parse(String value) {
            // 显式限定外层方法：签名同名的内部方法会遮蔽它
            return ManagementEnums.parse(CatalogType.class, value);
        }

        public String wireName() {
            return name();
        }
    }

    /** 管理规格 {@code StorageConfigInfo.storageType}。 */
    public enum StorageType {
        S3,
        GCS,
        AZURE,
        FILE;

        public static Optional<StorageType> parse(String value) {
            return ManagementEnums.parse(StorageType.class, value);
        }

        public String wireName() {
            return name();
        }
    }

    /**
     * 管理规格 {@code GrantResource.type}，即授权的资源类型判别值。
     *
     * <p>取值同时是 {@link Privilege#allowedFor(String)} 的键，因此这里的
     * {@link #wireName()} 必须与规格 discriminator 的 mapping 键逐字一致。
     */
    public enum GrantType {
        CATALOG("catalog"),
        NAMESPACE("namespace"),
        TABLE("table"),
        VIEW("view"),
        POLICY("policy"),
        SEMANTIC_MODEL("semantic-model");

        private final String wireName;

        GrantType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** 按规格取值解析，未知取值返回空。 */
        public static Optional<GrantType> parse(String value) {
            return Arrays.stream(values())
                    .filter(type -> type.wireName.equals(value))
                    .findFirst();
        }
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
