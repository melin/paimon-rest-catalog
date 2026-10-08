package com.example.paimonrest.service;

import com.example.paimonrest.dto.TypeDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.SchemaSupport;
import com.example.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 表 schema 变更执行器。
 *
 * <p>规格中的 {@code SchemaChange} 是以 {@code action} 为判别字段的多态联合，
 * 共 12 种变更。请求体按 {@code List<Map<String,Object>>} 承载后在此分派，
 * 逐条应用到一个可变 schema 上；未知 action 返回 400。
 */
@Service
public class SchemaChangeService {

    private static final String ACTION = "action";

    /**
     * 应用一组变更。
     *
     * @param schema  目标 schema（就地修改）
     * @param changes 变更列表，为空时不做任何事
     */
    public void apply(TypeDtos.Schema schema, List<Map<String, Object>> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        for (Map<String, Object> change : changes) {
            applyOne(schema, Values.map(change));
        }
        SchemaSupport.normalize(schema);
    }

    private void applyOne(TypeDtos.Schema schema, Map<String, Object> change) {
        String action = Values.string(change.get(ACTION));
        if (action == null || action.isBlank()) {
            throw ApiException.badRequest("Schema change requires an action");
        }
        switch (action) {
            case "setOption" -> schema.getOptions().put(requireKey(change), Values.string(change.get("value")));
            case "removeOption" -> schema.getOptions().remove(requireKey(change));
            case "updateComment" -> schema.setComment(Values.string(change.get("comment")));
            case "addColumn" -> addColumn(schema, change);
            case "renameColumn" -> renameColumn(schema, change);
            case "dropColumn" -> dropColumn(schema, change);
            case "updateColumnComment" -> updateColumn(schema, change,
                    field -> field.setDescription(Values.string(change.get("newComment"))));
            case "updateColumnDefaultValue" -> updateColumn(schema, change,
                    field -> field.setDefaultValue(Values.string(change.get("newDefaultValue"))));
            case "updateColumnType" -> updateColumnType(schema, change);
            case "updateColumnPosition" -> updateColumnPosition(schema, change);
            case "updateColumnNullability" -> updateColumnNullability(schema, change);
            case "dropPrimaryKey" -> schema.setPrimaryKeys(new ArrayList<>());
            default -> throw ApiException.badRequest("Unsupported schema change action: " + action);
        }
    }

    // ------------------------------------------------------------------ 列变更

    private void addColumn(TypeDtos.Schema schema, Map<String, Object> change) {
        List<String> path = fieldPath(change);
        List<TypeDtos.DataField> parent = SchemaSupport.parentList(schema.getFields(), path);
        if (parent == null) {
            throw ApiException.badRequest("Parent column does not exist: " + String.join(".", path));
        }
        String name = path.get(path.size() - 1);
        if (SchemaSupport.find(parent, name) != null) {
            throw ApiException.badRequest("Column already exists: " + name);
        }
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(SchemaSupport.maxFieldId(schema.getFields()) + 1);
        field.setName(name);
        field.setType(SchemaSupport.normalizeType(change.get("dataType")));
        field.setDescription(Values.string(change.get("comment")));
        parent.add(field);

        Map<String, Object> move = Values.map(change.get("move"));
        if (!move.isEmpty()) {
            Map<String, Object> effective = new LinkedHashMap<>(move);
            effective.putIfAbsent("fieldName", name);
            SchemaSupport.applyMove(parent, effective);
        }
    }

    private void renameColumn(TypeDtos.Schema schema, Map<String, Object> change) {
        List<String> path = fieldPath(change);
        TypeDtos.DataField field = SchemaSupport.resolve(schema.getFields(), path);
        if (field == null) {
            throw ApiException.badRequest("Column does not exist: " + String.join(".", path));
        }
        String newName = Values.string(change.get("newName"));
        if (newName == null || newName.isBlank()) {
            throw ApiException.badRequest("renameColumn requires newName");
        }
        String oldName = field.getName();
        field.setName(newName);
        if (path.size() == 1) {
            replace(schema.getPartitionKeys(), oldName, newName);
            replace(schema.getPrimaryKeys(), oldName, newName);
        }
    }

