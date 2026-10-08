package io.github.melin.paimonrest.domain.entity;

import io.github.melin.paimonrest.domain.entity.JsonConverters.StringList;
import io.github.melin.paimonrest.domain.entity.JsonConverters.StringMap;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * catalog 实例。REST 路径中的 {@code {prefix}} 就是它的对外标识，
 * 由 {@code GET /v1/config?warehouse=...} 下发给客户端。
 *
 * <p>{@code defaults} 与 {@code overrides} 参与客户端的配置合并
 * （defaults → 客户端属性 → overrides，后者优先）。
 */
@Entity
@Table(name = "paimon_catalog",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_prefix", columnNames = "prefix_value"))
@Getter
@Setter
public class CatalogEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    /** 对外暴露的 prefix，客户端后续所有路径都以它开头。 */
    @Column(name = "prefix_value", length = 255, nullable = false)
    private String prefix;

    @Column(name = "warehouse", length = 1024)
    private String warehouse;

    @Convert(converter = StringMap.class)
    @Column(name = "defaults_json", length = 65535)
    private Map<String, String> defaults = new LinkedHashMap<>();

    @Convert(converter = StringMap.class)
    @Column(name = "overrides_json", length = 65535)
    private Map<String, String> overrides = new LinkedHashMap<>();

    /** INTERNAL：元数据由本服务托管；EXTERNAL：外部同步、只读。 */
    @Column(name = "catalog_type", length = 32)
    private String catalogType = "INTERNAL";

    /**
     * 管理 API 视角下的 catalog 属性。
     *
     * <p>与 {@code defaults} / {@code overrides} 分开存放：后两者参与客户端的
     * 配置合并，只对支持它们的引擎有意义；这里是 catalog 的通用属性，
     * 由 {@code /api/management/v1/catalogs} 读写。
     */
    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535)
    private Map<String, String> properties = new LinkedHashMap<>();

    /**
     * 管理规格 {@code StorageConfigInfo} 的完整形态，按 {@code storageType} 判别。
     *
     * <p>这是存储配置的唯一真相来源。下面两列（{@code storage_type}、
     * {@code allowed_locations_json}）是它的**投影**：写入时由同一个方法一起落库，
     * 保留它们是为了能直接在 SQL 里按存储类型与位置筛选 catalog，
     * 也便于管理员用肉眼核对。读取时一律以本字段为准。
     */
    @Convert(converter = JsonConverters.StorageConfig.class)
    @Column(name = "storage_config_json", length = 65535)
    private StorageConfigInfo storageConfig;

    /** {@code StorageConfigInfo.storageType} 的投影：存储实现类型。 */
    @Column(name = "storage_type", length = 32)
    private String storageType = "FILE";

    /** {@code StorageConfigInfo.allowedLocations} 的投影。 */
    @Convert(converter = StringList.class)
    @Column(name = "allowed_locations_json", length = 65535)
    private List<String> allowedLocations = new ArrayList<>();

    /** 乐观并发版本号，对应管理规格的 {@code entityVersion}。 */
    @Column(name = "entity_version", nullable = false)
    private int entityVersion;
}
