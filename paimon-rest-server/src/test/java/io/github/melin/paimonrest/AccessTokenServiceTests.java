package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.service.AccessTokenService;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import io.github.melin.paimonrest.support.Jwt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link AccessTokenService} 的单元测试。
 *
 * <p>这一层是「令牌为什么失效」的答案所在。用户看到的永远是 401，
 * 而可能的原因有：过期、密钥换了、签发者不符、令牌被篡改。把它们逐个钉死，
 * 线上排查就只剩配置问题。
 *
 * <p>时间用固定时钟推进，不用 {@code Thread.sleep}：验证「TTL 之后失效」
 * 若靠真实等待，一个 1 小时的 TTL 不可能被测试覆盖，而改成 1 秒又会引入
 * 机器负载导致的假失败。
 */
class AccessTokenServiceTests {

    private static final String KEY = base64Key("0123456789abcdef0123456789abcdef");

    private final TestClock clock = new TestClock("2026-01-01T00:00:00Z");

    @Test
    void issuedTokenCarriesThePrincipalAndTheConfiguredTtl() {
        AccessTokenService service = service(KEY, Duration.ofHours(2));

        AccessTokenService.Issued issued = service.issue("alice", "PRINCIPAL_ROLE:ALL");

        assertEquals(7200, issued.expiresInSeconds());
        Jwt.Decoded decoded = Jwt.decode(issued.token());
        assertEquals("alice", decoded.claim("sub"));
        assertEquals("paimon-rest", decoded.claim("iss"));
        assertEquals("PRINCIPAL_ROLE:ALL", decoded.claim("scope"));
        assertEquals(clock.instant().getEpochSecond() + 7200, decoded.numericClaim("exp"));
        assertTrue(decoded.claim("jti") != null && !decoded.claim("jti").isBlank());

        Optional<AuthenticatedToken> verified = service.verify(issued.token());
        assertTrue(verified.isPresent());
        assertEquals("alice", verified.get().principal());
        assertEquals((clock.instant().getEpochSecond() + 7200) * 1000, verified.get().expiresAtMillis());
    }

    /** 不带 scope 时不写该 claim，而不是写一个空串。 */
    @Test
    void emptyScopeIsOmitted() {
        AccessTokenService service = service(KEY, Duration.ofMinutes(30));
        assertFalse(Jwt.decode(service.issue("alice", "  ").token()).claims().containsKey("scope"));
    }

    /** TTL 到期后立即失效——不多给一秒，因为没有撤销机制时这就是唯一的暴露窗口上限。 */
    @Test
    void tokenExpiresExactlyAtTheTtlBoundary() {
        AccessTokenService service = service(KEY, Duration.ofMinutes(30));
        String token = service.issue("alice", null).token();

        clock.advance(Duration.ofMinutes(29));
        assertTrue(service.verify(token).isPresent(), "未到期就失效会让用户莫名被登出");

        clock.advance(Duration.ofMinutes(1));
        assertFalse(service.verify(token).isPresent());
    }

    /** 换一把签名密钥后，此前签发的令牌必须全部失效——这是唯一的「全员登出」手段。 */
    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        AccessTokenService first = service(KEY, Duration.ofHours(1));
        AccessTokenService second = service(base64Key("ffffffffffffffffffffffffffffffff"), Duration.ofHours(1));

