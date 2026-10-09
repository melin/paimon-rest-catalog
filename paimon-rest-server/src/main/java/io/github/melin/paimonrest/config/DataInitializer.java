package io.github.melin.paimonrest.config;

import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.CatalogRoleAssignmentEntity;
import io.github.melin.paimonrest.domain.entity.CatalogRoleEntity;
import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.entity.PrincipalEntity;
import io.github.melin.paimonrest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.paimonrest.domain.entity.PrincipalRoleEntity;
import io.github.melin.paimonrest.domain.entity.ResourceGrantEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.CatalogRoleRepository;
import io.github.melin.paimonrest.domain.repo.DatabaseRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.paimonrest.domain.repo.PrincipalRoleRepository;
import io.github.melin.paimonrest.domain.repo.ResourceGrantRepository;
import io.github.melin.paimonrest.dto.ManagementEnums.GrantType;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.service.ManagementCatalogService;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.Paths;
import io.github.melin.paimonrest.support.Secrets;
import io.github.melin.paimonrest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 内置的默认账号密码。用户表里出现这个值时启动告警，见 {@link #warnAboutDefaultPassword}。 */
    private static final String DEFAULT_PASSWORD = "admin";

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
        warnAboutAuthConfiguration();

        String prefix = properties.getInitialCatalog().getPrefix();
        if (prefix == null || prefix.isBlank()) {
            // 没有初始 catalog 可播种。账号与授权链路的对账仍要做——它与初始 catalog 无关
            warnAboutPasswordAccountsWithoutPrincipal();
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
        warnAboutPasswordAccountsWithoutPrincipal();
    }

    /**
     * 报告认证配置的组合问题。
     *
     * <p>这些组合的共同点是「不会报错，只是不生效」——服务端照常启动、请求照常返回，
     * 问题只在人对着登录页发愣或者权限不对时才浮现。所以必须在启动日志里说出来。
     */
    private void warnAboutAuthConfiguration() {
        RestServerProperties.Auth auth = properties.getAuth();
        RestServerProperties.Auth.Console console = auth.getConsole();
        RestServerProperties.Auth.Oidc oidc = console.getOidc();

        if (!console.loginRequired(auth.isEnabled())) {
            if (console.anyMethodEnabled()) {
                log.warn("console login methods are configured but neither"
                        + " paimon.rest.auth.enabled nor paimon.rest.auth.console.required is on:"
                        + " the console will not ask anyone to log in, and the server accepts every"
                        + " request without a token.");
            }
            return;
        }

        List<String> methods = configuredMethods(auth);
        if (methods.isEmpty()) {
            // 这一档在本次改动前只意味着「浏览器上没得选，还能贴一个静态令牌」。
            // 现在静态令牌也按配置出现，因此这里是真的没有任何入口——
            // 登录页会显示一条「服务端要求登录但没有开启任何方式」，运维得先解决它
            log.warn("the console requires a login but no login method is available: enable one of"
                    + " paimon.rest.auth.console.password.enabled,"
                    + " …client-credentials.enabled or …oidc.enabled, or list a token in"
                    + " paimon.rest.auth.tokens. Until then nobody can sign in to the web console"
                    + " and every anonymous request is rejected.");
        } else {
            // 配置齐了就报告一行：运维需要从启动日志确认「登录是按我配的那样生效的」，
            // 而不是去试一次登录才知道
            log.info("console login methods: {}", String.join(", ", methods));
        }

        if (!auth.isEnabled()) {
            // 这是默认组合（console.required=true、auth.enabled=false）。必须说清楚它挡住了
            // 什么、没挡住什么——否则会被当成真正的访问控制
            log.warn("the console requires a login while paimon.rest.auth.enabled is false: the console"
                    + " asks for credentials before showing anything, but /v1/**,"
                    + " /api/catalog/v1/** and /api/management/v1/** still answer anonymous requests."
                    + " Set paimon.rest.auth.enabled=true to make it an access boundary.");
        }

        warnAboutDefaultPassword(console.getPassword());

        if (oidc.isEnabled()) {
            warnAboutOidc(oidc);
        }
    }

    /**
     * 已配置的登录方式（只看配置，不看 OIDC 的发现文档能不能拉到）。
     *
     * <p>与 {@code /api/console/v1/auth} 下发的 {@code methods} 是同一套判据，因此要在
     * 启动日志里如实列出：两者不一致时（日志里有、登录页上没有），运维会照着日志去
     * 找一个并不存在的页签。唯一的差别是 OIDC——它在运行时还取决于发现文档，
     * 那要发一次外部请求，不适合放在启动路径上。
     */
    private static List<String> configuredMethods(RestServerProperties.Auth auth) {
        RestServerProperties.Auth.Console console = auth.getConsole();
        List<String> methods = new ArrayList<>();
        if (console.getPassword().isEnabled()) {
            methods.add("password");
        }
        if (console.getClientCredentials().isEnabled()) {
            methods.add("client-credentials");
        }
        if (console.getOidc().isEnabled()) {
            methods.add("oidc");
        }
        if (auth.hasStaticTokens()) {
            methods.add("static-token");
        }
        return methods;
    }

    /**
     * 内置默认密码的告警。
     *
     * <p>默认账号 {@code admin/admin} 是「开箱能用」，不是「建议使用」：任何能访问这个
     * 地址的人都能用它进来。这一条只在密码仍等于内置值（{@code admin}）时出现，
     * 改掉之后就不再打扰——否则每次启动都刷一条没人看的告警，
     * 真正需要注意的那条也会被一起忽略掉。
     */
    private void warnAboutDefaultPassword(RestServerProperties.Auth.Password password) {
        if (!password.isEnabled()) {
            return;
        }
        if (password.getUsers().isEmpty()) {
            log.warn("paimon.rest.auth.console.password.enabled is true but no users are configured:"
                    + " nobody can log in this way");
            return;
        }
        List<String> weak = password.getUsers().entrySet().stream()
                .filter(entry -> DEFAULT_PASSWORD.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (!weak.isEmpty()) {
            log.warn("console password login uses its built-in default password for {}: anyone who can"
                    + " reach this address can log in as them. Change"
                    + " paimon.rest.auth.console.password.users, or point the account at another"
                    + " principal with …password.principals.", String.join(", ", weak));
        }
    }

    /**
     * 密码账号在授权链路里认不出人时的告警。
     *
     * <p>登录成功却什么也看不到，是开启授权后最容易踩的坑：账号名（默认 {@code admin}）
     * 与服务管理员名单、主体表都对不上时，请求会被判成「无权访问」。这里说的是排查方向，
     * 不是校验——账号名与主体名本来就允许不同（用 {@code password.principals} 映射）。
     */
    private void warnAboutPasswordAccountsWithoutPrincipal() {
        RestServerProperties.Auth.Console console = properties.getAuth().getConsole();
        if (!console.getPassword().isEnabled() || !properties.getAuthorization().isEnabled()) {
            return;
        }
        List<String> unresolved = new ArrayList<>();
        for (String username : console.getPassword().getUsers().keySet()) {
            String mapped = console.getPassword().getPrincipals().get(username);
            String principal = mapped == null || mapped.isBlank() ? username : mapped;
            if (properties.getAuthorization().getServiceAdmins().contains(principal)) {
                continue;
            }
            if (principalRepository.findByName(principal).isEmpty()) {
                unresolved.add(username + " -> " + principal);
            }
        }
        if (!unresolved.isEmpty()) {
            log.warn("authorization is enabled but these console password accounts map to a principal"
                    + " that does not exist: {}. They can log in and then see nothing. Add the"
                    + " principal name to paimon.rest.authorization.service-admins, create the"
                    + " principal, or remap the account with …console.password.principals",
                    String.join(", ", unresolved));
        }
    }

    /** OIDC 的配置缺项与权限提示。 */
    private void warnAboutOidc(RestServerProperties.Auth.Oidc oidc) {
        if (!hasText(oidc.getIssuerUri()) || !hasText(oidc.getClientId())) {
            log.warn("paimon.rest.auth.console.oidc.enabled is true but issuer-uri / client-id is"
                    + " missing: OIDC login will not be offered on the console login page");
        }
        if (!hasText(oidc.getRedirectUri())) {
            // IdP 比对的是完整字符串，缺了它前端只能自己猜一个，
            // 而猜错的唯一表现是「回到 IdP 时报 redirect_uri 不合法」
            log.warn("paimon.rest.auth.console.oidc.redirect-uri is not set: the console cannot start"
                    + " the OIDC flow without it (it must match the redirect URI registered at the"
                    + " identity provider)");
        }
        if (!hasText(oidc.getAudience())) {
            log.warn("paimon.rest.auth.console.oidc.audience is not set: tokens issued for other"
                    + " applications by the same issuer will also be accepted");
        }
        // 登录成功但没有任何权限，是 OIDC 接入最常见的「配好了却不能用」。
        // 这里说的是排查方向，不是校验——主体名由 IdP 决定，服务端无从预知
        log.info("OIDC logins authenticate as the value of the {} claim; that name must exist in"
                + " paimon.rest.authorization.service-admins or be registered as a principal,"
                + " otherwise the console will log in and then see nothing",
                oidc.getPrincipalClaim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
