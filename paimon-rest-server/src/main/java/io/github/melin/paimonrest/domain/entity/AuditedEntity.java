package io.github.melin.paimonrest.domain.entity;

import io.github.melin.paimonrest.support.AuditPrincipal;
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
 *
 * <p><b>三个主体名列的长度上限是 255，且这里就是收口点。</b>主体名在此之前可能很长
 * （认不出的令牌会退化为「令牌即主体名」，控制台访问令牌就有 272 个字符），
 * 直接写库会以 {@code Data too long for column 'created_by'} 失败——把业务写入
 * 打挂在审计字段上显然不对。因此写入前一律经 {@link AuditPrincipal#of} 归一化，
 * 任何调用方传什么进来都不会越过列宽。列宽显式写出来（而不依赖 Hibernate 的默认值），
 * 是为了让「实体声明」与 {@code sql/schema-mysql.sql} 里的 {@code varchar(255)}
 * 之间的对应关系在代码里看得见。
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditedEntity {

    @Column(name = "owner", length = AuditPrincipal.MAX_LENGTH)
    private String owner;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "created_by", length = AuditPrincipal.MAX_LENGTH)
    private String createdBy;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "updated_by", length = AuditPrincipal.MAX_LENGTH)
    private String updatedBy;

    /** 记录创建审计信息。 */
    public void markCreated(String principal, long now) {
        String name = AuditPrincipal.of(principal);
        this.owner = name;
        this.createdBy = name;
        this.createdAt = now;
        // 传已归一化的值：of 幂等，不会再摘一次
        touch(name, now);
    }

    /** 记录更新审计信息。 */
    public void touch(String principal, long now) {
        this.updatedBy = AuditPrincipal.of(principal);
        this.updatedAt = now;
    }
}
