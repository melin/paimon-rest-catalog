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
 * 「catalog role 授予 principal role」关系。
 *
 * <p>对应 {@code PUT /principal-roles/{principalRoleName}/catalog-roles/{catalogName}}。
 * catalog role 自身已经归属某个 catalog，因此这里不需要再记 catalog：
 * 归属关系由 {@link CatalogRoleEntity#getCatalogId()} 决定，
 * 路径中的 {@code catalogName} 用于校验一致性。
 */
@Entity
@Table(name = "paimon_catalog_role_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_role_grant",
                columnNames = {"principal_role_id", "catalog_role_id"}),
        indexes = @Index(name = "ix_catalog_role_grant_role", columnList = "catalog_role_id"))
@Getter
@Setter
public class CatalogRoleAssignmentEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "principal_role_id", length = 64, nullable = false)
    private String principalRoleId;

    @Column(name = "catalog_role_id", length = 64, nullable = false)
    private String catalogRoleId;
}
