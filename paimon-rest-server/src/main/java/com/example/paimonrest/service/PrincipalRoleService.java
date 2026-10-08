package com.example.paimonrest.service;

import com.example.paimonrest.config.RequestContext;
import com.example.paimonrest.domain.entity.CatalogRoleAssignmentEntity;
import com.example.paimonrest.domain.entity.CatalogRoleEntity;
import com.example.paimonrest.domain.entity.PrincipalEntity;
import com.example.paimonrest.domain.entity.PrincipalRoleAssignmentEntity;
import com.example.paimonrest.domain.entity.PrincipalRoleEntity;
import com.example.paimonrest.domain.repo.CatalogRoleAssignmentRepository;
import com.example.paimonrest.domain.repo.PrincipalRepository;
import com.example.paimonrest.domain.repo.PrincipalRoleAssignmentRepository;
import com.example.paimonrest.domain.repo.PrincipalRoleRepository;
import com.example.paimonrest.dto.ManagementDtos.CatalogRoles;
import com.example.paimonrest.dto.ManagementDtos.CreatePrincipalRoleRequest;
import com.example.paimonrest.dto.ManagementDtos.GrantCatalogRoleRequest;
import com.example.paimonrest.dto.ManagementDtos.Principal;
import com.example.paimonrest.dto.ManagementDtos.PrincipalRole;
import com.example.paimonrest.dto.ManagementDtos.PrincipalRoles;
import com.example.paimonrest.dto.ManagementDtos.Principals;
import com.example.paimonrest.dto.ManagementDtos.UpdatePrincipalRoleRequest;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Paging;
import com.example.paimonrest.support.ResourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * principal role 的增删改查，以及它与主体、catalog role 两侧的关联。
 *
 * <p>对应管理规格的 {@code /principal-roles} 一族端点。principal role 处在
 * 「主体 ↔ principal role ↔ catalog role ↔ 权限」链条的中间：
 * 它本身不携带权限，只把 catalog role 转发给主体。
 */
@Service
@RequiredArgsConstructor
public class PrincipalRoleService {

    private final PrincipalRoleRepository principalRoleRepository;
    private final PrincipalRepository principalRepository;
    private final PrincipalRoleAssignmentRepository principalRoleAssignmentRepository;
    private final CatalogRoleAssignmentRepository catalogRoleAssignmentRepository;
    private final CatalogRoleService catalogRoleService;

    /** {@code GET /principal-roles}。 */
    @Transactional(readOnly = true)
    public PrincipalRoles list() {
        List<PrincipalRole> roles = new ArrayList<>();
        for (PrincipalRoleEntity entity : principalRoleRepository.findAllByOrderByNameAsc()) {
            roles.add(ManagementMappers.toDto(entity));
        }
        return new PrincipalRoles(roles);
    }

