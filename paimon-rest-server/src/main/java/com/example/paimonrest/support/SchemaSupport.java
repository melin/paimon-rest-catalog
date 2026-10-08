package com.example.paimonrest.support;

import com.example.paimonrest.dto.TypeDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表/视图 schema 的归一化与字段树操作。
 *
 * <p>规格里的 {@code DataType} 是联合类型：基本类型是裸字符串，复杂类型是带 {@code type}
 * 判别字段的 object。这里把入参统一归一化为「裸字符串或规范化 Map」两种形态，并保证
 * 所有层级的 {@code fields} 都是 {@link TypeDtos.DataField} 对象列表，使后续变更可以就地修改。
 *
 * <p>可空性（nullability）在规格的 {@code DataField} 中没有独立字段，
 * 因此按 Paimon 的惯例编码在类型名中，形如 {@code "INT NOT NULL"}。
 * 这也是 {@code updateColumnType} 需要携带 {@code keepNullability} 参数的原因。
 */
public final class SchemaSupport {

    private static final String NOT_NULL = "NOT NULL";

    private SchemaSupport() {
    }

    // ------------------------------------------------------------------ 归一化

    public static void normalize(TypeDtos.Schema schema) {
        if (schema.getFields() == null) {
            schema.setFields(new ArrayList<>());
        }
        if (schema.getPartitionKeys() == null) {
            schema.setPartitionKeys(new ArrayList<>());
        }
        if (schema.getPrimaryKeys() == null) {
            schema.setPrimaryKeys(new ArrayList<>());
        }
        if (schema.getOptions() == null) {
            schema.setOptions(new LinkedHashMap<>());
        }
        normalizeFields(schema.getFields());
    }

    public static void normalize(TypeDtos.ViewSchema schema) {
        if (schema.getFields() == null) {
            schema.setFields(new ArrayList<>());
        }
        if (schema.getDialects() == null) {
            schema.setDialects(new LinkedHashMap<>());
        }
        if (schema.getOptions() == null) {
            schema.setOptions(new LinkedHashMap<>());
        }
        normalizeFields(schema.getFields());
    }

    public static void normalizeFields(List<TypeDtos.DataField> fields) {
        if (fields == null) {
            return;
        }
        for (TypeDtos.DataField field : fields) {
            if (field != null && field.getType() != null) {
                field.setType(normalizeType(field.getType()));
            }
        }
    }

