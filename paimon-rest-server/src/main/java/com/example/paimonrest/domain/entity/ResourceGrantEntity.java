package com.example.paimonrest.domain.entity;

import com.example.paimonrest.domain.entity.JsonConverters.StringList;
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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 「catalog role 在某个资源上拥有某项权限」。
 *
 * <p>对应 {@code PUT /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants}，
 * 即管理规格 {@code GrantResource} 多态联合的落库形态。规格用 {@code type} 判别六种资源，
 * 这里用三个字段等价表达：
 *
 * <ul>
 *   <li>{@code resourceType} —— 规格的判别字段 {@code type}；
 *   <li>{@code namespace} —— catalog 类为空列表，其余为多级命名空间路径；
 *   <li>{@code objectName} —— catalog 与 namespace 类为空串，其余为对象名。
 * </ul>
 *
 * <p>{@code namespaceKey} 是 {@code namespace} 的规范化拼接，用于等值定位：
 * 关系库中 {@code NULL} 不参与唯一性比较，因此可空语义统一用空串表达。
 *
 * <p>唯一约束落在 {@code namespaceHash} 而不是 {@code namespaceKey} 上：
 * 规格对命名空间的层级数没有上限，{@code namespaceKey} 的长度因此无上界，
 * 而 MySQL 的索引键上限是 3072 字节，直接索引会被
 * {@code ERROR 1071: Specified key was too long} 拒绝。
 */
@Entity
@Table(name = "paimon_resource_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_resource_grant",
                columnNames = {"catalog_role_id", "resource_type", "namespace_hash",
                        "object_name", "privilege"}),
        indexes = @Index(name = "ix_resource_grant_role", columnList = "catalog_role_id"))
@Getter
@Setter
public class ResourceGrantEntity extends AuditedEntity {

    /** 命名空间维度的分隔符，取 ASCII 单元分隔符，正常标识符不会包含。 */
    public static final String NAMESPACE_SEPARATOR = "\u001f";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_role_id", length = 64, nullable = false)
    private String catalogRoleId;

    /** 规格 {@code GrantResource.type}：catalog / namespace / table / view / policy / semantic-model。 */
    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType;

    @Convert(converter = StringList.class)
    @Column(name = "namespace_json", length = 4096)
    private List<String> namespace = new ArrayList<>();

    @Column(name = "namespace_key", length = 4096, nullable = false)
    private String namespaceKey = "";

    /** {@code namespaceKey} 的定长摘要，承担唯一约束；见 {@link Digests}。 */
    @Column(name = "namespace_hash", length = Digests.HEX_LENGTH, nullable = false)
    private String namespaceHash = "";

    @Column(name = "object_name", length = 255, nullable = false)
    private String objectName = "";

    @Column(name = "privilege", length = 64, nullable = false)
    private String privilege;

    /** 依据当前 {@code namespace} 重算 {@code namespaceKey}。 */
    public void normalizeNamespace() {
        this.namespaceKey = namespace == null || namespace.isEmpty()
                ? ""
                : String.join(NAMESPACE_SEPARATOR, namespace);
    }

    /**
     * 依据当前 {@code namespaceKey} 重算摘要。
     *
     * <p>放在生命周期回调里而不是 {@link #normalizeNamespace()} 里，
     * 是为了不依赖调用方是否记得先规范化：两条路径都赋值时，落库前摘要一定与之一致。
     */
    @PrePersist
    @PreUpdate
    void refreshNamespaceHash() {
        this.namespaceHash = Digests.sha256Hex(namespaceKey);
    }
}
