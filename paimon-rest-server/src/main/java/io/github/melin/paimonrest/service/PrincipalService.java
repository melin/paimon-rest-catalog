package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.domain.entity.PrincipalEntity;
import io.github.melin.paimonrest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.paimonrest.domain.entity.PrincipalRoleEntity;
import io.github.melin.paimonrest.domain.repo.PrincipalRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRoleRepository;
import io.github.melin.paimonrest.dto.ManagementDtos.CreatePrincipalRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.GrantPrincipalRoleRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.Principal;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRole;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalWithCredentials;
import io.github.melin.paimonrest.dto.ManagementDtos.Principals;
import io.github.melin.paimonrest.dto.ManagementDtos.ResetPrincipalRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdatePrincipalRequest;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.ResourceType;
import io.github.melin.paimonrest.support.Secrets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理主体（principal）的增删改查与凭据管理。
 *
 * <p>对应管理规格的 {@code /principals} 一族端点。凭据语义：
 * 创建、{@code rotate}、{@code reset} 三个操作会返回明文 {@code clientSecret}，
 * 其余任何响应都不含密钥，库中只存摘要。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrincipalService {

    private final PrincipalRepository principalRepository;
    private final PrincipalRoleRepository principalRoleRepository;
    private final PrincipalRoleAssignmentRepository principalRoleAssignmentRepository;

    /** {@code GET /principals}。 */
    @Transactional(readOnly = true)
    public Principals list() {
        List<Principal> result = new ArrayList<>();
        for (PrincipalEntity entity : principalRepository.findAllByOrderByNameAsc()) {
            result.add(toDto(entity));
        }
        return new Principals(result);
    }

    /** {@code POST /principals}。返回的凭据是本主体密钥的唯一一次明文出现。 */
    @Transactional
    public PrincipalWithCredentials create(CreatePrincipalRequest request) {
        String name = request == null || request.principal() == null
                ? null : request.principal().name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principal.name is required");
        }
        if (principalRepository.existsByName(name)) {
            throw ApiException.managementAlreadyExist(ResourceType.PRINCIPAL, name);
        }

        Map<String, String> properties = request.principal().properties();

        PrincipalEntity entity = new PrincipalEntity();
        entity.setId(Paging.newId());
        entity.setName(name);
        entity.setProperties(properties == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(properties));
        entity.setCredentialRotationRequired(Boolean.TRUE.equals(request.credentialRotationRequired()));
        entity.markCreated(RequestContext.principal(), RequestContext.now());

        String clientId = Secrets.newClientId();
        String secret = Secrets.newSecret();
        applyCredential(entity, clientId, secret);

        PrincipalEntity saved = principalRepository.save(entity);
        return withCredentials(saved, secret);
    }

    /** {@code GET /principals/{principalName}}。 */
    @Transactional(readOnly = true)
    public Principal get(String name) {
        return toDto(require(name));
    }

    /** {@code PUT /principals/{principalName}}。 */
    @Transactional
    public Principal update(String name, UpdatePrincipalRequest request) {
        PrincipalEntity entity = require(name);
        if (request == null || request.currentEntityVersion() == null) {
            throw ApiException.badRequest("currentEntityVersion is required");
        }
        if (request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.PRINCIPAL, name,
                    request.currentEntityVersion(), entity.getEntityVersion());
        }
        if (request.properties() == null) {
            throw ApiException.badRequest("properties is required");
        }
        entity.setProperties(new LinkedHashMap<>(request.properties()));
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        return toDto(principalRepository.save(entity));
    }

    /**
     * {@code DELETE /principals/{principalName}}。
     *
     * <p>级联清理该主体持有的全部 principal role 分配。规格没有 cascade 参数，
     * 这里按级联实现，理由是残留的角色分配会让主体名无法被重新创建。
     */
    @Transactional
    public void delete(String name) {
        PrincipalEntity entity = require(name);
        principalRoleAssignmentRepository.deleteByPrincipalId(entity.getId());
        principalRepository.delete(entity);
    }

    /** {@code POST /principals/{principalName}/rotate}：换密钥，保留 clientId。 */
    @Transactional
    public PrincipalWithCredentials rotate(String name) {
        PrincipalEntity entity = require(name);
        String secret = Secrets.newSecret();
        String clientId = entity.getClientId() == null ? Secrets.newClientId() : entity.getClientId();
        applyCredential(entity, clientId, secret);
        entity.setCredentialRotationRequired(false);
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        return withCredentials(principalRepository.save(entity), secret);
    }

    /**
     * {@code POST /principals/{principalName}/reset}。
     *
     * <p>规格的 {@code ResetPrincipalRequest} 两个字段都是可选的：都给了就按给定值重置，
     * 只给 {@code clientId} 就沿用该 id 重新生成密钥，都不给则全部重新生成。
     */
    @Transactional
    public PrincipalWithCredentials reset(String name, ResetPrincipalRequest request) {
        PrincipalEntity entity = require(name);
        String clientId = request == null || request.clientId() == null || request.clientId().isBlank()
                ? Secrets.newClientId()
                : request.clientId();
        String secret = request == null || request.clientSecret() == null || request.clientSecret().isBlank()
                ? Secrets.newSecret()
                : request.clientSecret();

        applyCredential(entity, clientId, secret);
        entity.setCredentialRotationRequired(false);
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        return withCredentials(principalRepository.save(entity), secret);
    }

    /** {@code GET /principals/{principalName}/principal-roles}。 */
    @Transactional(readOnly = true)
    public PrincipalRoles listRoles(String principalName) {
        PrincipalEntity entity = require(principalName);
        List<String> roleIds = principalRoleAssignmentRepository.findByPrincipalId(entity.getId())
                .stream().map(PrincipalRoleAssignmentEntity::getPrincipalRoleId).toList();
        List<PrincipalRole> roles = new ArrayList<>();
        for (PrincipalRoleEntity role : principalRoleRepository.findAllById(roleIds)) {
            roles.add(ManagementMappers.toDto(role));
        }
        return new PrincipalRoles(roles);
    }

    /**
     * {@code PUT /principals/{principalName}/principal-roles}：把 principal role 授予主体。
     *
     * <p>路径里只有主体名，被授予的角色名来自请求体
     * {@code GrantPrincipalRoleRequest.principalRole.name}。
     *
     * <p>幂等：重复授予同一角色不报错，便于重复执行同一段脚本。
     */
    @Transactional
    public void grantRole(String principalName, GrantPrincipalRoleRequest request) {
        String principalRoleName = request == null || request.principalRole() == null
                ? null : request.principalRole().name();
        if (principalRoleName == null || principalRoleName.isBlank()) {
            throw ApiException.badRequest("principalRole.name is required");
        }
        PrincipalEntity principal = require(principalName);
        PrincipalRoleEntity role = requireRole(principalRoleName);
        if (principalRoleAssignmentRepository.existsByPrincipalIdAndPrincipalRoleId(
                principal.getId(), role.getId())) {
            return;
        }
        PrincipalRoleAssignmentEntity assignment = new PrincipalRoleAssignmentEntity();
        assignment.setId(Paging.newId());
        assignment.setPrincipalId(principal.getId());
        assignment.setPrincipalRoleId(role.getId());
        assignment.markCreated(RequestContext.principal(), RequestContext.now());
        principalRoleAssignmentRepository.save(assignment);
    }

    /** {@code DELETE /principals/{principalName}/principal-roles/{principalRoleName}}。 */
    @Transactional
    public void revokeRole(String principalName, String principalRoleName) {
        PrincipalEntity principal = require(principalName);
        PrincipalRoleEntity role = requireRole(principalRoleName);
        boolean assigned = principalRoleAssignmentRepository.findByPrincipalId(principal.getId())
                .stream().anyMatch(a -> a.getPrincipalRoleId().equals(role.getId()));
        if (!assigned) {
            throw ApiException.assignmentNotExist(ResourceType.PRINCIPAL_ROLE, principalRoleName,
                    "Principal role " + principalRoleName + " is not assigned to principal " + principalName);
        }
        principalRoleAssignmentRepository
                .deleteByPrincipalIdAndPrincipalRoleId(principal.getId(), role.getId());
    }

    /** 按名称取主体，不存在则 404。 */
    @Transactional(readOnly = true)
    public PrincipalEntity require(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principal name is required");
        }
        return principalRepository.findByName(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.PRINCIPAL, name));
    }

    /** 主体名 → 其持有的全部 principal role 名，供授权判定使用。 */
    @Transactional(readOnly = true)
    public List<String> roleNamesOf(String principalName) {
        return principalRepository.findByName(principalName)
                .map(principal -> principalRoleAssignmentRepository.findByPrincipalId(principal.getId())
                        .stream()
                        .map(assignment -> principalRoleRepository.findById(assignment.getPrincipalRoleId())
                                .map(PrincipalRoleEntity::getName).orElse(null))
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of());
    }

    /** 实体 → 管理规格的 {@code Principal}。不含任何密钥信息。 */
    public Principal toDto(PrincipalEntity entity) {
        return ManagementMappers.toDto(entity);
    }

    private PrincipalRoleEntity requireRole(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principal role name is required");
        }
        return principalRoleRepository.findByName(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.PRINCIPAL_ROLE, name));
    }

    private void applyCredential(PrincipalEntity entity, String clientId, String secret) {
        String salt = Secrets.newSalt();
        entity.setClientId(clientId);
        entity.setSecretSalt(salt);
        entity.setSecretHash(Secrets.hash(salt, secret));
    }

    private PrincipalWithCredentials withCredentials(PrincipalEntity entity, String secret) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("clientId", entity.getClientId());
        credentials.put("clientSecret", secret);
        return new PrincipalWithCredentials(toDto(entity), credentials);
    }
}
