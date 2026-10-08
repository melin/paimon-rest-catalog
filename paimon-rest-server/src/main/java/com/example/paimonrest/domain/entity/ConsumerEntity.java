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
 * 流式消费进度：记录某个消费者下一次应读取的快照 id。
 */
@Entity
@Table(name = "paimon_consumer",
        uniqueConstraints = @UniqueConstraint(name = "uk_consumer", columnNames = {"table_id", "consumer_id"}),
        indexes = @Index(name = "ix_consumer_table", columnList = "table_id"))
@Getter
@Setter
public class ConsumerEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    @Column(name = "consumer_id", length = 512, nullable = false)
    private String consumerId;

    @Column(name = "next_snapshot")
    private Long nextSnapshot;

    @Column(name = "updated_at")
    private Long updatedAt;
}
