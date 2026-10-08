package io.github.melin.paimonrest.domain.entity;

import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.support.Json;
import io.github.melin.paimonrest.support.Values;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA 属性转换器集合：把结构化元数据以 JSON 文本存入单个列，
 * 使实体字段保持强类型，同时避免为每个键建表。
 */
public final class JsonConverters {

    private JsonConverters() {
    }

    /** {@code Map<String,String>} ↔ JSON 文本。 */
    @Converter
    public static class StringMap implements AttributeConverter<Map<String, String>, String> {

        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            return attribute == null ? null : Json.write(attribute);
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return new LinkedHashMap<>();
            }
            return Values.stringMap(Json.read(dbData));
        }
    }

    /** {@code Map<String,Long>} ↔ JSON 文本。 */
    @Converter
    public static class LongMap implements AttributeConverter<Map<String, Long>, String> {

        @Override
        public String convertToDatabaseColumn(Map<String, Long> attribute) {
            return attribute == null ? null : Json.write(attribute);
        }

        @Override
        public Map<String, Long> convertToEntityAttribute(String dbData) {
            Map<String, Long> result = new LinkedHashMap<>();
            if (dbData == null || dbData.isBlank()) {
                return result;
            }
            Values.map(Json.read(dbData)).forEach((key, value) -> result.put(key, Values.longValue(value)));
            return result;
        }
    }

    /** {@code Map<String,Object>} ↔ JSON 文本，用于自由结构的对象（分区 spec 等）。 */
    @Converter
    public static class ObjectMap implements AttributeConverter<Map<String, Object>, String> {

        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            return attribute == null ? null : Json.write(attribute);
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return new LinkedHashMap<>();
            }
            return new LinkedHashMap<>(Values.map(Json.read(dbData)));
        }
    }

    /**
     * {@code List<String>} ↔ JSON 文本。
     *
     * <p>用于需要保序的多值字段，例如多级 namespace 路径
     * （{@code ["silver", "sales"]}）与存储允许位置列表。
     */
    @Converter
    public static class StringList implements AttributeConverter<List<String>, String> {

        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return attribute == null ? null : Json.write(attribute);
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return new ArrayList<>();
            }
            return Values.stringList(Json.read(dbData));
        }
    }

    /**
     * {@code StorageConfigInfo} ↔ JSON 文本。
     *
     * <p>存储配置是判别联合：字段集合随 {@code storageType} 变化（S3 有 {@code roleArn}，
     * Azure 有 {@code tenantId}，GCS 有 {@code gcsServiceAccount}），字段数量少但形态不固定。
     * 用单列 JSON 承载，实体字段保持强类型，也免去为每种存储各建一批列。
     *
     * <p>序列化时把运行时类型当作 {@code StorageConfigInfo}，因此 JSON 里会带上
     * {@code storageType} 判别字段——这正是反序列化时选回子类型的依据。
     */
    @Converter
    public static class StorageConfig implements AttributeConverter<StorageConfigInfo, String> {

        @Override
        public String convertToDatabaseColumn(StorageConfigInfo attribute) {
            return attribute == null ? null : Json.write(attribute);
        }

        @Override
        public StorageConfigInfo convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            return Json.read(dbData, StorageConfigInfo.class);
        }
    }
}
