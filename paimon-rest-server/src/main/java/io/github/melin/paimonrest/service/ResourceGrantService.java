package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.domain.entity.CatalogRoleEntity;
import io.github.melin.paimonrest.domain.entity.ResourceGrantEntity;
import io.github.melin.paimonrest.domain.repo.ResourceGrantRepository;
import io.github.melin.paimonrest.dto.GrantDtos.GrantSpec;
import io.github.melin.paimonrest.dto.ManagementDtos.AddGrantRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.GrantResources;
import io.github.melin.paimonrest.dto.ManagementDtos.RevokeGrantRequest;
import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Paging;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源授权的读写：{@code /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants}。
 *
 * <p>这是 RBAC 链条的终点——权限最终以「catalog role + 资源 + 权限」三元组落在这里。
 * 请求体中的 {@code grant} 是规格的多态联合，由 {@link GrantSpec} 负责解析与还原。
 */
@Service
@RequiredArgsConstructor
public class ResourceGrantService {

    private final ResourceGrantRepository resourceGrantRepository;
    private final CatalogRoleService catalogRoleService;

    /** {@code GET .../grants}：列出该 catalog role 持有的全部授权。 */
    @Transactional(readOnly = true)
    public GrantResources list(String catalogName, String catalogRoleName) {
        CatalogRoleEntity role = catalogRoleService.require(catalogName, catalogRoleName);
        List<java.util.Map<String, Object>> grants = new ArrayList<>();
        for (ResourceGrantEntity entity : resourceGrantRepository.findByCatalogRoleId(role.getId())) {
            grants.add(toSpec(entity).toJson());
        }
        return new GrantResources(grants);
    }

    /**
     * {@code PUT .../grants}：新增授权。
     *
     * <p>幂等：同一「资源 + 权限」重复授予不报错，与其余授权端点一致，
     * 便于重复执行同一段脚本。
     *
     * @return 实际新建的授权条数（0 表示全部已存在）
     */
    @Transactional
    public int add(String catalogName, String catalogRoleName, AddGrantRequest request) {
        CatalogRoleEntity role = catalogRoleService.require(catalogName, catalogRoleName);
        GrantSpec spec = GrantSpec.from(request == null ? null : request.grant());
        if (exists(role.getId(), spec)) {
            return 0;
        }
        ResourceGrantEntity entity = new ResourceGrantEntity();
        entity.setId(Paging.newId());
        entity.setCatalogRoleId(role.getId());
        entity.setResourceType(spec.type().wireName());
        entity.setNamespace(new ArrayList<>(spec.namespace()));
        entity.setObjectName(spec.objectName() == null ? "" : spec.objectName());
        entity.setPrivilege(spec.privilege().name());
        entity.normalizeNamespace();
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        resourceGrantRepository.save(entity);
        return 1;
    }

    /**
     * {@code POST .../grants}：撤销授权。
     *
     * <p>规格用 POST 表达撤销（请求体是 {@code RevokeGrantRequest}），
     * 与 PUT 表达新增成对。撤销不存在的授权返回 404——
     * 静默成功会让调用方误以为权限已按预期回收。
     */
    @Transactional
    public void revoke(String catalogName, String catalogRoleName, RevokeGrantRequest request) {
        CatalogRoleEntity role = catalogRoleService.require(catalogName, catalogRoleName);
        GrantSpec spec = GrantSpec.from(request == null ? null : request.grant());
        List<ResourceGrantEntity> matches = resourceGrantRepository
                .findByCatalogRoleIdAndResourceTypeAndNamespaceKeyAndObjectNameAndPrivilege(
                        role.getId(),
                        spec.type().wireName(),
                        namespaceKey(spec),
                        spec.objectName() == null ? "" : spec.objectName(),
                        spec.privilege().name());
        if (matches.isEmpty()) {
            throw ApiException.managementNotExist(
                    io.github.melin.paimonrest.support.ResourceType.GRANT, describe(spec));
        }
        resourceGrantRepository.deleteAll(matches);
    }

    /** 实体的授权集合，供授权判定使用。 */
    @Transactional(readOnly = true)
    public List<ResourceGrantEntity> grantsOfRole(String catalogRoleId) {
        return resourceGrantRepository.findByCatalogRoleId(catalogRoleId);
    }

    /** 批量取授权，供授权判定一次装载。 */
    @Transactional(readOnly = true)
    public List<ResourceGrantEntity> grantsOfRoles(List<String> catalogRoleIds) {
        if (catalogRoleIds.isEmpty()) {
            return List.of();
        }
        return resourceGrantRepository.findByCatalogRoleIdIn(catalogRoleIds);
    }

    /** 实体 → 规格形状的授权。 */
    public GrantSpec toSpec(ResourceGrantEntity entity) {
        GrantType type = GrantType.parse(entity.getResourceType())
                .orElseThrow(() -> new IllegalStateException(
                        "Stored grant has unknown resource type: " + entity.getResourceType()));
        String objectName = entity.getObjectName() == null || entity.getObjectName().isEmpty()
                ? null
                : entity.getObjectName();
        return new GrantSpec(type, new ArrayList<>(entity.getNamespace()), objectName,
                io.github.melin.paimonrest.dto.Privilege.valueOf(entity.getPrivilege()));
    }

    private boolean exists(String catalogRoleId, GrantSpec spec) {
        return resourceGrantRepository
                .existsByCatalogRoleIdAndResourceTypeAndNamespaceKeyAndObjectNameAndPrivilege(
                        catalogRoleId,
                        spec.type().wireName(),
                        namespaceKey(spec),
                        spec.objectName() == null ? "" : spec.objectName(),
                        spec.privilege().name());
    }

    private String namespaceKey(GrantSpec spec) {
        return spec.namespace().isEmpty()
                ? ""
                : String.join(ResourceGrantEntity.NAMESPACE_SEPARATOR, spec.namespace());
    }

    private String describe(GrantSpec spec) {
        StringBuilder builder = new StringBuilder(spec.privilege().name())
                .append(" on ").append(spec.type().wireName());
        if (!spec.namespace().isEmpty()) {
            builder.append(' ').append(String.join(".", spec.namespace()));
        }
        if (spec.objectName() != null) {
            builder.append('.').append(spec.objectName());
        }
        return builder.toString();
    }
}
