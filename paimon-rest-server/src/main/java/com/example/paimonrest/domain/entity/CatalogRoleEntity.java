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
 * catalog role（目录角色）。
 *
 * <p>对应管理规格的 {@code CatalogRole}。规格原文：catalog role
 * 「belongs to a particular catalog resource」，即角色归属于某个 catalog，
 * 权限授予发生在「该角色 + 该 catalog 内的资源」这一坐标系中。
 *
 * <p>因此本表以 {@code catalog_id} 归属 catalog：同名 catalog role 可以存在于
 * 不同 catalog（唯一约束是 {@code (catalog_id, name)} 而非全局唯一的 {@code name}），
 * 这一点与规格的路径 {@code /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}
 * 一致。
 */
@Entity
@Table(name = "paimon_catalog_role",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_role_name",
                columnNames = {"catalog_id", "name"}))
@Getter
@Setter
public class CatalogRoleEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    /** 所属 catalog 的主键，对应 {@link CatalogEntity#getId()}。 */
    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535)
    private Map<String, String> properties = new LinkedHashMap<>();

    @Column(name = "entity_version", nullable = false)
    private int entityVersion;
}
