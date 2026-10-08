package io.github.melin.paimonrest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 语义视图定义。
 *
 * <p>与 SQL 视图是两类对象：语义视图的名称不出现在普通视图列表中，
 * 其 {@code content} 整篇保存、不做解析，单个定义上限 1 MiB UTF-8 字节。
 */
@Entity
@Table(name = "paimon_semantic_view",
        uniqueConstraints = @UniqueConstraint(name = "uk_semantic_view",
                columnNames = {"catalog_id", "database_id", "name"}),
        indexes = @Index(name = "ix_semantic_view_database", columnList = "database_id"))
@Getter
@Setter
public class SemanticViewEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false)
    private String databaseId;

    /** 语义视图名，允许含点号等字符。 */
    @Column(name = "name", length = 512, nullable = false)
    private String name;

    /** 模型文档格式，如 {@code databricks-yaml}、{@code snowflake-yaml}、{@code ossie-yaml}。 */
    @Column(name = "format_name", length = 255, nullable = false)
    private String format;

    @Column(name = "content_doc", length = 3000000, nullable = false)
    private String content;
}