        assertFalse(second.verify(first.issue("alice", null).token()).isPresent());
    }

    /** 篡改载荷（把主体改成 admin）必须失效。 */
    @Test
    void tamperedTokenIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        String[] parts = service.issue("alice", null).token().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"paimon-rest\",\"sub\":\"admin\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));

        assertFalse(service.verify(parts[0] + "." + forgedPayload + "." + parts[2]).isPresent());
    }

    /**
     * 签发者不符的令牌被拒。
     *
     * <p>这条守的是「同一份密钥被多个服务共用」的部署：密钥相同、签名合法，
     * 但令牌不是发给本服务的。少了这个校验，任何一个能拿到密钥的服务
     * 都能替本服务签发身份。
     */
    @Test
    void tokenWithAnotherIssuerIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "another-service");
        claims.put("sub", "alice");
        claims.put("exp", clock.instant().getEpochSecond() + 3600);
        String forged = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY));

        assertFalse(service.verify(forged).isPresent());
    }

    /** 没有 exp 的令牌被拒：那意味着它永不过期。 */
    @Test
    void tokenWithoutExpiryIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "paimon-rest");
        claims.put("sub", "alice");
        String forged = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY));

        assertFalse(service.verify(forged).isPresent());
    }

    /** 尚未生效（nbf 在未来）的令牌被拒。 */
    @Test
    void tokenNotYetValidIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "paimon-rest");
        claims.put("sub", "alice");
        claims.put("exp", clock.instant().getEpochSecond() + 3600);
        claims.put("nbf", clock.instant().getEpochSecond() + 600);
        String forged = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY));

        assertFalse(service.verify(forged).isPresent());
    }

    /** 空主体、乱码令牌、静态令牌都要被稳妥拒绝，而不是抛异常。 */
    @Test
    void malformedTokensAreRejectedWithoutThrowing() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));

        // 显式转型为 String：verify 有两个重载，null 会引发歧义
        assertFalse(service.verify((String) null).isPresent());
        assertFalse(service.verify("").isPresent());
        assertFalse(service.verify("opaque-static-token").isPresent());
        assertFalse(service.verify("a.b.c").isPresent());
    }

    /**
     * 未配置签名密钥时随机生成，且进程之间互不认账。
     *
     * <p>「互不认账」正是要断言的行为：如果随机密钥能被另一个实例猜中，
     * 那这个降级方案反而是安全的——但它不会，所以多实例部署必须显式配置密钥。
     * 这个测试同时也说明本地单机调试是安全的（重启后令牌失效，仅此而已）。
     */
    @Test
    void randomKeyIsGeneratedWhenUnsetAndIsNotSharedAcrossInstances() {
        AccessTokenService first = service(null, Duration.ofHours(1));
        AccessTokenService second = service(null, Duration.ofHours(1));

        String token = first.issue("alice", null).token();
        assertTrue(first.verify(token).isPresent());
        assertFalse(second.verify(token).isPresent(), "两个实例不该共享同一把随机密钥");
    }

    /** 密钥配置写错时必须启动失败，不能静默退回随机密钥。 */
    @Test
    void invalidSigningKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> service("not-base64!!!", Duration.ofHours(1)),
                "非 base64 的密钥应当让服务起不来");

        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> service(shortKey, Duration.ofHours(1)),
                "短于 32 字节的 HMAC 密钥可被暴力还原");

        // 合法的最小长度：32 字节，必须通过
        service(base64Key("0123456789abcdef0123456789abcdef"), Duration.ofHours(1));
    }

    /** TTL 写成 0 或负数时收敛到 1 秒，而不是签出一个立刻失效的令牌。 */
    @Test
    void nonPositiveTtlIsClampedToOneSecond() {
        assertEquals(1, service(KEY, Duration.ZERO).ttlSeconds());
        assertEquals(1, service(KEY, Duration.ofSeconds(-100)).ttlSeconds());

        AccessTokenService service = service(KEY, Duration.ZERO);
        clock.advance(Duration.ofMillis(999));
        assertTrue(service.verify(service.issue("alice", null).token()).isPresent());
    }

    // ------------------------------------------------------------------ 工具

    private RestServerProperties properties(String signingKey, Duration ttl) {
        RestServerProperties properties = new RestServerProperties();
        properties.getAuth().setEnabled(true);
        properties.getAuth().getAccessToken().setTtl(ttl);
        properties.getAuth().getAccessToken().setSigningKey(signingKey);
        return properties;
    }

    private AccessTokenService service(String signingKey, Duration ttl) {
        return new AccessTokenService(properties(signingKey, ttl), clock);
    }

    private static String base64Key(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