    /** {@code POST /principal-roles}。 */
    @Transactional
    public PrincipalRole create(CreatePrincipalRoleRequest request) {
        String name = request == null || request.principalRole() == null
                ? null : request.principalRole().name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principalRole.name is required");
        }
        if (principalRoleRepository.existsByName(name)) {
            throw ApiException.managementAlreadyExist(ResourceType.PRINCIPAL_ROLE, name);
        }
        PrincipalRoleEntity entity = new PrincipalRoleEntity();
        entity.setId(Paging.newId());
        entity.setName(name);
        entity.setFederated(Boolean.TRUE.equals(request.principalRole().federated()));
        Map<String, String> properties = request.principalRole().properties();
        entity.setProperties(properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties));
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        return ManagementMappers.toDto(principalRoleRepository.save(entity));
    }

    /** {@code GET /principal-roles/{principalRoleName}}。 */
    @Transactional(readOnly = true)
    public PrincipalRole get(String name) {
        return ManagementMappers.toDto(require(name));
    }

    /** {@code PUT /principal-roles/{principalRoleName}}。 */
    @Transactional
    public PrincipalRole update(String name, UpdatePrincipalRoleRequest request) {
        PrincipalRoleEntity entity = require(name);
        if (request == null || request.currentEntityVersion() == null) {
            throw ApiException.badRequest("currentEntityVersion is required");
        }
        if (request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.PRINCIPAL_ROLE, name,
                    request.currentEntityVersion(), entity.getEntityVersion());
        }
        if (request.properties() == null) {
            throw ApiException.badRequest("properties is required");
        }
        entity.setProperties(new LinkedHashMap<>(request.properties()));
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        return ManagementMappers.toDto(principalRoleRepository.save(entity));
    }

    /**
     * {@code DELETE /principal-roles/{principalRoleName}}。
     *
     * <p>级联清理两侧关联：主体持有的该角色分配、该角色在各 catalog 上的 catalog role 分配。
     * 不清理 catalog role 与授权本身——它们归属 catalog，不随 principal role 消失。
     */
    @Transactional
    public void delete(String name) {
        PrincipalRoleEntity entity = require(name);
        principalRoleAssignmentRepository.deleteByPrincipalRoleId(entity.getId());
        catalogRoleAssignmentRepository.deleteByPrincipalRoleId(entity.getId());
        principalRoleRepository.delete(entity);
    }

    /** {@code GET /principal-roles/{principalRoleName}/principals}。 */
    @Transactional(readOnly = true)
    public Principals listPrincipals(String principalRoleName) {
        PrincipalRoleEntity role = require(principalRoleName);
        List<Principal> principals = new ArrayList<>();
        for (PrincipalRoleAssignmentEntity assignment
                : principalRoleAssignmentRepository.findByPrincipalRoleId(role.getId())) {
            principalRepository.findById(assignment.getPrincipalId())
                    .map(ManagementMappers::toDto)
                    .ifPresent(principals::add);
        }
        return new Principals(principals);
    }

    /**
     * {@code GET /principal-roles/{principalRoleName}/catalog-roles/{catalogName}}。
     *
     * <p>只返回「该 principal role 在该 catalog 上被授予的」catalog role，
     * 而不是该 catalog 的全部 catalog role——路径里的 catalogName 是过滤条件，
     * 不是命名空间。
     */
    @Transactional(readOnly = true)
    public CatalogRoles listCatalogRoles(String principalRoleName, String catalogName) {
        PrincipalRoleEntity role = require(principalRoleName);
        String catalogId = catalogRoleService.catalogIdOf(catalogName);
        List<CatalogRoleEntity> roles = new ArrayList<>();
        for (CatalogRoleAssignmentEntity assignment
                : catalogRoleAssignmentRepository.findByPrincipalRoleId(role.getId())) {
            catalogRoleService.findEntity(assignment.getCatalogRoleId())
                    .filter(candidate -> candidate.getCatalogId().equals(catalogId))
                    .ifPresent(roles::add);
        }
        roles.sort(java.util.Comparator.comparing(CatalogRoleEntity::getName));
        return catalogRoleService.toCatalogRoles(roles);
    }

    /**
     * {@code PUT /principal-roles/{principalRoleName}/catalog-roles/{catalogName}}：
     * 把 catalog role 授予 principal role。
     *
     * <p>路径里只有 principal role 名与 catalog 名，被授予的 catalog role 名来自请求体
     * {@code GrantCatalogRoleRequest.catalogRole.name}。
     *
     * <p>幂等：重复授予不报错。
     */
    @Transactional
    public void grantCatalogRole(String principalRoleName, String catalogName,
                                 GrantCatalogRoleRequest request) {
        String catalogRoleName = request == null || request.catalogRole() == null
                ? null : request.catalogRole().name();
        if (catalogRoleName == null || catalogRoleName.isBlank()) {
            throw ApiException.badRequest("catalogRole.name is required");
        }
        PrincipalRoleEntity role = require(principalRoleName);
        CatalogRoleEntity catalogRole = catalogRoleService.require(catalogName, catalogRoleName);
        if (catalogRoleAssignmentRepository.existsByPrincipalRoleIdAndCatalogRoleId(
                role.getId(), catalogRole.getId())) {
            return;
        }
        CatalogRoleAssignmentEntity assignment = new CatalogRoleAssignmentEntity();
        assignment.setId(Paging.newId());
        assignment.setPrincipalRoleId(role.getId());
        assignment.setCatalogRoleId(catalogRole.getId());
        assignment.markCreated(RequestContext.principal(), RequestContext.now());
        catalogRoleAssignmentRepository.save(assignment);
    }

    /** {@code DELETE /principal-roles/{principalRoleName}/catalog-roles/{catalogName}/{catalogRoleName}}。 */
    @Transactional
    public void revokeCatalogRole(String principalRoleName, String catalogName, String catalogRoleName) {
        PrincipalRoleEntity role = require(principalRoleName);
        CatalogRoleEntity catalogRole = catalogRoleService.require(catalogName, catalogRoleName);
        boolean assigned = catalogRoleAssignmentRepository.findByPrincipalRoleId(role.getId())
                .stream().anyMatch(a -> a.getCatalogRoleId().equals(catalogRole.getId()));
        if (!assigned) {
            throw ApiException.assignmentNotExist(ResourceType.CATALOG_ROLE, catalogRoleName,
                    "Catalog role " + catalogRoleName + " of catalog " + catalogName
                            + " is not assigned to principal role " + principalRoleName);
        }
        catalogRoleAssignmentRepository
                .deleteByPrincipalRoleIdAndCatalogRoleId(role.getId(), catalogRole.getId());
    }

    /** 按名称取 principal role，不存在则 404。 */
    @Transactional(readOnly = true)
    public PrincipalRoleEntity require(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principal role name is required");
        }
        return principalRoleRepository.findByName(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.PRINCIPAL_ROLE, name));
    }

    /** 批量按主键转 DTO，供其他服务复用。 */
    @Transactional(readOnly = true)
    public PrincipalRoles toRoles(List<String> principalRoleIds) {
        List<PrincipalRole> roles = new ArrayList<>();
        for (PrincipalRoleEntity entity : principalRoleRepository.findAllById(principalRoleIds)) {
            roles.add(ManagementMappers.toDto(entity));
        }
        roles.sort(java.util.Comparator.comparing(PrincipalRole::name));
        return new PrincipalRoles(roles);
    }

    /** 该 principal role 在各 catalog 上持有的 catalog role 主键集合，供授权判定使用。 */
    @Transactional(readOnly = true)
    public List<String> catalogRoleIdsOf(String principalRoleName) {
        return principalRoleRepository.findByName(principalRoleName)
                .map(role -> catalogRoleAssignmentRepository.findByPrincipalRoleId(role.getId())
                        .stream().map(CatalogRoleAssignmentEntity::getCatalogRoleId).toList())
                .orElse(List.of());
    }
}
