package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.CatalogRoleAssignmentEntity;
import io.github.melin.paimonrest.domain.entity.CatalogRoleEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRoleRepository;
import io.github.melin.paimonrest.dto.ManagementDtos.CatalogRole;
import io.github.melin.paimonrest.dto.ManagementDtos.CatalogRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.CreateCatalogRoleRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRole;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdateCatalogRoleRequest;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.ResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog role 的增删改查。
 *
 * <p>对应管理规格的 {@code /catalogs/{catalogName}/catalog-roles} 一族端点。
 * catalog role 归属某个 catalog，是权限的实际持有者：
 * 「资源 + 权限」的授权记录都挂在 catalog role 上（见 {@link ResourceGrantService}）。
 */
@Service
@RequiredArgsConstructor
public class CatalogRoleService {

    private final CatalogRoleRepository catalogRoleRepository;
    private final CatalogRoleAssignmentRepository catalogRoleAssignmentRepository;
    private final PrincipalRoleRepository principalRoleRepository;
    private final ManagementCatalogService managementCatalogService;

    /** {@code GET /catalogs/{catalogName}/catalog-roles}。 */
    @Transactional(readOnly = true)
    public CatalogRoles list(String catalogName) {
        String catalogId = catalogIdOf(catalogName);
        return toCatalogRoles(catalogRoleRepository.findByCatalogIdOrderByNameAsc(catalogId));
    }

    /** {@code POST /catalogs/{catalogName}/catalog-roles}。 */
    @Transactional
    public CatalogRole create(String catalogName, CreateCatalogRoleRequest request) {
        String name = request == null || request.catalogRole() == null
                ? null : request.catalogRole().name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("catalogRole.name is required");
        }
        CatalogEntity catalog = managementCatalogService.require(catalogName);
        if (catalogRoleRepository.existsByCatalogIdAndName(catalog.getId(), name)) {
            throw ApiException.managementAlreadyExist(ResourceType.CATALOG_ROLE, name);
        }
        CatalogRoleEntity entity = new CatalogRoleEntity();
        entity.setId(Paging.newId());
        entity.setCatalogId(catalog.getId());
        entity.setName(name);
        Map<String, String> properties = request.catalogRole().properties();
        entity.setProperties(properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties));
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        return ManagementMappers.toDto(catalogRoleRepository.save(entity));
    }

    /** {@code GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}。 */
    @Transactional(readOnly = true)
    public CatalogRole get(String catalogName, String catalogRoleName) {
        return ManagementMappers.toDto(require(catalogName, catalogRoleName));
    }

    /** {@code PUT /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}。 */
    @Transactional
    public CatalogRole update(String catalogName, String catalogRoleName,
                              UpdateCatalogRoleRequest request) {
        CatalogRoleEntity entity = require(catalogName, catalogRoleName);
        if (request == null || request.currentEntityVersion() == null) {
            throw ApiException.badRequest("currentEntityVersion is required");
        }
        if (request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.CATALOG_ROLE, catalogRoleName,
                    request.currentEntityVersion(), entity.getEntityVersion());
        }
        if (request.properties() == null) {
            throw ApiException.badRequest("properties is required");
        }
        entity.setProperties(new LinkedHashMap<>(request.properties()));
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        return ManagementMappers.toDto(catalogRoleRepository.save(entity));
    }

    /**
     * {@code DELETE /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}。
     *
     * <p>级联清理该角色的授权记录与 principal role 分配：角色消失后这些记录不再有任何意义。
     */
    @Transactional
    public void delete(String catalogName, String catalogRoleName) {
        CatalogRoleEntity entity = require(catalogName, catalogRoleName);
        catalogRoleAssignmentRepository.deleteByCatalogRoleId(entity.getId());
        catalogRoleRepository.delete(entity);
    }

    /**
     * {@code GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/principal-roles}
     * ：哪些 principal role 被授予了该 catalog role。
     */
    @Transactional(readOnly = true)
    public PrincipalRoles listPrincipalRoles(String catalogName, String catalogRoleName) {
        CatalogRoleEntity catalogRole = require(catalogName, catalogRoleName);
        List<PrincipalRole> roles = new ArrayList<>();
        for (CatalogRoleAssignmentEntity assignment
                : catalogRoleAssignmentRepository.findByCatalogRoleId(catalogRole.getId())) {
            principalRoleRepository.findById(assignment.getPrincipalRoleId())
                    .map(ManagementMappers::toDto)
                    .ifPresent(roles::add);
        }
        roles.sort(Comparator.comparing(PrincipalRole::name));
        return new PrincipalRoles(roles);
    }

    /** catalog 名 → catalog 主键，不存在则 404。 */
    @Transactional(readOnly = true)
    public String catalogIdOf(String catalogName) {
        return managementCatalogService.require(catalogName).getId();
    }

    /** 按名称取 catalog role，不存在则 404。 */
    @Transactional(readOnly = true)
    public CatalogRoleEntity require(String catalogName, String catalogRoleName) {
        if (catalogRoleName == null || catalogRoleName.isBlank()) {
            throw ApiException.badRequest("catalog role name is required");
        }
        String catalogId = catalogIdOf(catalogName);
        return catalogRoleRepository.findByCatalogIdAndName(catalogId, catalogRoleName)
                .orElseThrow(() -> ApiException.managementNotExist(
                        ResourceType.CATALOG_ROLE, catalogRoleName));
    }

    /** 按主键取 catalog role。 */
    @Transactional(readOnly = true)
    public Optional<CatalogRoleEntity> findEntity(String catalogRoleId) {
        return catalogRoleRepository.findById(catalogRoleId);
    }

    /** 批量转 DTO。 */
    public CatalogRoles toCatalogRoles(List<CatalogRoleEntity> entities) {
        List<CatalogRole> roles = new ArrayList<>();
        for (CatalogRoleEntity entity : entities) {
            roles.add(ManagementMappers.toDto(entity));
        }
        return new CatalogRoles(roles);
    }
}
