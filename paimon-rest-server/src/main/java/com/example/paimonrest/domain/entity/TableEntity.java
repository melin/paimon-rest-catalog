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
 * 表实体。
 *
 * <p>{@code id} 即规格 {@code GetTableResponse.id}，也是
 * {@code GET /v1/{prefix}/tables/id/{tableId}} 使用的标识。
 * 当前 schema 冗余存放在 {@code schema_doc}，历史版本在
 * {@link TableSchemaVersionEntity}，以支持 {@code rollback-schema}。
 */
@Entity
@Table(name = "paimon_table",
        uniqueConstraints = @UniqueConstraint(name = "uk_table", columnNames = {"catalog_id", "database_id", "name"}),
        indexes = {
                @Index(name = "ix_table_catalog", columnList = "catalog_id"),
                @Index(name = "ix_table_database", columnList = "database_id")
        })
@Getter
@Setter
public class TableEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false)
    private String databaseId;

    @Column(name = "name", length = 512, nullable = false)
    private String name;

    /** 表的物理根路径。 */
    @Column(name = "path_value", length = 1024)
    private String path;

    /** 外部注册（register）而来的表标记为 true。 */
    @Column(name = "external_flag", nullable = false)
    private boolean external;

    /** 当前 schema 版本号，从 0 递增。 */
    @Column(name = "schema_id", nullable = false)
    private long schemaId;

    /** 当前 schema 的 JSON 文档。 */
    @Column(name = "schema_doc", length = 1048576)
    private String schemaDoc;

    /** 表类型，取自 schema options 的 {@code type}，默认 PAIMON。 */
    @Column(name = "table_type", length = 64)
    private String tableType = "PAIMON";

    /** 最新快照 id / uuid，未提交过快照时为 null。 */
    @Column(name = "latest_snapshot_id")
    private Long latestSnapshotId;

    @Column(name = "latest_snapshot_uuid", length = 64)
    private String latestSnapshotUuid;
}
