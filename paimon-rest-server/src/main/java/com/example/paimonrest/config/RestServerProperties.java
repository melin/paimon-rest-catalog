package com.example.paimonrest.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code paimon.rest.*} 配置项。
 */
@ConfigurationProperties(prefix = "paimon.rest")
@Getter
@Setter
public class RestServerProperties {

    /** {@code GET /v1/config} 未指定 warehouse 时返回的默认 prefix。 */
    private String defaultPrefix = "paimon";

    private String defaultWarehouse = "file:///tmp/paimon-warehouse";

    /**
     * prefix 未登记时是否自动登记为 catalog。
     *
     * <p>打开后，引擎首次以任意 warehouse 值调用 {@code /v1/config} 即可接入，
     * 无需预先在服务端创建 catalog。
     */
    private boolean autoCreateCatalog = true;

    /** 表路径模板，可用占位符 {@code {warehouse}}、{@code {database}}、{@code {table}}。 */
    private String pathTemplate = "{warehouse}/{database}.db/{table}";

    private int defaultPageSize = 100;

    private int maxPageSize = 1000;

    private final Auth auth = new Auth();

    private final Credential credential = new Credential();

    private final InitialCatalog initialCatalog = new InitialCatalog();

    private final Authorization authorization = new Authorization();

    /** Bearer 鉴权开关。关闭时所有请求以 {@code principal} 身份通过。 */
    @Getter
    @Setter
    public static class Auth {
        private boolean enabled = false;
        private String principal = "anonymous";
        private List<String> tokens = new ArrayList<>();

        /**
         * 令牌 → 主体名的显式映射。
         *
         * <p>授权判定按主体名查授权链路，因此开启授权时应当配置该映射；
         * 未配置的令牌退化为「令牌即主体名」。
         */
        private Map<String, String> tokenPrincipals = new LinkedHashMap<>();
    }

    /**
     * RBAC 授权开关。
     *
     * <p>默认关闭：关闭时管理 API 只做读写，catalog API 不做权限校验，
     * 与未开启授权的既有部署行为完全一致。开启后管理 API 会校验调用者权限，
     * 且 catalog API 会按 {@code @RequiresPrivilege} 声明校验。
     *
     * <p>开启时建议同时打开 {@code paimon.rest.auth.enabled}，
     * 否则所有请求都落到同一个主体上，授权判定失去区分度。
     */
    @Getter
    @Setter
    public static class Authorization {
        private boolean enabled = false;

        /**
         * 服务管理员主体名。
         *
         * <p>管理规格的权限 enum 里没有「管理主体与服务级角色」这一层权限，
         * 说明 Polaris 把这类操作交给服务管理员而非权限判定，因此这里用名单表达。
         * 名单内的主体可以管理 principal、principal role，并可在任意 catalog 上操作角色与授权。
         */
        private List<String> serviceAdmins = new ArrayList<>(List.of("root"));

        /**
         * 启动时预置的引导主体名。
         *
         * <p>与 {@link #serviceAdmins} 配合，构成授权体系的自举入口：
         * 没有它，全新部署没有任何主体能创建第一个主体。留空则不预置。
         */
        private String bootstrapPrincipal = "root";

        /** 启动时预置的引导角色链：service_admin → 各 catalog 的 catalog_admin。 */
        private String bootstrapPrincipalRole = "service_admin";
    }

    /** 数据访问令牌（凭证下发）配置。 */
    @Getter
    @Setter
    public static class Credential {
        private long ttlSeconds = 3600;
    }

    /** 启动时预置的 catalog 与 database。 */
    @Getter
    @Setter
    public static class InitialCatalog {
        private String prefix;
        private String warehouse;
        private List<DatabaseSeed> databases = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class DatabaseSeed {
        private String name;
        private Map<String, String> options = new LinkedHashMap<>();
    }
}
