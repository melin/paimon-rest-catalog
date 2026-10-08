package com.example.paimonrest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 表 schema 的历史版本，用于 {@code rollback-schema}。
 */
@Entity
@Table(name = "paimon_table_schema",
        uniqueConstraints = @UniqueConstraint(name = "uk_table_schema", columnNames = {"table_id", "schema_id"}),
        indexes = @Index(name = "ix_table_schema_table", columnList = "table_id"))
@Getter
@Setter
public class TableSchemaVersionEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    /** 关联 {@link TableEntity#getId()}。 */
    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    @Column(name = "schema_id", nullable = false)
    private long schemaId;

    @Column(name = "schema_doc", length = 1048576)
    private String schemaDoc;

    @Column(name = "created_at", nullable = false)
    private long createdAt;
}
