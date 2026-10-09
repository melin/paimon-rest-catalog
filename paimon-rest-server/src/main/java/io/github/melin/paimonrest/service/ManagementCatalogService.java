package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.CatalogRoleEntity;
import io.github.melin.paimonrest.domain.entity.ResourceGrantEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleRepository;
import io.github.melin.paimonrest.domain.repo.ResourceGrantRepository;
import io.github.melin.paimonrest.dto.ManagementDtos.Catalog;
import io.github.melin.paimonrest.dto.ManagementDtos.Catalogs;
import io.github.melin.paimonrest.dto.ManagementDtos.CreateCatalogRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdateCatalogRequest;
import io.github.melin.paimonrest.dto.ManagementEnums.CatalogType;
import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.dto.ManagementEnums.StorageType;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.CredentialCipher;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.ResourceType;
import io.github.melin.paimonrest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理 API 的 catalog 读写。
 *
 * <p>catalog 在管理面与 catalog 面是同一个对象，只是暴露的名字不同：
 * 管理规格的 {@code Catalog.name} 就是 catalog API 路径里的 {@code {prefix}}。
 * 因此这里直接操作 {@link CatalogEntity}，不另建实体。
 *
 * <p><b>存储配置的落库方式。</b>{@code storageConfigInfo} 是判别联合
 * （{@code S3} / {@code AZURE} / {@code GCS} / {@code FILE}，字段集合各不相同），
 * 因此整份配置以 JSON 存进 {@link CatalogEntity#getStorageConfig()}，
 * 并把 {@code storageType} 与 {@code allowedLocations} 再写一份到投影列，
 * 便于直接用 SQL 按存储类型和位置筛选。
 *
 * <p>仓库位置取自 {@code allowedLocations} 的首项（{@link StorageConfigs#warehouseOf}）：
 * 表路径模板要用它拼路径，所以即使调用方没给位置，也会补上配置里的默认仓库。
 *
 * <p><b>静态凭据由本服务收口。</b>写路径在落库前把明文密钥密封（{@link CredentialCipher}），
 * 读路径在返回前抹掉密文（{@link StorageConfigs#withoutSecrets}）——
 * 一写一读都经过这里，是因为 catalog 的读写只在这一个服务上；
 * 两个方向各留一处，脱敏就不会漏在别的装配点上。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagementCatalogService {

    /** 建 catalog 时预置的 catalog role 名，与 Polaris 默认角色同名。 */
    public static final String DEFAULT_CATALOG_ROLE = "catalog_admin";

    private final CatalogRepository catalogRepository;
    private final CatalogRoleRepository catalogRoleRepository;
    private final CatalogRoleAssignmentRepository catalogRoleAssignmentRepository;
    private final ResourceGrantRepository resourceGrantRepository;
    private final DatabaseService databaseService;
    private final RestServerProperties properties;
    private final StorageRuntimePolicy storagePolicy;
    private final CredentialCipher cipher;

    /** {@code GET /catalogs}。 */
    @Transactional(readOnly = true)
    public Catalogs list() {
        List<Catalog> result = new ArrayList<>();
        for (CatalogEntity entity : catalogRepository.findAll()) {
            result.add(toDto(entity));
        }
        return new Catalogs(result);
    }

    /** {@code POST /catalogs}。 */
    @Transactional
    public Catalog create(CreateCatalogRequest request) {
        if (request == null || request.catalog() == null) {
            throw ApiException.badRequest("catalog is required");
        }
        Catalog inbound = request.catalog();
        String name = inbound.name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("catalog.name is required");
        }
        if (catalogRepository.findByPrefix(name).isPresent()) {
            throw ApiException.managementAlreadyExist(ResourceType.CATALOG, name);
        }

        String type = inbound.type() == null ? CatalogType.INTERNAL.wireName()
                : CatalogType.parse(inbound.type())
                .orElseThrow(() -> ApiException.badRequest("Unsupported catalog.type: " + inbound.type()))
                .wireName();

        CatalogEntity entity = new CatalogEntity();
        entity.setId(Paging.newId());
        entity.setPrefix(name);
        entity.setCatalogType(type);
        applyStorage(entity, inbound.storageConfigInfo(), null);
        entity.setProperties(inbound.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(inbound.properties()));
        entity.setDefaults(new LinkedHashMap<>());
        entity.setOverrides(new LinkedHashMap<>());
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        CatalogEntity saved = catalogRepository.save(entity);

        provisionDefaultCatalogRole(saved);
        return toDto(saved);
    }

    /** {@code GET /catalogs/{catalogName}}。 */
    @Transactional(readOnly = true)
    public Catalog get(String name) {
        return toDto(require(name));
    }

    /** {@code PUT /catalogs/{catalogName}}。 */
    @Transactional
    public Catalog update(String name, UpdateCatalogRequest request) {
        CatalogEntity entity = require(name);
        if (request == null) {
            throw ApiException.badRequest("request body is required");
        }
        // 规格把 currentEntityVersion 声明为可选：给了就必须匹配，没给就跳过校验
        if (request.currentEntityVersion() != null
                && request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.CATALOG, name,
                    request.currentEntityVersion(), entity.getEntityVersion());
        }
        if (request.properties() != null) {
            entity.setProperties(new LinkedHashMap<>(request.properties()));
        }
        if (request.storageConfigInfo() != null) {
            applyStorage(entity, request.storageConfigInfo(), StorageConfigs.of(entity));
        }
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        return toDto(catalogRepository.save(entity));
    }

    /**
     * {@code DELETE /catalogs/{catalogName}}。
     *
     * <p>级联清理该 catalog 下的全部从属对象：database（及其表、分区、视图、函数、语义视图）、
     * catalog role 及其授权与角色分配。规格没有 cascade 参数，这里按级联实现，
     * 与 catalog API 的 {@code DELETE /databases/{database}} 保持一致。
     */
    @Transactional
    public void delete(String name) {
        CatalogEntity entity = require(name);

        // 先删角色分配与授权，再删角色，最后删 catalog 内容
        List<CatalogRoleEntity> roles = catalogRoleRepository.findByCatalogIdOrderByNameAsc(entity.getId());
        for (CatalogRoleEntity role : roles) {
            catalogRoleAssignmentRepository.deleteByCatalogRoleId(role.getId());
            resourceGrantRepository.deleteByCatalogRoleId(role.getId());
            catalogRoleRepository.delete(role);
        }
        // 复用 catalog API 的库级级联删除，保证两条删除路径的语义一致
        int databases = databaseService.dropAll(entity.getPrefix());
        catalogRepository.delete(entity);
        log.debug("deleted catalog {}: {} databases and {} catalog roles removed",
                name, databases, roles.size());
    }

    /** 按名称取 catalog，不存在则 404。供其他管理服务复用。 */
    @Transactional(readOnly = true)
    public CatalogEntity require(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("catalog name is required");
        }
        return catalogRepository.findByPrefix(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, name));
    }

    /** 实体 → 管理规格的 {@code Catalog}。 */
    public Catalog toDto(CatalogEntity entity) {
        return new Catalog(
                entity.getCatalogType(),
                entity.getPrefix(),
                new LinkedHashMap<>(entity.getProperties()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getEntityVersion(),
                StorageConfigs.withoutSecrets(StorageConfigs.of(entity)));
    }

    /**
     * 归一化并校验一份存储配置，然后写进实体。
     *
     * <p>调用方没给 {@code storageConfigInfo} 时按 {@code FILE} 处理、位置退回默认仓库，
     * 与 feature 上线前的行为一致。落库细节见 {@link StorageConfigs#apply}。
     *
     * @param existing 库中已有的配置，新建时为 {@code null}。只有静态凭据的合并需要它：
     *                 密钥只写不读，请求里省略时必须从旧值继承（见
     *                 {@link StorageConfigs#mergeStaticCredentials}）
     */
    private void applyStorage(CatalogEntity entity, StorageConfigInfo requested, StorageConfigInfo existing) {
        StorageConfigInfo supplied = requested == null
                ? StorageConfigs.blank(StorageType.FILE, new ArrayList<>())
                : requested;
        StorageConfigInfo normalized = StorageConfigs.normalize(supplied, properties.getDefaultWarehouse());
        StorageConfigInfo merged = StorageConfigs.mergeStaticCredentials(normalized, existing, cipher);
        StorageConfigs.validate(merged);
        // 存储类型是否可用是本部署的能力问题（装了哪个 FileIO），
        // 与配置本身是否合法是两件事，因此分两步校验、分开报错
        storagePolicy.requireSupported(merged.storageType(), entity.getPrefix());
        StorageConfigs.apply(entity, merged);
    }

    /**
     * 为新 catalog 预置 {@value #DEFAULT_CATALOG_ROLE} 角色并授予 catalog 级管理权限。
     *
     * <p>这是 Polaris 的既有做法（新建 catalog 会带一个默认管理角色），
     * 也是授权体系的自举入口：没有它，任何人都无法在新建的 catalog 上授予权限。
     * 仅在开启授权时有意义，因此关闭授权时不创建，避免给不使用的部署留下无用数据。
     */
    private void provisionDefaultCatalogRole(CatalogEntity catalog) {
        if (!properties.getAuthorization().isEnabled()) {
            return;
        }
        long now = RequestContext.now();
        CatalogRoleEntity role = new CatalogRoleEntity();
        role.setId(Paging.newId());
        role.setCatalogId(catalog.getId());
        role.setName(DEFAULT_CATALOG_ROLE);
        role.setProperties(new LinkedHashMap<>());
        role.markCreated(RequestContext.principal(), now);
        CatalogRoleEntity savedRole = catalogRoleRepository.save(role);

        for (Privilege privilege : List.of(Privilege.CATALOG_MANAGE_ACCESS, Privilege.CATALOG_MANAGE_CONTENT)) {
            ResourceGrantEntity grant = new ResourceGrantEntity();
            grant.setId(Paging.newId());
            grant.setCatalogRoleId(savedRole.getId());
            grant.setResourceType(GrantType.CATALOG.wireName());
            grant.setNamespace(new ArrayList<>());
            grant.setObjectName("");
            grant.normalizeNamespace();
            grant.setPrivilege(privilege.name());
            grant.markCreated(RequestContext.principal(), now);
            resourceGrantRepository.save(grant);
        }
        log.info("provisioned default catalog role {} with CATALOG_MANAGE_ACCESS for catalog {}",
                DEFAULT_CATALOG_ROLE, catalog.getPrefix());
    }
}
