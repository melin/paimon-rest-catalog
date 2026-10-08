package io.github.melin.paimonrest.domain.entity;

import io.github.melin.paimonrest.domain.entity.JsonConverters.StringMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 函数定义。支持 {@code sql} / {@code file} / {@code lambda} 三种定义，
 * 按定义名保存在 JSON 文档中。
 */
@Entity
@Table(name = "paimon_function",
        uniqueConstraints = @UniqueConstraint(name = "uk_function", columnNames = {"catalog_id", "database_id", "name"}),
        indexes = @Index(name = "ix_function_database", columnList = "database_id"))
@Getter
@Setter
public class FunctionEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false)
    private String databaseId;

    @Column(name = "name", length = 512, nullable = false)
    private String name;

    @Column(name = "input_params_doc", length = 1048576)
    private String inputParamsDoc;

    @Column(name = "return_params_doc", length = 1048576)
    private String returnParamsDoc;

    @Column(name = "definitions_doc", length = 1048576)
    private String definitionsDoc;

    @Column(name = "deterministic_flag")
    private Boolean deterministic;

    @Column(name = "comment_text", length = 4096)
    private String comment;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535)
    private Map<String, String> options = new LinkedHashMap<>();
}
