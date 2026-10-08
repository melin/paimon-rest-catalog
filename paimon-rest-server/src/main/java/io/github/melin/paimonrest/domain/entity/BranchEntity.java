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
 * 表分支：指向某个快照的命名历史线，供流式写入与多路消费使用。
 */
@Entity
@Table(name = "paimon_branch",
        uniqueConstraints = @UniqueConstraint(name = "uk_branch", columnNames = {"table_id", "name"}),
        indexes = @Index(name = "ix_branch_table", columnList = "table_id"))
@Getter
@Setter
public class BranchEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    @Column(name = "name", length = 512, nullable = false)
    private String name;

    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535)
    private Map<String, String> options = new LinkedHashMap<>();
}
