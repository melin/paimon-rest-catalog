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
 * 「principal role 授予主体」关系。
 *
 * <p>对应 {@code PUT /principals/{principalName}/principal-roles}。规格允许
 * principal 与 principal role 之间多对多，因此关系独立成表而不是放在任一侧。
 */
@Entity
@Table(name = "paimon_principal_role_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_principal_role_grant",
                columnNames = {"principal_id", "principal_role_id"}),
        indexes = @Index(name = "ix_principal_role_grant_role", columnList = "principal_role_id"))
@Getter
@Setter
public class PrincipalRoleAssignmentEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "principal_id", length = 64, nullable = false)
    private String principalId;

    @Column(name = "principal_role_id", length = 64, nullable = false)
    private String principalRoleId;
}
