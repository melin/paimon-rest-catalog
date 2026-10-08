package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogRoleAssignmentEntity;
import io.github.melin.paimonrest.domain.entity.CatalogRoleEntity;
import io.github.melin.paimonrest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.paimonrest.domain.entity.ResourceGrantEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.ResourceGrantRepository;
import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.support.ApiException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权判定：回答「调用者能否在某个资源上执行某操作」。
 *
 * <p>判定链路严格对应 Polaris 的 RBAC 模型：
 * 主体 → principal role → catalog role → 资源授权（见 {@code docs/management-api-contract.md}）。
 * 有效权限是「主体持有的全部 catalog role 上、作用域覆盖目标资源」的授权的并集，
 * 再按 {@link PrivilegeModel} 展开蕴含关系。
 *
 * <p><b>作用域覆盖规则。</b>规格允许在 catalog、namespace、以及具体对象三个层级授予权限，
 * 更宽的授予覆盖更窄的资源：
 * <ul>
 *   <li>catalog 级授予覆盖该 catalog 内的一切；
 *   <li>namespace 级授予覆盖该命名空间及其所有下级命名空间与对象
 *       （与 Polaris 文档「Privileges granted on a namespace or catalog apply to
 *       descendant models」的方向一致）；
 *   <li>对象级授予只覆盖该对象本身。
 * </ul>
 *
 * <p><b>开关。</b>{@code paimon.rest.authorization.enabled} 为假时所有判定直接通过，
 * 使未启用授权的部署保持既有行为。
 *
 * <p><b>服务管理员。</b>管理规格的权限 enum 覆盖 catalog、namespace、table、view、policy、
 * semantic model 六个层级，没有「管理主体与服务级角色」这一层，说明 Polaris 把这类操作
 * 交给服务管理员而非权限判定。因此本实现用
 * {@code paimon.rest.authorization.service-admins} 名单表达，不虚构权限取值。
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final RestServerProperties properties;
    private final PrincipalRepository principalRepository;
    private final PrincipalRoleAssignmentRepository principalRoleAssignmentRepository;
    private final CatalogRepository catalogRepository;
    private final CatalogRoleRepository catalogRoleRepository;
    private final CatalogRoleAssignmentRepository catalogRoleAssignmentRepository;
    private final ResourceGrantRepository resourceGrantRepository;

    /** 授权总开关。 */
    public boolean enabled() {
        return properties.getAuthorization().isEnabled();
    }

    /** 当前调用者是否为服务管理员。 */
    @Transactional(readOnly = true)
    public boolean isServiceAdmin(String principalName) {
        if (principalName == null) {
            return false;
        }
        return properties.getAuthorization().getServiceAdmins().stream()
                .anyMatch(admin -> admin.equalsIgnoreCase(principalName));
    }

    /** 要求服务管理员身份，用于主体与 principal role 的管理端点。 */
    public void requireServiceAdmin(String action) {
        if (!enabled()) {
            return;
        }
        String principal = RequestContext.principal();
        if (!isServiceAdmin(principal)) {
            throw ApiException.forbidden("The caller does not have permission to " + action
                    + ": principal " + principal + " is not a service admin");
        }
    }

    /**
     * 要求调用者在指定 catalog 上具备某项权限（资源为 catalog 本身）。
     *
     * @throws ApiException 403 权限不足
     */
    @Transactional(readOnly = true)
    public void requireCatalog(String catalogName, Privilege required, String action) {
        require(catalogName, GrantType.CATALOG, List.of(), null, required, action);
    }

    /**
     * 要求调用者在指定 catalog 的某个资源上具备某项权限。
     *
     * <p><b>catalog 不存在时不判定。</b>此时直接返回，让下层服务抛出 404。
     * 否则「无权限」与「无此 catalog」都会变成 403，调用方无法区分是名字写错
     * 还是确实没有授权。
     *
     * @param catalogName catalog 名（即前缀）
     * @param resourceType 资源类型判别值
     * @param namespace    多级命名空间；catalog 级传空列表
     * @param objectName   对象名；catalog / namespace 级传 {@code null}
     * @param required     要求具备的权限
     * @param action       操作描述，用于 403 错误信息
     * @throws ApiException 403 权限不足
     */
    @Transactional(readOnly = true)
    public void require(String catalogName, GrantType resourceType, List<String> namespace,
                        String objectName, Privilege required, String action) {
        if (!enabled()) {
            return;
        }
        String principal = RequestContext.principal();
        if (isServiceAdmin(principal)) {
            return;
        }
        List<String> catalogIds = catalogIdOf(catalogName);
        if (catalogIds.isEmpty()) {
            return;
        }
        Set<Privilege> effective =
                effectivePrivileges(principal, catalogIds, resourceType, namespace, objectName);
        if (!PrivilegeModel.satisfies(effective, required)) {
            throw ApiException.forbidden("The caller does not have permission to " + action
                    + ": principal " + principal + " lacks " + required
                    + " on " + describe(resourceType, namespace, objectName)
                    + " in catalog " + catalogName);
        }
    }

    /**
     * 计算调用者（或指定主体）在目标资源上实际持有的权限集合。
     *
     * <p>只统计作用域覆盖目标资源的授权，并按蕴含关系展开，因此返回值可以直接
     * 用于多次判定，不必重复查询。
     */
    @Transactional(readOnly = true)
    public Set<Privilege> effectivePrivileges(String principalName, String catalogName,
                                              GrantType resourceType, List<String> namespace,
                                              String objectName) {
        return effectivePrivileges(principalName, catalogIdOf(catalogName), resourceType,
                namespace, objectName);
    }

    /** 已解析出 catalog 主键的版本，供一次请求内多处判定复用。 */
    private Set<Privilege> effectivePrivileges(String principalName, List<String> catalogIds,
                                               GrantType resourceType, List<String> namespace,
                                               String objectName) {
        Set<Privilege> result = EnumSet.noneOf(Privilege.class);
        if (catalogIds.isEmpty()) {
            return result;
        }
        List<String> catalogRoleIds = catalogRoleIdsOf(principalName, catalogIds);
        if (catalogRoleIds.isEmpty()) {
            return result;
        }
        for (ResourceGrantEntity grant : resourceGrantRepository.findByCatalogRoleIdIn(catalogRoleIds)) {
            if (!covers(grant, resourceType, namespace, objectName)) {
                continue;
            }
            result.addAll(PrivilegeModel.expand(Privilege.valueOf(grant.getPrivilege())));
        }
        return result;
    }

    /**
     * 主体在某个 catalog 上持有的 catalog role 主键。
     *
     * <p>链路：主体 →（principal role 分配）→ principal role →（catalog role 分配）→
     * catalog role → 过滤出属于目标 catalog 的角色。
     */
    @Transactional(readOnly = true)
    public List<String> catalogRoleIdsOf(String principalName, String catalogName) {
        return catalogRoleIdsOf(principalName, catalogIdOf(catalogName));
    }

    private List<String> catalogRoleIdsOf(String principalName, List<String> catalogIds) {
        if (catalogIds.isEmpty()) {
            return List.of();
        }
        String principalId = principalRepository.findByName(principalName)
                .map(principal -> principal.getId())
                .orElse(null);
        if (principalId == null) {
            // 主体未在管理面登记，视为没有任何授权
            return List.of();
        }
        List<String> principalRoleIds = new ArrayList<>();
        for (PrincipalRoleAssignmentEntity assignment
                : principalRoleAssignmentRepository.findByPrincipalId(principalId)) {
            principalRoleIds.add(assignment.getPrincipalRoleId());
        }
        if (principalRoleIds.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String principalRoleId : principalRoleIds) {
            for (CatalogRoleAssignmentEntity assignment
                    : catalogRoleAssignmentRepository.findByPrincipalRoleId(principalRoleId)) {
                CatalogRoleEntity role = catalogRoleRepository.findById(assignment.getCatalogRoleId())
                        .orElse(null);
                if (role != null && catalogIds.contains(role.getCatalogId())) {
                    result.add(role.getId());
                }
            }
        }
        return result;
    }

    /**
     * 判断一条授权的作用域是否覆盖目标资源。
     *
     * <p>catalog 级授权覆盖一切；namespace 级授权覆盖自身与下级；
     * 对象级授权要求命名空间完全相同且对象名一致。
     */
    private boolean covers(ResourceGrantEntity grant, GrantType resourceType,
                           List<String> namespace, String objectName) {
        GrantType scope = GrantType.parse(grant.getResourceType()).orElse(null);
        if (scope == null) {
            return false;
        }
        if (scope == GrantType.CATALOG) {
            return true;
        }
        if (scope == GrantType.NAMESPACE) {
            // 目标资源的命名空间以授权命名空间为前缀即为覆盖
            return isPrefix(grant.getNamespace(), namespace);
        }
        // 对象级授权：类型、命名空间、对象名三者都要对上
        return scope == resourceType
                && grant.getNamespace().equals(namespace)
                && objectName != null
                && objectName.equalsIgnoreCase(grant.getObjectName());
    }

    private boolean isPrefix(List<String> prefix, List<String> candidate) {
        if (prefix.size() > candidate.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(candidate.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 生成 403 错误信息中的资源描述。 */
    private String describe(GrantType resourceType, List<String> namespace, String objectName) {
        if (resourceType == GrantType.CATALOG) {
            return "the catalog";
        }
        StringBuilder builder = new StringBuilder(resourceType.wireName());
        if (namespace != null && !namespace.isEmpty()) {
            builder.append(' ').append(String.join(".", namespace));
        }
        if (objectName != null && !objectName.isEmpty()) {
            builder.append('.').append(objectName);
        }
        return builder.toString();
    }

    private List<String> catalogIdOf(String catalogName) {
        // 直接查仓储而不是走 ManagementCatalogService：后者可能反向依赖本服务，
        // 而 catalog 名与主键的对应关系只是一次按 prefix 的查询
        String id = catalogRepository.findByPrefix(catalogName)
                .map(catalog -> catalog.getId())
                .orElse(null);
        return id == null ? List.of() : List.of(id);
    }
}
