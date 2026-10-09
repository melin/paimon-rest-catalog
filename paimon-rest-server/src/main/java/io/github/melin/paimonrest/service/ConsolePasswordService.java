package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Digests;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户名 + 密码登录（控制台的默认方式）。
 *
 * <p>账号来自配置（{@code paimon.rest.auth.console.password.users}，默认 {@code admin/admin}），
 * 不是数据库里的主体。两者回答的是不同的问题：<b>这里回答「谁能打开控制台」，
 * 授权链路回答「进来之后能做什么」</b>。登录成功后签发的令牌仍然带主体名
 * （默认就是用户名，也可以用 {@code password.principals} 映射到别的主体名），
 * 因此授权判定照常按主体名走。
 *
 * <p>与 {@link ClientCredentialsService} 的三点一致，都是为了同一类问题：
 *
 * <ol>
 *   <li><b>不区分「用户名不存在」与「密码不对」</b>——两者返回一字不差的报文，
 *       且都走一次摘要比较，避免从响应内容或耗时上枚举出哪些用户名是有效的。
 *   <li><b>失败限速</b>——用户名密码比客户端凭据更怕爆破：clientSecret 是 32 字节
 *       随机值，而人定的密码可能只有六七个字符。
 *   <li><b>令牌仍然由 {@link AccessTokenService} 签发</b>——本类只负责「换还是不换」，
 *       令牌长什么样、活多久、怎么验签都不在这里。
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsolePasswordService {

    /**
     * 用户名不存在时用来做等时比较的假密码。
     *
     * <p>取值无所谓——它永远不可能匹配上（真实配置里不允许出现这个值，
     * 即便出现，密码字段也会被当作普通密码处理）。存在的意义只是让
     * 「用户名不存在」这条路径也走一遍摘要比较，与「密码不对」耗时相当。
     */
    private static final String ABSENT_PASSWORD_DIGEST = "absent";

    private final RestServerProperties properties;

    private final AccessTokenService accessTokens;

    private final LoginAttemptLimiter limiter;

    /** 签发结果；字段名是控制台的驼峰风格（本端点不是 OAuth 规范端点）。 */
    public record IssuedLogin(String accessToken, String tokenType, long expiresInSeconds, String principal) {
    }

    /**
     * 本方式是否可用。
     *
     * <p>与控制台其它登录方式同一条门槛：只有当控制台确实需要登录时，
     * 换令牌的入口才开放。
     */
    public boolean enabled() {
        return properties.getAuth().getConsole().loginRequired(properties.getAuth().isEnabled())
                && password().isEnabled();
    }

    /** 配置里的账号表。 */
    public Map<String, String> users() {
        return password().getUsers();
    }

    /**
     * 用用户名与密码换访问令牌。
     *
     * @param username      配置里的用户名
     * @param password      明文密码
     * @param clientAddress 来源地址，用于失败限速
     * @throws ApiException 400 参数不合法或本方式未启用 / 401 凭据不符 / 429 失败次数超限
     */
    public IssuedLogin login(String username, String password, String clientAddress) {
        if (!enabled()) {
            // 400 而不是 401：这是「服务端没提供这个功能」，不是「你给的凭据不对」
            throw ApiException.badRequest("username/password login is not enabled on this server:"
                    + " set paimon.rest.auth.console.password.enabled=true, and make sure the console"
                    + " actually requires a login (paimon.rest.auth.enabled=true or"
                    + " paimon.rest.auth.console.required=true)");
        }
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw ApiException.badRequest("username and password are both required");
        }

        String name = username.trim();
        if (!limiter.allow(rateLimit(), clientAddress, name)) {
            log.warn("refusing console password login for {} from {}: too many recent failures",
                    name, clientAddress);
            throw new ApiException(429, null, null,
                    "too many failed login attempts; retry after"
                            + " " + rateLimit().getWindow().toSeconds() + " seconds");
        }

        String expected = users().get(name);
        // 用户名不存在时也走一次摘要比较，避免从耗时上区分出两种失败
        boolean matches = MessageDigest.isEqual(
                digest(expected == null ? ABSENT_PASSWORD_DIGEST : expected),
                digest(password));

        if (expected == null || !matches) {
            limiter.recordFailure(rateLimit(), clientAddress, name);
            log.warn("console password login rejected from {}: username={}", clientAddress, name);
            // 措辞刻意与「密码不对」一致：不要在报文里区分「没这个用户名」与「密码不对」
            throw new ApiException(401, null, null, "invalid username or password");
        }

        String principal = principalName(name);
        AccessTokenService.Issued issued =
                accessTokens.issue(principal, ClientCredentialsService.SCOPE_ALL);
        log.info("issued an access token for principal {} (console username={}, expires in {}s)",
                principal, name, issued.expiresInSeconds());
        return new IssuedLogin(issued.token(), "Bearer", issued.expiresInSeconds(), principal);
    }

    /**
     * 用户名 → 主体名。
     *
     * <p>默认就是用户名本身；{@code password.principals} 里有映射时用它，
     * 这样「开门的账号」不必为授权再建一个主体（与静态令牌的
     * {@code token-principals} 同一个办法）。
     */
    public String principalName(String username) {
        String mapped = password().getPrincipals().get(username);
        return mapped == null || mapped.isBlank() ? username : mapped;
    }

    /** 本方式自己的失败限速策略。 */
    private RestServerProperties.Auth.RateLimit rateLimit() {
        return password().getRateLimit();
    }

    private RestServerProperties.Auth.Password password() {
        return properties.getAuth().getConsole().getPassword();
    }

    /** 比较的是摘要而不是明文：明文比较会在第一个不同的字符处返回。 */
    private static byte[] digest(String value) {
        return Digests.sha256Hex(value).getBytes(StandardCharsets.UTF_8);
    }
}
