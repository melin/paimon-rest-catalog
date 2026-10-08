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
 * 标签：给快照起的不可变名称，常用于长期保留某个可回滚的版本。
 */
@Entity
@Table(name = "paimon_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_tag", columnNames = {"table_id", "tag_name"}),
        indexes = @Index(name = "ix_tag_table", columnList = "table_id"))
@Getter
@Setter
public class TagEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    @Column(name = "tag_name", length = 512, nullable = false)
    private String tagName;

    @Column(name = "snapshot_id", nullable = false)
    private long snapshotId;

    @Column(name = "tag_create_time")
    private Long tagCreateTime;

    /** 保留时长，形如 {@code "1d"} / {@code "12h"} / {@code "30m"}。 */
    @Column(name = "tag_time_retained", length = 64)
    private String tagTimeRetained;
}
