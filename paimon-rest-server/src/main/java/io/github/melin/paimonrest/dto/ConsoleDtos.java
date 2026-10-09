package io.github.melin.paimonrest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 控制台元数据端点（{@code GET /api/console/v1/meta}）的响应模型。
 *
 * <p><b>本端点不是 Polaris 规格的一部分</b>，是本工程为 Web 控制台加的一个只读扩展，
 * 与 {@code OBS} / {@code OSS} 两个扩展存储类型同属一类取舍（见
 * {@code docs/management-api-contract.md}）。它只做一件事：把服务端已经在用的枚举与服务配置
 * 导出给前端。
 *
 * <p>为什么不把这些取值直接写死在控制台里：控制台的表单要靠它们渲染下拉框
 * （存储类型、资源类型、可授予的权限），而权限与资源类型的对应关系由
 * {@link Privilege#allowedFor(String)} 从管理规格还原出来——前端复制一份必然漂移，
 * 而且漂移的表现是「提交后才被服务端拒绝」，排查成本高。这里做成一处的导出。
 *
 * <p>端点只暴露枚举取值与服务端自身的配置，不暴露任何主体、catalog 或凭据信息，
 * 因此不做细粒度授权判定（与 {@code /api/management/v1/**} 一样只过认证拦截器）。
 */
public final class ConsoleDtos {

    private ConsoleDtos() {
    }

    /** {@code GET /api/console/v1/meta} 的响应。 */
    public record ConsoleMeta(Service service, Enums enums) {
    }

    /**
     * 服务端生效的配置摘要。
     *
     * <p>字段名刻意用驼峰而不是配置里的短横线形式：控制台按 JSON 字段取值，
     * 保留短横线会让前端到处写引号取属性。配置项名以注释形式标出，便于运维对照。
     */
    public record Service(
            /** {@code spring.application.name} */
            String name,
            /** 从 jar 清单读取的构建版本；从类目录运行时为 {@code development} */
            String version,
            /** {@code paimon.rest.auth.enabled} */
            boolean authEnabled,
            /** {@code paimon.rest.authorization.enabled} */
            boolean authorizationEnabled,
            /** {@code paimon.rest.credential-manager.type} */
            String credentialManagerType,
            /** {@code paimon.rest.file-io.type} */
            String fileIoType,
            /** {@code paimon.rest.default-prefix} */
            String defaultPrefix,
            /** {@code paimon.rest.default-warehouse} */
            String defaultWarehouse,
            /** {@code paimon.rest.auto-create-catalog} */
            boolean autoCreateCatalog,
            /** {@code paimon.rest.default-page-size} */
            int defaultPageSize,
            /** {@code paimon.rest.max-page-size} */
            int maxPageSize,
            /** {@code paimon.rest.path-template} */
            String pathTemplate,
            /** {@code paimon.rest.storage-credential-cache.max-entries} */
            long storageCredentialCacheMaxEntries,
            /** {@code paimon.rest.credential.ttl-seconds} */
            long credentialTtlSeconds) {
    }

    /**
     * 枚举取值。
     *
     * <p>{@code storageTypes} 是全部取值，{@code supportedStorageTypes} 是本部署实际可用的子集
     * （由 {@code file-io.type} 决定）。分成两个列表而不是一个带标记的列表：
     * 控制台需要把不可用的取值渲染成禁用项，而不是干脆不显示——
     * 让人看到「有 OBS 这个类型但当前部署没开」，比看不到要好。
     */
    public record Enums(
            List<String> catalogTypes,
            List<String> storageTypes,
            List<String> supportedStorageTypes,
            List<String> credentialManagerTypes,
            List<String> fileIoTypes,
            List<String> grantTypes,
            /** 资源类型（{@code grantTypes} 的取值）→ 该层级允许授予的权限。 */
            Map<String, List<String>> privilegesByGrantType) {
    }

    /**
     * {@code GET /api/console/v1/auth} 的响应：控制台登录页需要知道的一切。
     *
     * <p><b>这个端点不做鉴权</b>，因为它要回答的正是「怎么登录」。把它放在鉴权之后
     * 会形成死循环：登录页要先知道服务端支持哪些认证方式，才谈得上发起登录。
     *
     * <p>它暴露的内容按「对匿名访问者是否敏感」筛过：认证方式本身、令牌端点、
     * OIDC 的公开端点与公开客户端标识——这些在 OIDC 规范里本来就是公开信息
     * （发现文档任何人都能拉）。<b>不含</b>任何 clientSecret、主体清单、
     * catalog 或凭据。携带有效令牌时额外返回「你是谁」，那是给已登录者看自己的。
     *
     * <p><b>控制台为什么不自己判断认证方式。</b>控制台是构建期打包的静态资源，
     * 读不到服务端环境变量；OIDC 的端点又只有服务端能发现（要拉发现文档）。
     * 因此「支持哪些登录方式、端点在哪」只能由服务端在运行时下发。
     * 前端硬编码一份必然漂移，而漂移的表现是「点了登录按钮没反应」。
     *
     * @param authEnabled   服务端是否要求令牌（{@code paimon.rest.auth.enabled}），
     *                      也就是<b>数据面</b>（catalog API 与管理 API）是否受保护
     * @param consoleRequired 控制台自身是否要求先登录（{@code paimon.rest.auth.console.required}）。
     *                      与 {@code authEnabled} 分开返回，因为控制台需要据此显示一句
     *                      区别很大的话：门禁开着而整体鉴权关着时，数据接口仍然匿名可调，
     *                      这层登录只挡住界面，不是安全边界
     * @param methods       可用的登录方式，取值见 {@link AuthMethod}；顺序即建议的展示顺序。
     *                      <b>只列真的可用的方式</b>：各自按配置判定，关闭或缺配置就不出现。
     *                      因此 {@code consoleRequired=true} 时它仍可能为空——那说明服务端
     *                      要求登录却一个方式都没开，属于配置错误，前端会照实提示
     * @param tokenEndpoint 客户端凭据流程要 POST 的地址（绝对路径，不含主机名）
     * @param oidc          OIDC 方式可用时的连接参数；不可用时为 {@code null}
     * @param session       当前令牌的状态；未携带令牌或令牌无效时 {@code authenticated=false}
     */
    public record ConsoleAuth(
            boolean authEnabled,
            boolean consoleRequired,
            List<String> methods,
            String tokenEndpoint,
            OidcClientConfig oidc,
            CurrentSession session) {
    }

    /** 登录方式的取值。用字符串而不是 enum：前端按字面量分支，多一个取值不该让旧前端解析失败。 */
    public static final class AuthMethod {

        /**
         * 用户名 + 密码，账号在服务端配置里（{@code paimon.rest.auth.console.password}，
         * 默认 {@code admin/admin}）。
         *
         * <p>它排在第一位：不需要运维先去建主体、也不依赖外部身份提供方，
         * 是「装好就能进控制台」的那条路。
         */
        public static final String PASSWORD = "password";

        /** OAuth 2.0 客户端凭据：填主体的 clientId / clientSecret。 */
        public static final String CLIENT_CREDENTIALS = "client-credentials";

        /** OpenID Connect 授权码 + PKCE：跳转到外部身份提供方。 */
        public static final String OIDC = "oidc";

        /**
         * 直接填一个访问令牌。
         *
         * <p>这是给机器与脚本准备的降级入口，不是给人用的正常路径——
         * 要求使用者自己去拿 {@code paimon.rest.auth.tokens} 里的值。
         * 它在列表里排最后，正是为了不鼓励这条路。
         *
         * <p><b>只在 {@code paimon.rest.auth.tokens} 里登记了令牌时才出现。</b>
         * 它没有独立的 {@code enabled} 开关，「开启」的唯一表现就是配置里有值；
         * 没配就列出来，使用者填什么都会被拒。
         */
        public static final String STATIC_TOKEN = "static-token";

        private AuthMethod() {
        }
    }

    /**
     * {@code POST /api/console/v1/login} 的请求体。
     *
     * <p>用 JSON 而不是表单：这个端点是控制台自己的扩展端点，调用方只有控制台，
     * 不背 OAuth 那套 form-urlencoded 的约定。
     */
    public record PasswordLoginRequest(String username, String password) {
    }

    /**
     * {@code POST /api/console/v1/login} 的成功响应。
     *
     * <p>字段是驼峰（本端点是控制台扩展端点，不是 OAuth 规范端点），
     * 但语义与令牌端点的响应一致：拿到的是一个 Bearer 令牌及其有效期。
     * 另外带上 {@code principal}，让前端在跳转前就能显示「以谁的身份进来了」。
     */
    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String principal) {
    }

    /**
     * OIDC 公开客户端参数，全部由服务端从发现文档与配置拼出来。
     *
     * <p>{@code clientSecret} 不在这里，也不该有：控制台用的是公开客户端 + PKCE，
     * 浏览器里放不下秘密——任何放进前端构建产物的密钥都等同于公开。
     */
    public record OidcClientConfig(
            /** 身份提供方的 issuer，用于展示与排查（前端不据此做判断）。 */
            String issuer,
            /** 授权端点：浏览器要跳到这里。 */
            String authorizationEndpoint,
            /** 令牌端点：回调页用授权码换令牌。 */
            String tokenEndpoint,
            String clientId,
            /** 必须在 IdP 侧登记过的回调地址。 */
            String redirectUri,
            String scope) {
    }

    /**
     * 当前令牌的状态。
     *
     * <p>令牌无效时也给一个对象（而非 {@code null}），这样前端不需要为了读
     * 「是否已登录」而处理两种形状。
     */
    public record CurrentSession(
            boolean authenticated,
            /** 主体名；未认证时为 null。 */
            String principal,
            /** 令牌来源，取值 {@code static-token} / {@code console-access-token} / {@code oidc}。 */
            String source,
            /** 令牌失效时刻（毫秒）；静态令牌没有失效时刻，为 null。 */
            Long expiresAtMillis,
            /** 该主体的凭据是否被标记为待轮换，供控制台提醒。未认证时为 false。 */
            boolean credentialRotationRequired) {
    }

    /**
     * {@code POST /api/catalog/v1/oauth/tokens} 的成功响应。
     *
     * <p>字段名是 RFC 6749 第 5.1 节规定的 snake_case，而不是本工程其余 DTO 的驼峰：
     * 这个端点的客户端不只有本控制台——任何按 OAuth 2.0 写的库、
     * 以及 Polaris 官方的 console 都按规范字段名取值。
     * 在这里「与规范一致」比「与工程内其余 DTO 一致」重要。
     */
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresInSeconds,
            @JsonProperty("scope") String scope) {
    }

    /**
     * OAuth 2.0 错误响应（RFC 6749 第 5.2 节）。
     *
     * <p>同样用规范字段名。{@code error} 是机器读的取值，本端点会用到：
     * {@code invalid_request}（缺参数、请求形状不对）、
     * {@code invalid_scope}（scope 取值不支持）、
     * {@code invalid_client}（凭据不符）、
     * {@code unsupported_grant_type}（只支持 {@code client_credentials}）、
     * {@code temporarily_unavailable}（失败次数超限）。
     * {@code error_description} 是给人读的一句话。
     */
    public record TokenError(
            @JsonProperty("error") String error,
            @JsonProperty("error_description") String errorDescription) {
    }
}
