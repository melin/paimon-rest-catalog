package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import io.github.melin.paimonrest.support.Digests;
import io.github.melin.paimonrest.support.Jwt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 令牌认证链：一个 Bearer 令牌来自哪里、对应哪个主体。
 *
 * <p>三种来源依次尝试，任何一条认出令牌就停下：
 *
 * <ol>
 *   <li><b>静态令牌</b>（{@code paimon.rest.auth.tokens}）——运维配置的长期凭据，给机器用。
 *   <li><b>控制台访问令牌</b>（{@link AccessTokenService}）——控制台用主体凭据登录后
 *       拿到的 JWT，本服务端签发。
 *   <li><b>OIDC 令牌</b>（{@link OidcService}）——外部身份提供方签发，本服务端验签。
 * </ol>
 *
 * <p><b>顺序是有意的。</b>静态令牌是一个字符串包含判断（零解析成本），
 * 自签发的 JWT 是一次 HMAC，OIDC 的可能是 RSA 验签（最贵，还可能要刷新 JWKS）。
 * 便宜的放前面，让最常见的调用路径（引擎用静态令牌）不付验签的钱。
 *
 * <p><b>三种来源在这里合流，下游看不到区别。</b>授权判定按主体名查授权链路，
 * 审计字段记主体名——它们都不需要知道令牌是配置里的字符串、控制台签的 JWT，
 * 还是 Keycloak 签的 JWT。加一种认证方式只改这个类，
 * 这正是「认证可替换、授权只有一套」应当呈现的样子。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenAuthenticationService {

    /** 令牌来源标识。写进诊断信息与日志，不参与判定。 */
    public static final String SOURCE_STATIC = "static-token";

    public static final String SOURCE_CONSOLE = "console-access-token";

    public static final String SOURCE_OIDC = "oidc";

    private final RestServerProperties properties;

    private final AccessTokenService accessTokens;

    private final OidcService oidc;

    /**
     * 认证一个令牌。
     *
     * @return 令牌有效时的身份；无效（含格式不对、签名不符、已过期）时为空。
     *         调用方不应区分这些原因——它们对外都是同一个 401
     */
    public Optional<AuthenticatedToken> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (matchesStaticToken(token)) {
            // 静态令牌没有失效时刻：它在配置里，运维删掉它才算失效
            return Optional.of(new AuthenticatedToken(resolveStaticPrincipal(token), null));
        }

        // 走到这里令牌必须是 JWT 形状。解析一次，三个来源共用
        Jwt.Decoded decoded;
        try {
            decoded = Jwt.decode(token);
        } catch (IllegalArgumentException e) {
            // 静态令牌不认识、又不是 JWT——最常见的原因是令牌粘错或截断
            return Optional.empty();
        }

        Optional<AuthenticatedToken> fromConsole = accessTokens.verify(decoded);
        if (fromConsole.isPresent()) {
            return fromConsole;
        }
        return oidc.verify(decoded);
    }

    /** 令牌来源标识，只用于诊断展示。 */
    public String sourceOf(String token) {
        if (matchesStaticToken(token)) {
            return SOURCE_STATIC;
        }
        try {
            Jwt.Decoded decoded = Jwt.decode(token);
            if (accessTokens.verify(decoded).isPresent()) {
                return SOURCE_CONSOLE;
            }
            if (oidc.verify(decoded).isPresent()) {
                return SOURCE_OIDC;
            }
        } catch (IllegalArgumentException ignored) {
            // 解析不了就没有来源可言
        }
        return null;
    }

    /**
     * 令牌是否在静态列表中。
     *
     * <p>逐个比摘要而不是原文：令牌是长期凭据，直接比较会在第一个不同的字符处返回，
     * 理论上可被用来逐字符试探。列表通常只有几条，全部走一遍的开销可以忽略。
     */
    private boolean matchesStaticToken(String token) {
        byte[] candidate = digest(token);
        for (String registered : properties.getAuth().getTokens()) {
            if (registered != null && MessageDigest.isEqual(digest(registered), candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 静态令牌 → 主体名。
     *
     * <p>优先取 {@code token-principals} 的显式映射；未配置时退化为「令牌即主体名」，
     * 这样本地调试可以直接用 {@code -H 'Authorization: Bearer alice'} 以指定主体身份操作。
     */
    private String resolveStaticPrincipal(String token) {
        String mapped = properties.getAuth().getTokenPrincipals().get(token);
        return mapped == null || mapped.isBlank() ? token : mapped;
    }

    private static byte[] digest(String value) {
        return Digests.sha256Hex(value).getBytes(StandardCharsets.UTF_8);
    }
}