    /** 把类型统一为裸字符串（基本类型）或规范化 Map（复杂类型）。 */
    public static Object normalizeType(Object type) {
        if (type == null || type instanceof String) {
            return type;
        }
        if (!(type instanceof Map<?, ?> raw)) {
            // 非法形态（例如数字）保留原值，由上层校验
            return type;
        }
        Map<String, Object> source = Values.map(raw);
        String name = Values.string(source.get("type"));
        if (name == null) {
            return new LinkedHashMap<>(source);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", name);
        if (upper.startsWith("ARRAY") || upper.startsWith("MULTISET")) {
            normalized.put("element", normalizeType(source.get("element")));
        } else if (upper.startsWith("MAP")) {
            normalized.put("key", normalizeType(source.get("key")));
            normalized.put("value", normalizeType(source.get("value")));
        } else if (upper.startsWith("ROW")) {
            normalized.put("fields", toFields(source.get("fields")));
        } else if (upper.startsWith("VECTOR")) {
            normalized.put("element", normalizeType(source.get("element")));
            normalized.put("length", Values.integer(source.get("length")));
        } else {
            return new LinkedHashMap<>(source);
        }
        return normalized;
    }

    public static List<TypeDtos.DataField> toFields(Object value) {
        List<TypeDtos.DataField> fields = new ArrayList<>();
        if (value != null) {
            for (Object item : Values.list(value)) {
                TypeDtos.DataField field = toField(item);
                if (field != null) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    public static TypeDtos.DataField toField(Object item) {
        if (item instanceof TypeDtos.DataField field) {
            return field;
        }
        if (item instanceof Map<?, ?> raw) {
            Map<String, Object> map = Values.map(raw);
            TypeDtos.DataField field = new TypeDtos.DataField();
            field.setId(Values.integer(map.get("id")));
            field.setName(Values.string(map.get("name")));
            field.setType(normalizeType(map.get("type")));
            field.setDescription(Values.string(map.get("description")));
            field.setDefaultValue(Values.string(map.get("defaultValue")));
            return field;
        }
        return null;
    }

    // ------------------------------------------------------------------ 类型读取

    /** 取类型的显示名；基本类型即其字符串本身。 */
    public static String typeName(Object type) {
        if (type instanceof String s) {
            return s;
        }
        if (type instanceof Map<?, ?> raw) {
            return Values.string(Values.map(raw).get("type"));
        }
        return null;
    }

    public static boolean isRow(Object type) {
        String name = typeName(type);
        return name != null && name.toUpperCase(Locale.ROOT).startsWith("ROW");
    }

    /**
     * 取 ROW 类型的子字段列表（活动引用，可就地修改）。
     *
     * @return 非 ROW 类型返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static List<TypeDtos.DataField> rowFields(Object type) {
        if (!(type instanceof Map<?, ?> raw)) {
            return null;
        }
        Object fields = Values.map(raw).get("fields");
        if (fields instanceof List<?> list) {
            return (List<TypeDtos.DataField>) list;
        }
        return null;
    }

    // ------------------------------------------------------------------ 字段树查找

    public static TypeDtos.DataField find(List<TypeDtos.DataField> fields, String name) {
        if (fields == null) {
            return null;
        }
        for (TypeDtos.DataField field : fields) {
            if (field != null && name.equals(field.getName())) {
                return field;
            }
        }
        return null;
    }

    public static int indexOf(List<TypeDtos.DataField> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            TypeDtos.DataField field = fields.get(i);
            if (field != null && name.equals(field.getName())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 按字段路径逐层定位末端字段。
     *
     * @return 路径不存在时返回 {@code null}
     */
    public static TypeDtos.DataField resolve(List<TypeDtos.DataField> root, List<String> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        List<TypeDtos.DataField> current = root;
        TypeDtos.DataField found = null;
        for (String name : path) {
            found = find(current, name);
            if (found == null) {
                return null;
            }
            if (!name.equals(path.get(path.size() - 1))) {
                current = rowFields(found.getType());
                if (current == null) {
                    return null;
                }
            }
        }
        return found;
    }

    /**
     * 定位末端字段所在的字段列表（即其父容器的子字段表）。
     *
     * @return 中间层级不是 ROW 时返回 {@code null}
     */
    public static List<TypeDtos.DataField> parentList(List<TypeDtos.DataField> root, List<String> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        List<TypeDtos.DataField> current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            TypeDtos.DataField field = find(current, path.get(i));
            if (field == null) {
                return null;
            }
            current = rowFields(field.getType());
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** 递归计算字段树中已使用的最大字段 id。 */
    public static int maxFieldId(List<TypeDtos.DataField> fields) {
        int max = 0;
        if (fields == null) {
            return max;
        }
        for (TypeDtos.DataField field : fields) {
            if (field == null) {
                continue;
            }
            if (field.getId() != null && field.getId() > max) {
                max = field.getId();
            }
            List<TypeDtos.DataField> children = rowFields(field.getType());
            if (children != null) {
                max = Math.max(max, maxFieldId(children));
            }
        }
        return max;
    }

    // ------------------------------------------------------------------ 可空性

    public static boolean isNotNull(String typeName) {
        return typeName != null && typeName.toUpperCase(Locale.ROOT).contains(NOT_NULL);
    }

    /** 按可空性改写类型名：{@code nullable=false} 追加 {@code NOT NULL} 标记。 */
    public static String applyNullability(String typeName, boolean nullable) {
        if (typeName == null) {
            return null;
        }
        String base = typeName;
        int index = base.toUpperCase(Locale.ROOT).lastIndexOf(NOT_NULL);
        if (index >= 0) {
            base = base.substring(0, index).trim();
        }
        return nullable ? base : base + " " + NOT_NULL;
    }

    // ------------------------------------------------------------------ 位置调整

    /**
     * 在兄弟字段列表中按 {@code move} 描述重排字段。
     *
     * <p>{@code move} 字段：{@code fieldName}、{@code referenceFieldName}、{@code type}
     * （{@code before} / {@code after} / {@code first}）。{@code referenceFieldName} 为空
     * 或 {@code type} 为 {@code first} 时移到首位。
     */
    public static void applyMove(List<TypeDtos.DataField> siblings, Map<String, Object> move) {
        String fieldName = Values.string(move.get("fieldName"));
        if (fieldName == null) {
            throw ApiException.badRequest("Move requires fieldName");
        }
        int index = indexOf(siblings, fieldName);
        if (index < 0) {
            throw ApiException.badRequest("Column does not exist: " + fieldName);
        }
        TypeDtos.DataField target = siblings.remove(index);

        String reference = Values.string(move.get("referenceFieldName"));
        String type = Values.string(move.get("type"), "");
        if (reference == null || reference.isBlank() || "first".equalsIgnoreCase(type)) {
            siblings.add(0, target);
            return;
        }
        int referenceIndex = indexOf(siblings, reference);
        if (referenceIndex < 0) {
            throw ApiException.badRequest("Reference column does not exist: " + reference);
        }
        if ("after".equalsIgnoreCase(type)) {
            siblings.add(referenceIndex + 1, target);
        } else {
            siblings.add(referenceIndex, target);
        }
    }
}
