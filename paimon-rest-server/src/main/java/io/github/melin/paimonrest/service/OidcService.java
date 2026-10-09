package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import io.github.melin.paimonrest.support.Jwks;
import io.github.melin.paimonrest.support.Jwt;
import io.github.melin.paimonrest.support.Values;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * OIDC 令牌的验证与发现文档。
 *
 * <p>对应 {@code paimon.rest.auth.console.oidc.*}。本服务不认识任何用户，
 * 也不签发令牌——那些都是外部身份提供方（Keycloak / Auth0 / Okta）的事。
 * 它只回答一个问题：<b>这个令牌是不是该提供方签发的，且属于谁</b>。
 *
 * <p><b>只有两处会碰网络</b>：拉发现文档与拉 JWKS，都是惰性的、带缓存的。
 * 这样做是有意的：控制台的可用性不该取决于 IdP 在某一刻是否可达。
 * 已经缓存过公钥的进程，在 IdP 短暂不可用时仍然能验证令牌（令牌是自包含的），
 * 于是「IdP 重启 30 秒」不会变成「控制台全员被登出」。
 *
 * <p><b>刷新失败时沿用旧值。</b>缓存过期后若拉取失败，返回上一次的结果并记一条
 * 警告，而不是让验证失败。理由同上：过期只是「该刷新了」，不是「之前那份不能用了」。
 * 只有在从没成功拉到过的情况下才真的无法验证。
 *
 * <p><b>JWKS 遇到不认识的 kid 会强制刷新一次。</b>IdP 轮换签名密钥后，
 * 新令牌的 kid 不在缓存里——这是正常情况，不是攻击。给一次立即重取的机会，
 * 否则在缓存过期前的这段时间里，所有新令牌都会被判为无效。
 */
@Slf4j
@Service
public class OidcService {

    /** 发现文档与 JWKS 都是小 JSON，超时给得比业务请求短：它们是登录路径上的阻塞点。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final RestServerProperties properties;

    private final Clock clock;

    private final HttpClient http;

    /** 缓存项为 volatile：读写分别发生在不同请求线程，需要保证可见性。 */
    private volatile Cached<Metadata> metadataCache;

    private volatile Cached<Map<String, PublicKey>> jwksCache;