    private void dropColumn(TypeDtos.Schema schema, Map<String, Object> change) {
        List<String> path = fieldPath(change);
        List<TypeDtos.DataField> parent = SchemaSupport.parentList(schema.getFields(), path);
        String name = path.get(path.size() - 1);
        if (parent == null || SchemaSupport.indexOf(parent, name) < 0) {
            throw ApiException.badRequest("Column does not exist: " + String.join(".", path));
        }
        parent.remove(SchemaSupport.indexOf(parent, name));
        if (path.size() == 1) {
            schema.getPartitionKeys().remove(name);
            schema.getPrimaryKeys().remove(name);
        }
    }

    private void updateColumn(TypeDtos.Schema schema, Map<String, Object> change,
                              Consumer<TypeDtos.DataField> mutator) {
        List<String> path = fieldPath(change);
        TypeDtos.DataField field = SchemaSupport.resolve(schema.getFields(), path);
        if (field == null) {
            throw ApiException.badRequest("Column does not exist: " + String.join(".", path));
        }
        mutator.accept(field);
    }

    private void updateColumnType(TypeDtos.Schema schema, Map<String, Object> change) {
        List<String> path = fieldPath(change);
        TypeDtos.DataField field = SchemaSupport.resolve(schema.getFields(), path);
        if (field == null) {
            throw ApiException.badRequest("Column does not exist: " + String.join(".", path));
        }
        Object newType = SchemaSupport.normalizeType(change.get("newDataType"));
        if (newType == null) {
            throw ApiException.badRequest("updateColumnType requires newDataType");
        }
        // 可空性编码在类型名中，keepNullability 决定是否沿用原有标记
        boolean keepNullability = Values.bool(change.get("keepNullability"), true);
        if (newType instanceof String typeName
                && keepNullability
                && SchemaSupport.isNotNull(SchemaSupport.typeName(field.getType()))) {
            newType = SchemaSupport.applyNullability(typeName, false);
        }
        field.setType(newType);
    }

    private void updateColumnNullability(TypeDtos.Schema schema, Map<String, Object> change) {
        List<String> path = fieldPath(change);
        TypeDtos.DataField field = SchemaSupport.resolve(schema.getFields(), path);
        if (field == null) {
            throw ApiException.badRequest("Column does not exist: " + String.join(".", path));
        }
        if (!(field.getType() instanceof String typeName)) {
            throw ApiException.badRequest(
                    "Nullability is tracked in the type name and is only supported for primitive column types");
        }
        boolean nullable = Values.bool(change.get("newNullability"), true);
        field.setType(SchemaSupport.applyNullability(typeName, nullable));
    }

    private void updateColumnPosition(TypeDtos.Schema schema, Map<String, Object> change) {
        Map<String, Object> move = Values.map(change.get("move"));
        String fieldName = Values.string(move.get("fieldName"));
        if (fieldName == null || fieldName.isBlank()) {
            throw ApiException.badRequest("updateColumnPosition requires move.fieldName");
        }
        Map<String, Object> effective = new LinkedHashMap<>(move);
        List<TypeDtos.DataField> siblings;
        if (fieldName.contains(".")) {
            List<String> path = new ArrayList<>(List.of(fieldName.split("\\.")));
            siblings = SchemaSupport.parentList(schema.getFields(), path);
            if (siblings == null) {
                throw ApiException.badRequest("Column does not exist: " + fieldName);
            }
            effective.put("fieldName", path.get(path.size() - 1));
        } else {
            siblings = schema.getFields();
        }
        SchemaSupport.applyMove(siblings, effective);
    }

    // ------------------------------------------------------------------ 辅助

    private List<String> fieldPath(Map<String, Object> change) {
        List<String> fieldNames = Values.stringList(change.get("fieldNames"));
        if (fieldNames.isEmpty()) {
            throw ApiException.badRequest("Schema change requires a non-empty fieldNames");
        }
        return fieldNames;
    }

    private String requireKey(Map<String, Object> change) {
        String key = Values.string(change.get("key"));
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("Schema change requires a non-blank key");
        }
        return key;
    }

    private void replace(List<String> names, String oldName, String newName) {
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            if (oldName.equals(names.get(i))) {
                names.set(i, newName);
            }
        }
    }
}
