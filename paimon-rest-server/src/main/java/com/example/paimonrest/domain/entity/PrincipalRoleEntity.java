package com.example.paimonrest.domain.entity;

import com.example.paimonrest.domain.entity.JsonConverters.StringMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * principal role（主体角色）。
 *
 * <p>对应管理规格的 {@code PrincipalRole}。它是主体与 catalog role 之间的中转层：
 * 权限不下发给 principal role，而是先把 catalog role 授予 principal role，
 * 再把 principal role 授予主体。多对多关系分别落在
 * {@link PrincipalRoleAssignmentEntity} 与 {@link CatalogRoleAssignmentEntity}。
 *
 * <p>{@code federated} 标记该角色是否来自外部身份提供方（规格字段）。
 * 本服务不接入外部 IdP，因此该字段只作为标记保存与回显。
 */
@Entity
@Table(name = "paimon_principal_role",
        uniqueConstraints = @UniqueConstraint(name = "uk_principal_role_name", columnNames = "name"))
@Getter
@Setter
public class PrincipalRoleEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "federated", nullable = false)
    private boolean federated;

    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535)
    private Map<String, String> properties = new LinkedHashMap<>();

    @Column(name = "entity_version", nullable = false)
    private int entityVersion;
}
