package com.example.paimonrest.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code paimon.rest.*} 配置项。
 *
 * <p>加了 {@link Validated}：数值与时长类配置写错（负数、零）会让服务带着无效参数跑起来，
 * 直到第一次真正用上才暴露。这里让它在启动绑定时就失败，错误信息带上属性名，
 * 比运行时再看日志去猜要省事。
 */
@ConfigurationProperties(prefix = "paimon.rest")
@Validated
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

    private final Storage storage = new Storage();

    private final StorageCredentialCache storageCredentialCache = new StorageCredentialCache();

    private final CredentialManager credentialManager = new CredentialManager();

    private final FileIo fileIo = new FileIo();

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

    /**
     * {@code paimon.rest.storage.*}：服务端访问对象存储所用的凭据与连接参数。
     *
     * <p>键名与 Polaris 1.8.0 配置参考的「Storage & Credentials」一节逐项对应，
     * 只把前缀从 {@code polaris} 换成 {@code paimon.rest}，运维从 Polaris 迁过来时
     * 配置可以按名字平移。唯一的结构差异是具名存储：Polaris 写成
     * {@code polaris.storage.aws.<storage>.access-key}，与同层的
     * {@code polaris.storage.aws.access-key} 共用键空间；Spring 的绑定器无法在
     * 一个 {@code aws} 前缀下同时接受具名字段和动态子节点，因此这里下沉一层，
     * 写成 {@code paimon.rest.storage.aws.storages.<storage>.access-key}。
     *
     * <p><b>连接池与超时项的作用范围。</b>{@code read-timeout}、{@code connect-timeout}
     * 之外的连接池参数（{@code max-http-connections}、{@code connection-max-idle-time}、
     * {@code connection-time-to-live}、{@code connection-acquisition-timeout}、
     * {@code expect-continue-enabled}）在 JDK 的 {@code HttpClient} 上找不到对应调节项，
     * 当前实现只做取值校验与启动日志输出。保留这些键是为了配置面与 Polaris 一一对应，
     * 迁移时不必删配置、也不必担心它们悄悄失效——它们本来就没生效。
     */
    @Getter
    @Setter
    public static class Storage {

        private final Aws aws = new Aws();

        private final Gcp gcp = new Gcp();

        /** STS 客户端缓存上限，对应 Polaris 的 {@code polaris.storage.clients-cache-max-size}。 */
        @Min(1)
        private Integer clientsCacheMaxSize;

        /** 连接池上限，对应 Polaris 的 {@code polaris.storage.max-http-connections}。 */
        @Min(1)
        private Integer maxHttpConnections;

        /** 单次请求的读取超时。 */
        private Duration readTimeout;

        /** TCP 建连超时。 */
        private Duration connectTimeout;

        /** 从连接池取连接的等待上限。 */
        private Duration connectionAcquisitionTimeout;

        /** 池中连接的闲置上限。 */
        private Duration connectionMaxIdleTime;

        /** 池中连接的总存活时长。 */
        private Duration connectionTimeToLive;

        /** 是否发送 {@code Expect: 100-continue}。 */
        private Boolean expectContinueEnabled;

        /**
         * AWS 凭据。
         *
         * <p>{@code access-key}/{@code secret-key} 是默认凭据，用于未指定具名存储的 S3 catalog；
         * 两者都不配时，凭据来源退化为环境（实例角色、环境变量、配置文件），
         * 与 Polaris 的「默认凭据链」一致。
         */
        @Getter
        @Setter
        public static class Aws {

            private String accessKey;

            private String secretKey;

            /**
             * 具名存储的凭据，键为 {@code StorageConfigInfo.storageName}。
             *
             * <p>Polaris 用它支持「一个服务托管多组 S3 凭据」：catalog 只引用一个名字，
             * 密钥留在服务端配置里，不出现在管理 API 的报文中。这也是本项目里
             * 唯一能让密钥不落库的途径——{@code storageConfigInfo} 是整份存进
             * {@code storage_config_json} 的。
             */
            private Map<String, Keys> storages = new LinkedHashMap<>();
        }

        /** 一组访问密钥。 */
        @Getter
        @Setter
        public static class Keys {

            private String accessKey;

            private String secretKey;
        }

        /**
         * GCP 凭据。
         *
         * <p>{@code token} 是访问令牌明文；{@code lifespan} 是它的有效期，
         * 两者都对应 Polaris 的 {@code polaris.storage.gcp.token} 与
         * {@code polaris.storage.gcp.lifespan}（后者默认 {@code null}，即用令牌自带的有效期）。
         */
        @Getter
        @Setter
        public static class Gcp {

            private String token;

            private Duration lifespan;
        }
    }

    /**
     * {@code paimon.rest.storage-credential-cache.*}：下发的存储凭据的复用上限。
     *
     * <p>凭据在过期前会被重复下发，缓存避免每次读表都重新解析一遍配置。
     * 缓存条目按签发时的过期时间自然失效，因此这个上限只用于兜住
     * 「大量不同表各自持有一份未过期凭据」时的内存占用。
     */
    @Getter
    @Setter
    public static class StorageCredentialCache {

        /** 缓存条目上限，对应 Polaris 的 {@code polaris.storage-credential-cache.max-entries}。 */
        @Min(1)
        private long maxEntries = 10000;
    }

    /**
     * {@code paimon.rest.credential-manager.*}：凭据签发策略的选择。
     *
     * <p>对应 Polaris 的 {@code polaris.credential-manager.type}，取值是实现标识。
     * 取值非法时启动失败，而不是悄悄退回默认实现——
     * 一个被写错的 {@code noop} 若被当成默认值处理，会让「凭据已停发」的部署
     * 继续把凭据发出去。
     */
    @Getter
    @Setter
    public static class CredentialManager {

        private String type = "default";
    }

    /**
     * {@code paimon.rest.file-io.*}：本部署接入了哪些存储实现的 FileIO。
     *
     * <p>对应 Polaris 的 {@code polaris.file-io.type}。取值为
     * {@code default} / {@code s3} / {@code azure} / {@code gcs} / {@code local}，
     * 决定创建或修改 catalog 时允许出现哪些 {@code storageType}：
     * 服务端没有对应实现却接受这份配置，错误会推迟到引擎第一次读写时才暴露，
     * 那时已经很难定位到是 catalog 的存储类型选错了。
     */
    @Getter
    @Setter
    public static class FileIo {

        private String type = "default";
    }
}
