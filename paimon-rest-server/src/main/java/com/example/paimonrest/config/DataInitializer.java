package com.example.paimonrest.config;

import com.example.paimonrest.domain.entity.CatalogEntity;
import com.example.paimonrest.domain.entity.CatalogRoleAssignmentEntity;
import com.example.paimonrest.domain.entity.CatalogRoleEntity;
import com.example.paimonrest.domain.entity.DatabaseEntity;
import com.example.paimonrest.domain.entity.PrincipalEntity;
import com.example.paimonrest.domain.entity.PrincipalRoleAssignmentEntity;
import com.example.paimonrest.domain.entity.PrincipalRoleEntity;
import com.example.paimonrest.domain.entity.ResourceGrantEntity;
import com.example.paimonrest.domain.repo.CatalogRepository;
import com.example.paimonrest.domain.repo.CatalogRoleAssignmentRepository;
import com.example.paimonrest.domain.repo.CatalogRoleRepository;
import com.example.paimonrest.domain.repo.DatabaseRepository;
import com.example.paimonrest.domain.repo.PrincipalRepository;
import com.example.paimonrest.domain.repo.PrincipalRoleAssignmentRepository;
import com.example.paimonrest.domain.repo.PrincipalRoleRepository;
import com.example.paimonrest.domain.repo.ResourceGrantRepository;
import com.example.paimonrest.dto.ManagementEnums.GrantType;
import com.example.paimonrest.dto.Privilege;
import com.example.paimonrest.service.ManagementCatalogService;
import com.example.paimonrest.support.Paging;
import com.example.paimonrest.support.Paths;
import com.example.paimonrest.support.Secrets;
import com.example.paimonrest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动时预置 catalog 与 database，并在开启授权时预置自举链路。
 *
 * <p>对应配置 {@code paimon.rest.initial-catalog.*}，用于本地开发或演示环境；
 * 生产环境可以留空，由引擎首次调用 {@code GET /v1/config} 时按需登记。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private static final String SYSTEM_PRINCIPAL = "system";

    private final RestServerProperties properties;
    private final CatalogRepository catalogRepository;
    private final DatabaseRepository databaseRepository;
    private final PrincipalRepository principalRepository;
    private final PrincipalRoleRepository principalRoleRepository;
    private final PrincipalRoleAssignmentRepository principalRoleAssignmentRepository;
    private final CatalogRoleRepository catalogRoleRepository;
    private final CatalogRoleAssignmentRepository catalogRoleAssignmentRepository;
    private final ResourceGrantRepository resourceGrantRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String prefix = properties.getInitialCatalog().getPrefix();
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        String warehouse = properties.getInitialCatalog().getWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            warehouse = properties.getDefaultWarehouse();
        }

        String resolvedWarehouse = warehouse;
        CatalogEntity catalog = catalogRepository.findByPrefix(prefix).orElseGet(() -> {
            CatalogEntity created = new CatalogEntity();
            created.setId(Paging.newId());
            created.setPrefix(prefix);
            // 与其它创建路径一致：存储配置与仓库一起写，避免只留下投影列
            StorageConfigs.apply(created, StorageConfigs.fileStorage(resolvedWarehouse));
            created.setDefaults(new LinkedHashMap<>());
            created.setOverrides(new LinkedHashMap<>());
            created.setCatalogType("INTERNAL");
            created.markCreated(SYSTEM_PRINCIPAL, System.currentTimeMillis());
            return catalogRepository.save(created);
        });

        long now = System.currentTimeMillis();
        for (RestServerProperties.DatabaseSeed seed : properties.getInitialCatalog().getDatabases()) {
            if (seed.getName() == null || seed.getName().isBlank()) {
                continue;
            }
            if (databaseRepository.existsByCatalogIdAndName(catalog.getId(), seed.getName())) {
                continue;
            }
            DatabaseEntity database = new DatabaseEntity();
            database.setId(Paging.newId());
            database.setCatalogId(catalog.getId());
            database.setName(seed.getName());
            database.setLocation(Paths.databaseLocation(catalog.getWarehouse(), seed.getName()));
            database.setOptions(seed.getOptions() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(seed.getOptions()));
            database.markCreated(SYSTEM_PRINCIPAL, now);
            databaseRepository.save(database);
        }

        bootstrapAuthorization(catalog, now);
    }

    /**
     * 预置授权体系的自举链路。
     *
     * <p>关闭授权时什么都不做，避免给不使用的部署留下无用数据。
     *
     * <p>开启授权时需要一条最小可用链路，否则全新部署没有任何主体能创建第一个主体：
     * 引导主体 → {@code service_admin}（principal role）→ 各 catalog 的
     * {@code catalog_admin}（catalog role，由 {@code ManagementCatalogService} 在
     * 建 catalog 时预置）→ {@code CATALOG_MANAGE_ACCESS}。
     *
     * <p>这里还负责给出一个容易踩坑的配置组合告警：鉴权关闭 + 授权开启时，
     * 任何令牌都会被当成主体名，调用方可以任意冒用身份。
     */
    private void bootstrapAuthorization(CatalogEntity catalog, long now) {
        RestServerProperties.Authorization authorization = properties.getAuthorization();
        if (!authorization.isEnabled()) {
            return;
        }
        if (!properties.getAuth().isEnabled()) {
            log.warn("authorization is enabled while authentication is disabled: any bearer token"
                    + " will be accepted and used as the principal name, so callers can impersonate"
                    + " any principal. Enable paimon.rest.auth.enabled in production.");
        }

        String bootstrapPrincipal = authorization.getBootstrapPrincipal();
        if (bootstrapPrincipal == null || bootstrapPrincipal.isBlank()) {
            log.info("authorization enabled without a bootstrap principal; relying on an existing"
                    + " service admin to seed the authorization graph");
            return;
        }
        if (!authorization.getServiceAdmins().contains(bootstrapPrincipal)) {
            log.warn("bootstrap principal {} is not listed in paimon.rest.authorization.service-admins;"
                    + " it will be created but cannot manage principals", bootstrapPrincipal);
        }

        String secret = Secrets.newSecret();
        PrincipalEntity principal = principalRepository.findByName(bootstrapPrincipal).orElse(null);
        if (principal == null) {
            principal = new PrincipalEntity();
            principal.setId(Paging.newId());
            principal.setName(bootstrapPrincipal);
            principal.setProperties(new LinkedHashMap<>());
            principal.setCredentialRotationRequired(true);
            principal.markCreated(SYSTEM_PRINCIPAL, now);
            String salt = Secrets.newSalt();
            principal.setClientId(Secrets.newClientId());
            principal.setSecretSalt(salt);
            principal.setSecretHash(Secrets.hash(salt, secret));
            principal = principalRepository.save(principal);
            // 明文密钥只在日志中出现这一次，与创建主体的响应语义一致；
            // 拿到后应立即轮换（POST /principals/{name}/rotate）或改为从密钥管理系统注入。
            log.warn("created bootstrap principal {} with clientId={} clientSecret={};"
                            + " rotate it after the first login",
                    bootstrapPrincipal, principal.getClientId(), secret);
        }

        String roleName = authorization.getBootstrapPrincipalRole();
        if (roleName == null || roleName.isBlank()) {
            return;
        }
        PrincipalRoleEntity role = principalRoleRepository.findByName(roleName).orElse(null);
        if (role == null) {
            role = new PrincipalRoleEntity();
            role.setId(Paging.newId());
            role.setName(roleName);
            role.setProperties(new LinkedHashMap<>());
            role.markCreated(SYSTEM_PRINCIPAL, now);
            role = principalRoleRepository.save(role);
            log.info("created bootstrap principal role {}", roleName);
        }

        if (!principalRoleAssignmentRepository.existsByPrincipalIdAndPrincipalRoleId(
                principal.getId(), role.getId())) {
            PrincipalRoleAssignmentEntity assignment = new PrincipalRoleAssignmentEntity();
            assignment.setId(Paging.newId());
            assignment.setPrincipalId(principal.getId());
            assignment.setPrincipalRoleId(role.getId());
            assignment.markCreated(SYSTEM_PRINCIPAL, now);
            principalRoleAssignmentRepository.save(assignment);
        }

        // 把 service_admin 接到每个 catalog 的默认管理角色上
        String catalogAdmin = ManagementCatalogService.DEFAULT_CATALOG_ROLE;
        for (CatalogRoleEntity catalogRole
                : catalogRoleRepository.findByCatalogIdOrderByNameAsc(catalog.getId())) {
            if (!catalogAdmin.equals(catalogRole.getName())) {
                continue;
            }
            if (catalogRoleAssignmentRepository.existsByPrincipalRoleIdAndCatalogRoleId(
                    role.getId(), catalogRole.getId())) {
                continue;
            }
            CatalogRoleAssignmentEntity assignment = new CatalogRoleAssignmentEntity();
            assignment.setId(Paging.newId());
            assignment.setPrincipalRoleId(role.getId());
            assignment.setCatalogRoleId(catalogRole.getId());
            assignment.markCreated(SYSTEM_PRINCIPAL, now);
            catalogRoleAssignmentRepository.save(assignment);
            log.info("granted catalog role {} of catalog {} to principal role {}",
                    catalogAdmin, catalog.getPrefix(), roleName);
        }

        // 若初始 catalog 未带默认管理角色（例如先于本功能创建），此处补齐，保证自举链路完整
        if (catalogRoleRepository.findByCatalogIdAndName(catalog.getId(), catalogAdmin).isEmpty()) {
            CatalogRoleEntity createdRole = new CatalogRoleEntity();
            createdRole.setId(Paging.newId());
            createdRole.setCatalogId(catalog.getId());
            createdRole.setName(catalogAdmin);
            createdRole.setProperties(new LinkedHashMap<>());
            createdRole.markCreated(SYSTEM_PRINCIPAL, now);
            createdRole = catalogRoleRepository.save(createdRole);

            for (Privilege privilege : List.of(
                    Privilege.CATALOG_MANAGE_ACCESS, Privilege.CATALOG_MANAGE_CONTENT)) {
                ResourceGrantEntity grant = new ResourceGrantEntity();
                grant.setId(Paging.newId());
                grant.setCatalogRoleId(createdRole.getId());
                grant.setResourceType(GrantType.CATALOG.wireName());
                grant.setNamespace(new ArrayList<>());
                grant.setObjectName("");
                grant.normalizeNamespace();
                grant.setPrivilege(privilege.name());
                grant.markCreated(SYSTEM_PRINCIPAL, now);
                resourceGrantRepository.save(grant);
            }

            CatalogRoleAssignmentEntity assignment = new CatalogRoleAssignmentEntity();
            assignment.setId(Paging.newId());
            assignment.setPrincipalRoleId(role.getId());
            assignment.setCatalogRoleId(createdRole.getId());
            assignment.markCreated(SYSTEM_PRINCIPAL, now);
            catalogRoleAssignmentRepository.save(assignment);
            log.info("provisioned default catalog role {} for pre-existing catalog {}",
                    catalogAdmin, catalog.getPrefix());
        }
    }
}
