package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import io.github.melin.paimonrest.support.Jwt;
import io.github.melin.paimonrest.support.Paging;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 控制台登录签发的访问令牌。
 *
 * <p>对应 {@code paimon.rest.auth.access-token.*}。本服务只做两件事：
 * 签一个令牌出去，验一个令牌回来。凭据校验（clientId / clientSecret 对不对）
 * 发生在调用方，因为那是主体仓库的事，不是签名的事。
 *
 * <p><b>令牌是自包含的。</b>载荷里只有主体名与时间，服务端不存任何东西。
 * 由此得到三个性质，都是上一版（内存会话表）缺的：
 *
 * <ul>
 *   <li><b>多实例互认</b>——任一实例签发的令牌，其余实例凭同一份密钥即可验证。
 *       内存会话表做不到这点，负载均衡下登录会时灵时不灵。
 *   <li><b>重启不掉线</b>——密钥在配置里，重启后仍然能验签。
 *   <li><b>代价是撤销</b>——令牌签发后到过期前始终有效，服务端没有「作废它」
 *       的开关。轮换签名密钥能让全部令牌立刻失效，但那不区分对象。
 *       因此 {@code ttl} 是唯一的暴露窗口上限，默认取 1 小时而非一天。
 * </ul>
 *
 * <p><b>签名密钥未配置时随机生成。</b>这样本地起一个实例就能用，但重启会让
 * 已签发的令牌全部失效、多实例之间互不认账。启动日志会就此告警，
 * 生产必须显式配置（{@code openssl rand -base64 48}）。
 */
@Slf4j
@Service
public class AccessTokenService {

    private final RestServerProperties properties;

    /** 取当前时间。抽成字段以便测试用固定时钟确定性地验证过期。 */
    private final Clock clock;

    private final byte[] signingKey;

    public AccessTokenService(RestServerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = resolveSigningKey(properties);
    }

    /** 签发结果：令牌与其有效期（秒）。两者的有效范围由同一处计算，不会各说各话。 */
    public record Issued(String token, long expiresInSeconds) {
    }

    /** 令牌有效期（秒）。控制台登录页据此提示「多久后需要重新登录」。 */
    public long ttlSeconds() {
        return Math.max(1, properties.getAuth().getAccessToken().getTtl().toSeconds());
    }

    /** 签发者标识，即 JWT 的 {@code iss}。 */
    public String issuer() {
        return properties.getAuth().getAccessToken().getIssuer();
    }

    /**
     * 为指定主体签发一个访问令牌。
     *
     * @param principal 主体名。授权判定按它查授权链路，因此它必须来自可信来源
     *                  （已通过 clientSecret 校验的主体记录），不能是请求里直接给的字符串
     * @param scope     记录在令牌里的授权范围；为空则不带该 claim
     */
    public Issued issue(String principal, String scope) {
        long nowSeconds = clock.millis() / 1000;
        long ttl = ttlSeconds();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer());
        claims.put("sub", principal);
        claims.put("iat", nowSeconds);
        claims.put("exp", nowSeconds + ttl);
        // jti 目前只用于日志与排查：没有撤销机制，它不参与判定。
        // 留着是因为将来若加黑名单，这是现成的键——事后补一个 claim 会让
        // 已签发的令牌与新签发的令牌形状不一致
        claims.put("jti", Paging.newId());
        if (scope != null && !scope.isBlank()) {
            claims.put("scope", scope);
        }
        return new Issued(Jwt.signHs256(claims, signingKey), ttl);
    }

    /**
     * 验证令牌并取出主体名。
     *
     * <p>任何一步不通过都返回空——调用方只需要知道「这个令牌能不能用」，
     * 把失败原因分成七八种只会让鉴权失败时的处理分叉。
     * 需要区分原因的场合（排查 OIDC 配置）由 {@code OidcService} 自己写日志。
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
     * 提供这个重载是为了让一次请求只解析一遍 JWT——解析本身很便宜，
     * 但三个来源各解析一遍会让「令牌只被解析一次」这个不变量变得需要靠注释维持。
     * <b>调用前必须已经确认这不是静态令牌</b>：静态令牌不是 JWT，解析会失败。
     */
    public Optional<AuthenticatedToken> verify(Jwt.Decoded decoded) {
        if (!Jwt.verifyHmac(decoded, signingKey)) {
            return Optional.empty();
        }
        // 密钥是自己的，但签发者也要比：将来若出现「同一份密钥被多个服务共用」的部署，
        // 这个字段是唯一能区分令牌归属的地方
        if (!issuer().equals(decoded.claim("iss"))) {
            return Optional.empty();
        }
        long nowSeconds = clock.millis() / 1000;
        Long expiresAt = decoded.numericClaim("exp");
        if (expiresAt == null || nowSeconds >= expiresAt) {
            return Optional.empty();
        }
        // 自签发的令牌不给时钟偏移：签发与验证在同一个进程里用同一个时钟，
        // 容忍偏移只会在时钟被回拨时延长令牌寿命
        Long notBefore = decoded.numericClaim("nbf");
        if (notBefore != null && nowSeconds < notBefore) {
            return Optional.empty();
        }
        String principal = decoded.claim("sub");
        return principal == null || principal.isBlank()
                ? Optional.empty()
                : Optional.of(new AuthenticatedToken(principal, expiresAt * 1000L));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 解析签名密钥。
     *
     * <p>格式错误时**启动失败**而不是退回随机密钥：一个写错的密钥配置
     * 如果被静默忽略，部署者会以为「配好了」，直到重启后发现所有人掉线、
     * 多实例之间互相 401 才回头怀疑。密钥长度不够同样失败——
     * HMAC-SHA256 用短密钥签出来的令牌是可以被暴力还原密钥的。
     */
    private static byte[] resolveSigningKey(RestServerProperties properties) {
        String configured = properties.getAuth().getAccessToken().getSigningKey();
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[48];
            new SecureRandom().nextBytes(generated);
            log.warn("paimon.rest.auth.access-token.signing-key is not set: a random key was generated"
                    + " for this process. Tokens issued now will stop working after a restart, and"
                    + " multiple instances will not accept each other's tokens. Set it in production"
                    + " (generate one with: openssl rand -base64 48)");
            return generated;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "paimon.rest.auth.access-token.signing-key must be base64 (generate one with:"
                            + " openssl rand -base64 48)", e);
        }
        if (decoded.length < Jwt.MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException("paimon.rest.auth.access-token.signing-key must decode to at"
                    + " least " + Jwt.MIN_HMAC_KEY_BYTES + " bytes, got " + decoded.length);
        }
        return decoded;
    }
}
