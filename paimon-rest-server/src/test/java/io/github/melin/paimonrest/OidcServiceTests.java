package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.service.OidcService;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import io.github.melin.paimonrest.support.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcService} 的测试。
 *
 * <p>用一个真实的 HTTP 服务器（JDK 自带的 {@code HttpServer}）冒充身份提供方，
 * 而不是 mock 掉 HTTP 调用。理由：这一层真正会出错的地方是「发现文档的字段名对不对」
 * 「JWKS 的编码能不能被解开」「缓存该刷新时有没有刷新」——
 * mock 掉之后这些全都不被覆盖，测试只剩「调了某个方法」这种同义反复。
 *
 * <p>密钥是每次运行现生成的，因此也顺带验证了「换一对密钥就验不过」。
 */
class OidcServiceTests {

    private static final String OTHER_ISSUER = "https://another-idp.example.com";

    private final TestClock clock = new TestClock("2026-01-01T00:00:00Z");

    private HttpServer server;

    private String issuer;

    private KeyPair keyPair;

    /** 当前暴露的 JWKS，测试可替换它来模拟密钥轮换。 */
    private final AtomicReference<String> jwks = new AtomicReference<>("{\"keys\":[]}");

    private final AtomicInteger jwksRequests = new AtomicInteger();

    private final AtomicInteger metadataRequests = new AtomicInteger();