    public OidcService(RestServerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 发现文档里本服务端用得到的部分。
     *
     * <p>{@code authorizationEndpoint} 与 {@code tokenEndpoint} 会通过
     * {@code /api/console/v1/auth} 下发给浏览器——控制台是构建期打包的静态资源，
     * 没法读环境变量，端点只能由服务端告诉它。
     */
    public record Metadata(String issuer, String authorizationEndpoint, String tokenEndpoint,
                           String jwksUri, String endSessionEndpoint) {
    }

    private record Cached<T>(T value, long fetchedAtMillis) {
    }

    private RestServerProperties.Auth.Oidc oidc() {
        return properties.getAuth().getConsole().getOidc();
    }

    /** 是否配置齐了 OIDC 登录。缺任何一项都算没配好。 */
    public boolean enabled() {
        RestServerProperties.Auth.Oidc oidc = oidc();
        return oidc.isEnabled() && hasText(oidc.getIssuerUri()) && hasText(oidc.getClientId());
    }

    /**
     * 取发现文档；未启用或取不到时为空。
     *
     * <p>同时给控制台的登录页与令牌验证用。前者只在页面加载时调一次，
     * 因此「页面里有没有 SSO 按钮」不会因为 IdP 抖动而闪烁——
     * 缓存命中时它是纯内存操作。
     */
    public Optional<Metadata> metadata() {
        if (!enabled()) {
            return Optional.empty();
        }
        long now = clock.millis();
        Cached<Metadata> current = metadataCache;
        if (current != null && fresh(current, now, oidc().getMetadataCacheTtl().toMillis())) {
            return Optional.of(current.value());
        }
        try {
            Metadata fetched = fetchMetadata(oidc().getIssuerUri());
            metadataCache = new Cached<>(fetched, now);
            return Optional.of(fetched);
        } catch (RuntimeException e) {
            if (current != null) {
                log.warn("failed to refresh OIDC discovery document from {} ({}); keeping the previously"
                        + " fetched one", oidc().getIssuerUri(), e.getMessage());
                return Optional.of(current.value());
            }
            log.warn("failed to fetch OIDC discovery document from {}: {}",
                    oidc().getIssuerUri(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 验证一个 OIDC 令牌并取出主体名。
     *
     * <p>依次做四件事：签名对不对、签发者对不对、时间还在不在范围内、主体是谁。
     * 任何一步不通过都返回空，并把原因写进 debug 日志——登录失败时运维需要从
     * 服务端日志里看出是「签名不对」还是「受众不对」，而这两者在浏览器里
     * 都只表现为 401。
     */
    public Optional<AuthenticatedToken> verify(String token) {
        Jwt.Decoded decoded;
        try {
            decoded = Jwt.decode(token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return verify(decoded);
    }

    /**
     * 验证一个已经解析过的令牌。
     *
     * <p>认证链要依次问三个来源「这是你的令牌吗」，各自都从同一个令牌串出发。
     * 提供这个重载是为了让一次请求只解析一遍 JWT。
     */
    public Optional<AuthenticatedToken> verify(Jwt.Decoded decoded) {
        if (!enabled()) {
            return Optional.empty();
        }
        if (!Jwt.isSupportedAlgorithm(decoded.algorithm())) {
            // 把 none 与不认识的算法单独说出来：它们是攻击特征，不是配置问题
            log.warn("rejected OIDC token with unsupported algorithm {}", decoded.algorithm());
            return Optional.empty();
        }

        Optional<Metadata> metadata = metadata();
        if (metadata.isEmpty()) {
            log.warn("cannot verify OIDC token: the discovery document is unavailable");
            return Optional.empty();
        }
        Metadata document = metadata.get();

        if (!document.issuer().equals(decoded.claim("iss"))) {
            log.debug("rejected OIDC token: issuer {} does not match {}",
                    decoded.claim("iss"), document.issuer());
            return Optional.empty();
        }
        if (!verifySignature(decoded, document)) {
            log.debug("rejected OIDC token: signature verification failed (kid={})", decoded.keyId());
            return Optional.empty();
        }
        if (!timeWindowOk(decoded)) {
            return Optional.empty();
        }
        if (!audienceOk(decoded)) {
            log.debug("rejected OIDC token: audience {} does not match {}",
                    decoded.listClaim("aud"), oidc().getAudience());
            return Optional.empty();
        }

        String claim = oidc().getPrincipalClaim();
        String principal = decoded.claim(claim);
        if (principal == null || principal.isBlank()) {
            log.debug("rejected OIDC token: claim {} is missing or empty", claim);
            return Optional.empty();
        }
        Long expiresAt = decoded.numericClaim("exp");
        return Optional.of(new AuthenticatedToken(principal, expiresAt == null ? null : expiresAt * 1000L));
    }

    /** 清掉两处缓存。测试用来构造「密钥轮换」「IdP 换地址」这类场景。 */
    public void invalidateCaches() {
        metadataCache = null;
        jwksCache = null;
    }

    // ------------------------------------------------------------------ 内部

    private boolean verifySignature(Jwt.Decoded decoded, Metadata document) {
        String keyId = decoded.keyId();
        PublicKey key = publicKey(document.jwksUri(), keyId);
        if (key != null && Jwt.verifyWithPublicKey(decoded, key)) {
            return true;
        }
        // 缓存里没有这个 kid，或验签没过：先认为可能只是密钥轮换，重取一次再判。
        // 不重取的话，IdP 换密钥后在缓存过期前所有新令牌都会被拒
        Map<String, PublicKey> refreshed = fetchJwks(document.jwksUri());
        if (refreshed == null) {
            return false;
        }
        jwksCache = new Cached<>(refreshed, clock.millis());
        PublicKey retried = select(refreshed, keyId);
        return retried != null && Jwt.verifyWithPublicKey(decoded, retried);
    }

    private PublicKey publicKey(String jwksUri, String keyId) {
        Map<String, PublicKey> keys = publicKeys(jwksUri);
        return keys == null ? null : select(keys, keyId);
    }

    /**
     * 按 kid 选公钥。
     *
     * <p>没有 kid 时退化为「唯一的那个」：JWKS 里只有一把钥匙的话，选它比拒签更合理，
     * 而如果有多个，任意选一个都是在赌——此时返回 {@code null} 让验证失败。
     */
    private static PublicKey select(Map<String, PublicKey> keys, String keyId) {
        if (keyId != null && !keyId.isBlank()) {
            return keys.get(keyId);
        }
        return keys.size() == 1 ? keys.values().iterator().next() : null;
    }

    private Map<String, PublicKey> publicKeys(String jwksUri) {
        long now = clock.millis();
        Cached<Map<String, PublicKey>> current = jwksCache;
        if (current != null && fresh(current, now, oidc().getJwksCacheTtl().toMillis())) {
            return current.value();
        }
        Map<String, PublicKey> fetched = fetchJwks(jwksUri);
        if (fetched != null) {
            jwksCache = new Cached<>(fetched, now);
            return fetched;
        }
        return current == null ? null : current.value();
    }

    private Map<String, PublicKey> fetchJwks(String jwksUri) {
        try {
            String body = get(jwksUri);
            Map<String, PublicKey> keys = Jwks.parse(body);
            log.debug("loaded {} public key(s) from {}", keys.size(), jwksUri);
            return keys;
        } catch (RuntimeException e) {
            log.warn("failed to load JWKS from {}: {}", jwksUri, e.getMessage());
            return null;
        }
    }

    private Metadata fetchMetadata(String issuerUri) {
        String base = issuerUri.trim().replaceAll("/+$", "");
        String body = get(base + "/.well-known/openid-configuration");
        Map<String, Object> document = Values.map(io.github.melin.paimonrest.support.Json.read(body));

        String issuer = Values.string(document.get("issuer"));
        String authorizationEndpoint = Values.string(document.get("authorization_endpoint"));
        String tokenEndpoint = Values.string(document.get("token_endpoint"));
        String jwksUri = Values.string(document.get("jwks_uri"));
        if (issuer == null || authorizationEndpoint == null || tokenEndpoint == null || jwksUri == null) {
            throw new IllegalStateException("the discovery document is missing issuer /"
                    + " authorization_endpoint / token_endpoint / jwks_uri");
        }
        // 文档里的 issuer 必须与配置的一致。这是 OIDC 规范的要求，也是防「混入攻击」
        // （mix-up）的关键一步：不校验的话，一个被劫持的 IdP 可以声称自己是别人
        if (!base.equals(issuer.replaceAll("/+$", ""))) {
            throw new IllegalStateException("the discovery document reports issuer " + issuer
                    + ", which does not match the configured " + issuerUri);
        }
        return new Metadata(issuer, authorizationEndpoint, tokenEndpoint, jwksUri,
                Values.string(document.get("end_session_endpoint")));
    }

    private String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private boolean timeWindowOk(Jwt.Decoded decoded) {
        long skewSeconds = Math.max(0, oidc().getClockSkew().toSeconds());
        long nowSeconds = clock.millis() / 1000;
        Long expiresAt = decoded.numericClaim("exp");
        if (expiresAt == null) {
            // 没有 exp 的 OIDC 令牌不可接受：那意味着它永不过期
            log.debug("rejected OIDC token: it has no exp claim");
            return false;
        }
        if (nowSeconds - skewSeconds >= expiresAt) {
            log.debug("rejected OIDC token: expired at {}", expiresAt);
            return false;
        }
        Long notBefore = decoded.numericClaim("nbf");
        if (notBefore != null && nowSeconds + skewSeconds < notBefore) {
            log.debug("rejected OIDC token: not valid before {}", notBefore);
            return false;
        }
        return true;
    }

    private boolean audienceOk(Jwt.Decoded decoded) {
        String expected = oidc().getAudience();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        List<String> actual = decoded.listClaim("aud");
        return actual.contains(expected);
    }

    private static boolean fresh(Cached<?> cached, long nowMillis, long ttlMillis) {
        return nowMillis - cached.fetchedAtMillis() < Math.max(1, ttlMillis);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
