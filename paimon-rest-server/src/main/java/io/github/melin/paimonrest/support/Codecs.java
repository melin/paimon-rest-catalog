package io.github.melin.paimonrest.support;

import io.github.melin.paimonrest.dto.TypeDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化元数据与列内 JSON 文本之间的编解码。
 *
 * <p>所有写入数据库的 schema / 字段列表 / 类型树，都在此处统一归一化，
 * 保证「读回来的对象」与「请求体构造出来的对象」形态一致。
 */
public final class Codecs {

    private Codecs() {
    }

    public static String write(TypeDtos.Schema schema) {
        return Json.write(schema);
    }

    public static String write(TypeDtos.ViewSchema schema) {
        return Json.write(schema);
    }

    public static String writeFields(List<TypeDtos.DataField> fields) {
        return Json.write(fields == null ? List.of() : fields);
    }

    public static String writeObjectMap(Map<String, Object> map) {
        return Json.write(map == null ? new LinkedHashMap<>() : map);
    }

    /** 读取表 schema 文档；空串返回一个空 schema。 */
    public static TypeDtos.Schema readSchema(String doc) {
        if (doc == null || doc.isBlank()) {
            return new TypeDtos.Schema();
        }
        TypeDtos.Schema schema = Json.read(doc, TypeDtos.Schema.class);
        SchemaSupport.normalize(schema);
        return schema;
    }

    /** 读取视图 schema 文档；空串返回一个空 schema。 */
    public static TypeDtos.ViewSchema readViewSchema(String doc) {
        if (doc == null || doc.isBlank()) {
            return new TypeDtos.ViewSchema();
        }
        TypeDtos.ViewSchema schema = Json.read(doc, TypeDtos.ViewSchema.class);
        SchemaSupport.normalize(schema);
        return schema;
    }

    /** 读取字段列表文档。 */
    public static List<TypeDtos.DataField> readFields(String doc) {
        if (doc == null || doc.isBlank()) {
            return new ArrayList<>();
        }
        return SchemaSupport.toFields(Json.read(doc));
    }

    /** 读取自由对象（分区 spec、函数定义等）。 */
    public static Map<String, Object> readObjectMap(String doc) {
        if (doc == null || doc.isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(Values.map(Json.read(doc)));
    }

    /** 深拷贝字段列表，避免历史版本与当前 schema 共享可变对象。 */
    public static List<TypeDtos.DataField> copyFields(List<TypeDtos.DataField> fields) {
        List<TypeDtos.DataField> copy = new ArrayList<>();
        if (fields != null) {
            for (TypeDtos.DataField field : fields) {
                if (field != null) {
                    copy.add(field.copy());
                }
            }
        }
        return copy;
    }
}
