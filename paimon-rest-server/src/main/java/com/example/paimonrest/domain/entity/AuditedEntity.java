package com.example.paimonrest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * 带审计字段的实体基类。
 *
 * <p>时间戳统一为 epoch 毫秒，与规格中 {@code int64} 的 {@code createdAt} /
 * {@code updatedAt} 语义一致。{@code owner} / {@code createdBy} / {@code updatedBy}
 * 取自当前请求的认证主体。
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditedEntity {

    @Column(name = "owner")
    private String owner;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    /** 记录创建审计信息。 */
    public void markCreated(String principal, long now) {
        this.owner = principal;
        this.createdBy = principal;
        this.createdAt = now;
        touch(principal, now);
    }

    /** 记录更新审计信息。 */
    public void touch(String principal, long now) {
        this.updatedBy = principal;
        this.updatedAt = now;
    }
}
