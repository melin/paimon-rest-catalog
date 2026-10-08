package com.example.paimonrest.domain.entity;

import com.example.paimonrest.domain.entity.JsonConverters.ObjectMap;
import com.example.paimonrest.domain.entity.JsonConverters.StringMap;
import com.example.paimonrest.support.Digests;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 分区。
 *
 * <p>{@code specKey} 是 spec 的规范化键，用于按分区定位与去重；
 * {@code specJson} 保存完整的分区字段映射。{@code done} 标记分区是否已
 * 完成（{@code markDonePartitions}），{@code options} 支持自定义路径等扩展。
 *
 * <p>唯一约束落在 {@code specHash} 而不是 {@code specKey} 上：规格未限制分区字段数量，
 * {@code specKey} 的长度没有上界，而 MySQL 的索引键上限是 3072 字节，
 * 直接索引会被 {@code ERROR 1071: Specified key was too long} 拒绝。
 */
@Entity
@Table(name = "paimon_partition",
        uniqueConstraints = @UniqueConstraint(name = "uk_partition", columnNames = {"table_id", "spec_hash"}),
        indexes = @Index(name = "ix_partition_table", columnList = "table_id"))
@Getter
@Setter
public class PartitionEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    /** 规范化后的 spec 文本，用于按分区等值定位。 */
    @Column(name = "spec_key", length = 1024, nullable = false)
    private String specKey;

    /** {@code specKey} 的定长摘要，承担唯一约束；见 {@link Digests}。 */
    @Column(name = "spec_hash", length = Digests.HEX_LENGTH, nullable = false)
    private String specHash = "";

    @Convert(converter = ObjectMap.class)
    @Column(name = "spec_json", length = 65535)
    private Map<String, Object> spec = new LinkedHashMap<>();

    @Column(name = "record_count")
    private Long recordCount;

    @Column(name = "file_size_in_bytes")
    private Long fileSizeInBytes;

    @Column(name = "file_count")
    private Long fileCount;

    @Column(name = "last_file_creation_time")
    private Long lastFileCreationTime;

    @Column(name = "total_buckets")
    private Integer totalBuckets;

    @Column(name = "done_flag", nullable = false)
    private boolean done;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535)
    private Map<String, String> options = new LinkedHashMap<>();

    /**
     * 依据当前 {@code specKey} 重算摘要。
     *
     * <p>放在生命周期回调里而不是 setter 里，是为了不依赖调用方是否记得调用
     * {@code normalize}：无论 {@code specKey} 从哪条路径被赋值，落库前摘要一定会被刷新。
     */
    @PrePersist
    @PreUpdate
    void refreshSpecHash() {
        this.specHash = Digests.sha256Hex(specKey);
    }
}