    @BeforeEach
    void startFakeIdp() throws Exception {
        keyPair = rsaKeyPair();
        jwks.set(jwks(keyPair, "key-1"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        issuer = "http://127.0.0.1:" + port;

        server.createContext("/.well-known/openid-configuration", exchange -> {
            metadataRequests.incrementAndGet();
            respond(exchange, 200, discoveryDocument(issuer));
        });
        server.createContext("/jwks", exchange -> {
            jwksRequests.incrementAndGet();
            respond(exchange, 200, jwks.get());
        });
        server.createContext("/token", exchange -> respond(exchange, 200, "{}"));
        server.start();
    }

    @AfterEach
    void stopFakeIdp() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void metadataIsDiscoveredFromTheIssuer() {
        OidcService service = service(issuer, null, "sub");

        OidcService.Metadata metadata = service.metadata().orElseThrow();

        assertEquals(issuer, metadata.issuer());
        assertEquals(issuer + "/authorize", metadata.authorizationEndpoint());
        assertEquals(issuer + "/token", metadata.tokenEndpoint());
        assertEquals(issuer + "/jwks", metadata.jwksUri());
        assertEquals(issuer + "/logout", metadata.endSessionEndpoint());
    }

    @Test
    void validTokenYieldsThePrincipalFromTheDefaultClaim() {
        OidcService service = service(issuer, null, "sub");
        String token = token(keyPair, "key-1", Map.of());

        Optional<AuthenticatedToken> verified = service.verify(token);

        assertTrue(verified.isPresent());
        assertEquals("alice", verified.get().principal());
        assertEquals((clock.instant().getEpochSecond() + 3600) * 1000, verified.get().expiresAtMillis());
    }

    /** 主体名取自可配置的 claim——多数 IdP 里「人」对应的是 preferred_username 或 email。 */
    @Test
    void principalComesFromTheConfiguredClaim() {
        OidcService service = service(issuer, null, "preferred_username");
        String token = token(keyPair, "key-1",
                Map.of("sub", "uuid-1234", "preferred_username", "alice"));

        assertEquals("alice", service.verify(token).orElseThrow().principal());
    }

    /** 配置的 claim 在令牌里不存在时必须拒绝，而不是退回 sub —— 那会静默换一个身份。 */
    @Test
    void missingPrincipalClaimIsRejected() {
        OidcService service = service(issuer, null, "preferred_username");
        String token = token(keyPair, "key-1", Map.of("sub", "uuid-1234"));

        assertTrue(service.verify(token).isEmpty());
    }

    @Test
    void expiredTokenIsRejected() {
        OidcService service = service(issuer, null, "sub");
        String token = token(keyPair, "key-1", Map.of("exp", clock.instant().getEpochSecond() - 3600));

        assertTrue(service.verify(token).isEmpty());
    }

    /**
     * 时钟偏移对 OIDC 令牌是必需的。
     *
     * <p>IdP 与本服务端的时钟不可能完全一致；不给余量会让「刚签发的令牌被判为
     * 尚未生效」或「刚过期几秒的令牌立刻失效」，前者表现为「SSO 登录随机失败」。
     */
    @Test
    void clockSkewIsToleratedForOidcTokens() {
        OidcService service = service(issuer, null, "sub");
        long now = clock.instant().getEpochSecond();
        // 5 秒前过期，在默认 60 秒偏移之内
        String recentlyExpired = token(keyPair, "key-1",
                Map.of("iat", now - 3600, "exp", now - 5));
        assertTrue(service.verify(recentlyExpired).isPresent(), "偏移之内应该接受");

        // 2 分钟前过期，超出偏移
        String longExpired = token(keyPair, "key-1",
                Map.of("iat", now - 7200, "exp", now - 120));
        assertTrue(service.verify(longExpired).isEmpty(), "偏移之外必须拒绝");
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        OidcService service = service(issuer, null, "sub");
        // 这里刻意绕开 token(...) 的默认载荷：那个载荷总是带 exp，
        // 而本用例恰恰要一个没有 exp 的令牌
        String token = signed("RS256", keyPair, "{\"iss\":\"" + issuer + "\",\"sub\":\"alice\"}",
                ",\"kid\":\"key-1\"");

        assertTrue(service.verify(token).isEmpty(), "没有 exp 的令牌意味着永不过期");
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        OidcService service = service(issuer, null, "sub");
        String token = token(keyPair, "key-1", Map.of("iss", OTHER_ISSUER));

        assertTrue(service.verify(token).isEmpty());
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        OidcService service = service(issuer, null, "sub");
        KeyPair attacker = rsaKeyPair();
        String token = token(attacker, "key-1", Map.of("sub", "admin"));

        assertTrue(service.verify(token).isEmpty(), "签名不符的令牌必须拒绝");
    }

    /** 配置了 audience 时必须校验；否则同一 issuer 下给别的应用签的令牌也能用。 */
    @Test
    void audienceIsEnforcedWhenConfigured() {
        OidcService service = service(issuer, "paimon-console", "sub");

        String wrong = token(keyPair, "key-1", Map.of("aud", "other-app"));
        assertTrue(service.verify(wrong).isEmpty());

        String right = token(keyPair, "key-1", Map.of("aud", "paimon-console"));
        assertTrue(service.verify(right).isPresent());
    }

    /** 未配置 audience 时不校验，任何受众都接受（单客户端部署的常见情况）。 */
    @Test
    void audienceIsNotCheckedWhenUnset() {
        OidcService service = service(issuer, null, "sub");
        String token = token(keyPair, "key-1", Map.of("aud", "whatever"));

        assertTrue(service.verify(token).isPresent());
    }

    /**
     * IdP 轮换签名密钥后，新令牌要能被接受。
     *
     * <p>这是最容易出问题的一条：JWKS 有缓存，而新令牌的 kid 不在缓存里。
     * 不给一次立即重取的机会，IdP 换密钥后到缓存过期前的这段时间里，
     * 所有新令牌都会被判为无效——表现为「突然所有人都登不上，过一阵自己好了」。
     */
    @Test
    void jwksIsRefreshedWhenTheSigningKeyRotates() throws Exception {
        OidcService service = service(issuer, null, "sub");
        assertTrue(service.verify(token(keyPair, "key-1", Map.of()))
                .isPresent());
        int requestsAfterFirstVerification = jwksRequests.get();

        // IdP 换了一对密钥
        KeyPair rotated = rsaKeyPair();
        jwks.set(jwks(rotated, "key-2"));

        String tokenWithNewKey = token(rotated, "key-2", Map.of());
        assertTrue(service.verify(tokenWithNewKey).isPresent(),
                "kid 未命中缓存时应当强制刷新 JWKS");
        assertTrue(jwksRequests.get() > requestsAfterFirstVerification,
                "应当真的重新拉取了 JWKS");
    }

    /** 没有 kid 且 JWKS 里有多把钥匙时不猜：拒签比选错钥匙更安全。 */
    @Test
    void ambiguousKeySelectionWithoutKidIsRejected() throws Exception {
        KeyPair other = rsaKeyPair();
        jwks.set("{\"keys\":[" + jwk(keyPair.getPublic(), "key-1") + ","
                + jwk(other.getPublic(), "key-2") + "]}");
        OidcService service = service(issuer, null, "sub");
        String token = tokenWithoutKid(keyPair, Map.of());

        assertTrue(service.verify(token).isEmpty());
    }

    /** JWKS 里只有一把钥匙且令牌无 kid 时，选它是合理的。 */
    @Test
    void singleKeyWithoutKidIsAccepted() {
        OidcService service = service(issuer, null, "sub");
        String token = tokenWithoutKid(keyPair, Map.of());

        assertTrue(service.verify(token).isPresent());
    }

    /**
     * 发现文档里的 issuer 与配置不一致时必须拒绝（防 mix-up）。
     *
     * <p>一个被劫持或配错的 IdP 可以声称自己是别人；不校验这一项，
     * 攻击者只要让某个 issuer 的元数据指向自己的 JWKS，就能签发任意身份的令牌。
     */
    @Test
    void discoveryDocumentWithMismatchedIssuerIsRejected() throws Exception {
        HttpServer impostor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        impostor.createContext("/.well-known/openid-configuration", exchange ->
                respond(exchange, 200, discoveryDocument("https://someone-else.example.com")));
        impostor.start();
        try {
            String impostorIssuer = "http://127.0.0.1:" + impostor.getAddress().getPort();
            OidcService service = service(impostorIssuer, null, "sub");

            assertTrue(service.metadata().isEmpty(),
                    "文档里的 issuer 与配置不符时不该接受这份文档");
        } finally {
            impostor.stop(0);
        }
    }

    /**
     * IdP 短暂不可用时，已经在用控制台的人不受影响。
     *
     * <p>这是缓存「刷新失败沿用旧值」的意义：缓存过期只是「该刷新了」，
     * 不是「之前那份不能用了」。否则 IdP 重启 30 秒会变成全员被登出。
     * 缓存 TTL 在这里刻意设为 1 毫秒，好让「过期 + 拉不到」这个组合确实被触发。
     */
    @Test
    void cachedMetadataAndJwksSurviveAnUnreachableIdp() {
        OidcService service = service(issuer, null, "sub", Duration.ofMillis(1));
        String token = token(keyPair, "key-1", Map.of());
        assertTrue(service.verify(token).isPresent());

        // IdP 下线，且缓存已过期
        server.stop(0);
        clock.advance(Duration.ofMinutes(10));

        assertTrue(service.verify(token).isPresent(),
                "IdP 不可达时应当继续使用上一次取到的元数据与公钥");
        assertTrue(service.metadata().isPresent());
    }

    /** 从没成功拉到过元数据时，验证失败——此时确实无从判断令牌的真伪。 */
    @Test
    void verificationFailsWhenMetadataWasNeverFetched() {
        OidcService service = service("http://127.0.0.1:1", null, "sub");
        String token = token(keyPair, "key-1", Map.of("iss", "http://127.0.0.1:1"));

        assertTrue(service.metadata().isEmpty());
        assertTrue(service.verify(token).isEmpty());
    }

    /** 未启用时什么都不做，也不发任何请求。 */
    @Test
    void disabledOidcRejectsEverythingWithoutTouchingTheNetwork() {
        RestServerProperties properties = new RestServerProperties();
        properties.getAuth().getConsole().getOidc().setEnabled(false);
        properties.getAuth().getConsole().getOidc().setIssuerUri(issuer);
        properties.getAuth().getConsole().getOidc().setClientId("console");
        OidcService service = new OidcService(properties, clock);

        assertFalse(service.enabled());
        assertTrue(service.metadata().isEmpty());
        assertTrue(service.verify(token(keyPair, "key-1", Map.of()))
                .isEmpty());
        assertEquals(0, metadataRequests.get());
        assertEquals(0, jwksRequests.get());
    }

    /** 缺 client-id 也算没配好：发现文档能拉到，但登录页拿不到客户端标识。 */
    @Test
    void enabledWithoutClientIdIsNotUsable() {
        RestServerProperties properties = new RestServerProperties();
        properties.getAuth().getConsole().getOidc().setEnabled(true);
        properties.getAuth().getConsole().getOidc().setIssuerUri(issuer);
        OidcService service = new OidcService(properties, clock);

        assertFalse(service.enabled());
        assertTrue(service.metadata().isEmpty());
    }

    /** {@code alg: none} 是攻击特征，必须被挡住并留下日志。 */
    @Test
    void noneAlgorithmIsRejected() {
        OidcService service = service(issuer, null, "sub");
        String forged = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}")
                + "." + base64Url("{\"sub\":\"admin\",\"iss\":\"" + issuer + "\"}") + ".";

        assertTrue(service.verify(forged).isEmpty());
    }

    /** 乱码输入不该抛异常。 */
    @Test
    void malformedTokensAreRejectedWithoutThrowing() {
        OidcService service = service(issuer, null, "sub");

        // 显式转型为 String：verify 有两个重载，null 会引发歧义
        assertTrue(service.verify((String) null).isEmpty());
        assertTrue(service.verify("").isEmpty());
        assertTrue(service.verify("opaque").isEmpty());
        assertTrue(service.verify("a.b.c").isEmpty());
    }

    // ------------------------------------------------------------------ 工具

    private OidcService service(String issuerUri, String audience, String principalClaim) {
        // 默认给足缓存时长：多数用例关心的是验证逻辑，不希望中途去拉元数据
        return service(issuerUri, audience, principalClaim, Duration.ofHours(1));
    }

    private OidcService service(String issuerUri, String audience, String principalClaim,
                                Duration cacheTtl) {
        RestServerProperties properties = new RestServerProperties();
        RestServerProperties.Auth.Oidc oidc = properties.getAuth().getConsole().getOidc();
        oidc.setEnabled(true);
        oidc.setIssuerUri(issuerUri);
        oidc.setClientId("paimon-console");
        oidc.setRedirectUri("http://localhost:8080/console/auth/callback");
        oidc.setPrincipalClaim(principalClaim);
        oidc.setAudience(audience);
        oidc.setMetadataCacheTtl(cacheTtl);
        oidc.setJwksCacheTtl(cacheTtl);
        return new OidcService(properties, clock);
    }

    private String discoveryDocument(String reportedIssuer) {
        return "{\"issuer\":\"" + reportedIssuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"end_session_endpoint\":\"" + issuer + "/logout\","
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
    }

    /**
     * 生成一个带默认载荷的令牌。
     *
     * <p>默认载荷是「合法 issuer + 一小时前签发、一小时后过期 + 主体 alice」，
     * 各用例只覆盖自己关心的那几项。这样做的理由：{@code exp} 是
     * {@code OidcService} 的必需项，每个用例都手写一遍「完整且合法」的载荷，
     * 漏掉任何一项都会让用例失败在一件与它无关的事情上（本文件的第一版就是这样，
     * 六个用例一起红在「没有 exp」上）。
     *
     * @param overrides 覆盖默认载荷的项
     */
    private String token(KeyPair pair, String keyId, Map<String, Object> overrides) {
        return signed("RS256", pair, claims(overrides), ",\"kid\":\"" + keyId + "\"");
    }

    private String tokenWithoutKid(KeyPair pair, Map<String, Object> overrides) {
        return signed("RS256", pair, claims(overrides), "");
    }

    private String claims(Map<String, Object> overrides) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        claims.put("sub", "alice");
        claims.putAll(overrides);
        return Json.write(claims);
    }

    private String signed(String algorithm, KeyPair pair, String claimsJson, String extraHeader) {
        String header = base64Url("{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\"" + extraHeader + "}");
        String payload = base64Url(claimsJson);
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(pair.getPrivate());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String jwks(KeyPair pair, String keyId) {
        return "{\"keys\":[" + jwk(pair.getPublic(), keyId) + "]}";
    }

    private String jwk(java.security.PublicKey key, String keyId) {
        java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) key;
        return "{\"kty\":\"RSA\",\"kid\":\"" + keyId + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + unsigned(rsa.getModulus()) + "\","
                + "\"e\":\"" + unsigned(rsa.getPublicExponent()) + "\"}";
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
