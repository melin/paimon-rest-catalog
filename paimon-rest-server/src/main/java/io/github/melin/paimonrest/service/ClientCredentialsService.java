package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.PrincipalEntity;
import io.github.melin.paimonrest.domain.repo.PrincipalRepository;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Secrets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * OAuth 2.0 客户端凭据流程（控制台的默认登录方式）。
 *
 * <p>凭据是<b>主体的 clientId 与 clientSecret</b>，与引擎调用管理 API 时用的是
 * 同一份数据（{@code paimon_principal} 表）。这里做到三件事：
 * 校验凭据、把 clientId 翻译成主体名、让 {@link AccessTokenService} 签发令牌。
 *
 * <p><b>为什么不另设一套「控制台登录账号」。</b>那样做等于把同一件事说两遍：
 * 主体已经有 clientId / clientSecret，而服务端登录账号并不在授权链路里，
 * 只是「碰巧与某个主体同名」才有权限。两者并存时，排查权限问题的第一个问题
 * 会变成「你用的是哪套凭据」——这类问题不该存在。
 *
 * <p><b>不区分「clientId 不存在」与「clientSecret 不对」。</b>两种情况都返回
 * {@code invalid_client}，且都走一次摘要比较：前者如果直接返回，响应时间会
 * 短一截，攻击者据此枚举出哪些 clientId 是真实的。枚举出 clientId 之后，
 * 爆破面就从「两个都不知道」缩小到「只知道密码」，这个便宜不能给。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientCredentialsService {

    /**
     * 本工程认识的唯一 scope。
     *
     * <p>Polaris 用 scope 表示「以哪个 principal role 的身份访问」
     * （{@code PRINCIPAL_ROLE:<name>}），而本工程的授权判定是把主体的全部
     * principal role 并起来算权限（见 {@code AuthorizationService}），
     * 没有「挑一个角色」这一层。因此这里只接受
     * {@code PRINCIPAL_ROLE:ALL}（Polaris Console 的默认值，表示不限制），
     * 其余取值一律拒绝。
     *
     * <p><b>为什么拒绝而不是忽略。</b>接受了却不生效的 scope 是最糟的一种：
     * 调用方以为「我只授予了只读角色」，实际拿到的是主体的全部权限，
     * 而这一点在测试里不会暴露（请求都成功）。拒绝至少是可见的。
     */
    public static final String SCOPE_ALL = "PRINCIPAL_ROLE:ALL";

    /**
     * RFC 6749 第 5.2 节的兜底错误码：请求缺参数，或服务端不提供这个流程。
     *
     * <p>单独拎出来是因为它对「scope 取值不支持」**不适用**——
     * 规范把那种情形定义为 {@code invalid_scope}（见 {@link #issue}）。
     */
    private static final String OAUTH_INVALID_REQUEST = "invalid_request";

    /**
     * 主体不存在时用来做等时比较的假摘要。
     *
     * <p>取值无所谓，只要它是合法的 64 位十六进制摘要——它永远不可能匹配上，
     * 存在的意义只是让「主体不存在」这条路径也走一遍 {@code Secrets.matches}，
     * 从而与「密码不对」耗时相当。
     */
    private static final String ABSENT_PRINCIPAL_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private final RestServerProperties properties;

    private final PrincipalRepository principalRepository;

    private final AccessTokenService accessTokens;

    private final LoginAttemptLimiter limiter;

    /** 签发结果。字段名对应 RFC 6749 第 5.1 节的令牌响应。 */
    public record IssuedToken(String accessToken, String tokenType, long expiresInSeconds, String scope) {
    }

    /**
     * 控制台登录是否可用：控制台确实需要登录，且客户端凭据方式没被关掉。
     *
     * <p>注意门槛不是 {@code paimon.rest.auth.enabled} 单独一项：控制台自己
     * 要求登录（{@code console.required}，默认开）时，即使数据面还是匿名的，
     * 也需要这个换令牌的入口，否则登录页上会少一种方式。
     */
    public boolean enabled() {
        return properties.getAuth().getConsole().loginRequired(properties.getAuth().isEnabled())
                && properties.getAuth().getConsole().getClientCredentials().isEnabled();
    }

    /** 本方式自己的失败限速策略。 */
    private RestServerProperties.Auth.RateLimit rateLimit() {
        return properties.getAuth().getConsole().getClientCredentials().getRateLimit();
    }

    /**
     * 用客户端凭据换取访问令牌。
     *
     * @param clientId      主体的 clientId
     * @param clientSecret  主体的 clientSecret 明文
     * @param scope         请求的授权范围；空或 {@value #SCOPE_ALL} 之外的取值会被拒绝
     * @param clientAddress 来源地址，用于失败限速
     * @throws ApiException 400 参数不合法 / 401 凭据不符 / 429 失败次数超限
     */
    public IssuedToken issue(String clientId, String clientSecret, String scope, String clientAddress) {
        if (!enabled()) {
            // 400 而不是 401：这是「服务端没提供这个功能」，不是「你给的凭据不对」。
            // 混在一起会让部署者一直怀疑密码，而真正的原因是配置没开
            throw ApiException.protocolError(400, OAUTH_INVALID_REQUEST,
                    "client credentials login is not enabled on this server:"
                    + " set paimon.rest.auth.console.client-credentials.enabled=true, and make sure"
                    + " the console actually requires a login"
                    + " (paimon.rest.auth.enabled=true or paimon.rest.auth.console.required=true)");
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isEmpty()) {
            throw ApiException.protocolError(400, OAUTH_INVALID_REQUEST,
                    "client_id and client_secret are both required");
        }
        String requestedScope = normalizeScope(scope);
        if (requestedScope == null) {
            // invalid_scope 而不是 invalid_request：RFC 6749 第 5.2 节把 scope 取值问题
            // 单独分开（invalid_request 的定义明确排除了它）。两者的 HTTP 状态都是 400，
            // 因此只有错误码能把这个区别带给调用方
            throw ApiException.protocolError(400, "invalid_scope",
                    "unsupported scope \"" + scope + "\": this server grants the"
                    + " principal's full set of roles and therefore only accepts " + SCOPE_ALL);
        }
        if (!limiter.allow(rateLimit(), clientAddress, clientId)) {
            log.warn("refusing client credentials login for {} from {}: too many recent failures",
                    clientId, clientAddress);
            throw ApiException.protocolError(429, "temporarily_unavailable",
                    "too many failed login attempts; retry after"
                            + " " + properties.getAuth().getConsole().getClientCredentials()
                                    .getRateLimit().getWindow().toSeconds() + " seconds");
        }

        Optional<PrincipalEntity> found = principalRepository.findByClientId(clientId);
        // 主体不存在时也走一次摘要比较，避免从耗时上区分出两种失败
        boolean matches = Secrets.matches(
                found.map(PrincipalEntity::getSecretHash).orElse(ABSENT_PRINCIPAL_HASH),
                found.map(PrincipalEntity::getSecretSalt).orElse(""),
                clientSecret);

        if (found.isEmpty() || !matches) {
            limiter.recordFailure(rateLimit(), clientAddress, clientId);
            log.warn("client credentials login rejected from {}: clientId={}",
                    clientAddress, clientId);
            // 措辞刻意与「凭据不符」一致：不要在报文里区分「没这个 clientId」与「密码不对」
            throw ApiException.protocolError(401, "invalid_client", "invalid client credentials");
        }

        PrincipalEntity principal = found.get();
        if (principal.isCredentialRotationRequired()) {
            // 不阻断登录：要轮换凭据，第一步恰恰是先登进控制台。
            // 但要说出来，否则「凭据待轮换」这个状态永远不会被人看到
            log.warn("principal {} logged in while its credentials are marked for rotation;"
                    + " rotate them with POST /api/management/v1/principals/{principal}/rotate",
                    principal.getName());
        }

        AccessTokenService.Issued issued = accessTokens.issue(principal.getName(), requestedScope);
        log.info("issued an access token for principal {} (clientId={}, expires in {}s)",
                principal.getName(), clientId, issued.expiresInSeconds());
        return new IssuedToken(issued.token(), "bearer", issued.expiresInSeconds(), requestedScope);
    }

    /**
     * 归一化 scope。
     *
     * @return 可接受的 scope（空请求归一成 {@value #SCOPE_ALL}）；不可接受时返回 {@code null}
     */
    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SCOPE_ALL;
        }
        // scope 允许是空格分隔的列表（RFC 6749 第 3.3 节）。逐个比对，
        // 允许调用方附带别的空白项之外的标准 scope 会让人以为它们生效了，
        // 因此只要出现不认识的项就拒绝
        String[] items = scope.trim().split("\\s+");
        for (String item : items) {
            if (!SCOPE_ALL.equals(item)) {
                return null;
            }
        }
        return SCOPE_ALL;
    }
}
