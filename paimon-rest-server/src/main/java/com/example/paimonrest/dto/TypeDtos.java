package com.example.paimonrest.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 字段与 schema 相关 DTO，对应 OpenAPI 的 {@code DataField} / {@code Schema} / {@code ViewSchema}
 * 以及 {@code DataType} 联合类型。
 *
 * <p>{@code DataType} 在规格中是 {@code oneOf}：既可以是 {@code "INT"} 这样的裸字符串
 * （{@code PrimitiveType}），也可以是 {@code {"type":"ARRAY","element":...}} 形式的 object。
 * 由于两种形态差异过大，这里把字段的类型声明为 {@code Object} 原样承载，
 * 由 {@code SchemaSupport} 归一化为「裸字符串或规范化后的 Map」再参与变更计算。
 */
public final class TypeDtos {

    private TypeDtos() {
    }

    /** 字段定义。{@code type} 为裸字符串或已归一化的类型 Map。 */
    @Getter
    @Setter
    public static class DataField {
        private Integer id;
        private String name;
        private Object type;
        private String description;
        private String defaultValue;

        public DataField copy() {
            DataField copy = new DataField();
            copy.id = id;
            copy.name = name;
            copy.type = type;
            copy.description = description;
            copy.defaultValue = defaultValue;
            return copy;
        }
    }

    /** 表 schema。 */
    @Getter
    @Setter
    public static class Schema {
        private List<DataField> fields = new ArrayList<>();
        private List<String> partitionKeys = new ArrayList<>();
        private List<String> primaryKeys = new ArrayList<>();
        private Map<String, String> options = new LinkedHashMap<>();
        private String comment;
    }

    /** 视图 schema。 */
    @Getter
    @Setter
    public static class ViewSchema {
        private List<DataField> fields = new ArrayList<>();
        private String query;
        private Map<String, String> dialects = new LinkedHashMap<>();
        private String comment;
        private Map<String, String> options = new LinkedHashMap<>();
    }
}
