package io.github.melin.paimonrest.domain.entity;

import io.github.melin.paimonrest.domain.entity.JsonConverters.LongMap;
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
 * 表快照。
 *
 * <p>同时承载规格里 {@code Snapshot} 与 {@code TableSnapshot} 两类信息：
 * {@code Snapshot} 是提交产物的清单指针，{@code TableSnapshot} 在其上补充了
 * 记录数、文件数与文件体积等聚合统计，这里合并存放。
 */
@Entity
@Table(name = "paimon_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_snapshot", columnNames = {"table_id", "snapshot_id"}),
        indexes = @Index(name = "ix_snapshot_table", columnList = "table_id"))
@Getter
@Setter
public class TableSnapshotEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    /** 规格中的 {@code Snapshot.id}。 */
    @Column(name = "snapshot_id", nullable = false)
    private long snapshotId;

    /** 规格中的 {@code Snapshot.version}，从 1 递增。 */
    @Column(name = "version_number")
    private Integer version;

    @Column(name = "uuid_value", length = 64)
    private String uuid;

    @Column(name = "schema_id", nullable = false)
    private long schemaId;

    @Column(name = "base_manifest_list", length = 1024)
    private String baseManifestList;

    @Column(name = "delta_manifest_list", length = 1024)
    private String deltaManifestList;

    @Column(name = "changelog_manifest_list", length = 1024)
    private String changelogManifestList;

    @Column(name = "index_manifest", length = 1024)
    private String indexManifest;

    @Column(name = "commit_user", length = 255)
    private String commitUser;

    @Column(name = "commit_identifier", length = 255)
    private String commitIdentifier;

    /** APPEND / COMPACT / OVERWRITE / ANALYZE。 */
    @Column(name = "commit_kind", length = 32)
    private String commitKind;

    @Column(name = "time_millis")
    private Long timeMillis;

    @Convert(converter = LongMap.class)
    @Column(name = "log_offsets_json", length = 65535)
    private Map<String, Long> logOffsets = new LinkedHashMap<>();

    @Column(name = "total_record_count")
    private Long totalRecordCount;

    @Column(name = "delta_record_count")
    private Long deltaRecordCount;

    @Column(name = "changelog_record_count")
    private Long changelogRecordCount;

    @Column(name = "watermark_value")
    private Long watermark;

    @Column(name = "statistics_doc", length = 1048576)
    private String statistics;

    @Column(name = "record_count")
    private Long recordCount;

    @Column(name = "file_size_in_bytes")
    private Long fileSizeInBytes;

    @Column(name = "file_count")
    private Long fileCount;

    @Column(name = "last_file_creation_time")
    private Long lastFileCreationTime;
}
