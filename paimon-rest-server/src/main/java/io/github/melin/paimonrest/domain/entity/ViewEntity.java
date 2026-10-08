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
 * SQL 视图。
 *
 * <p>{@code viewSchemaDoc} 同时保存字段列表、默认查询语句与各方言查询语句，
 * 以 JSON 文档形式存放。
 */
@Entity
@Table(name = "paimon_view",
        uniqueConstraints = @UniqueConstraint(name = "uk_view", columnNames = {"catalog_id", "database_id", "name"}),
        indexes = @Index(name = "ix_view_database", columnList = "database_id"))
@Getter
@Setter
public class ViewEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false)
    private String databaseId;

    @Column(name = "name", length = 512, nullable = false)
    private String name;

    @Column(name = "view_schema_doc", length = 1048576)
    private String viewSchemaDoc;
}
