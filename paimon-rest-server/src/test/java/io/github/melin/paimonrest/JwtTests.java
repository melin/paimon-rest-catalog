package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.support.Jwt;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Jwt} 的单元测试。
 *
 * <p>这一层的价值在于：令牌验签错了，症状永远是「401」，而 401 可能来自
 * 密钥不对、算法不对、编码不对、时间不对中的任何一个。把这些可能性在单元层
 * 逐一钉死，线上再遇到 401 就只剩配置问题。
 *
 * <p>尤其值得测的是 <b>ECDSA 的签名格式转换</b>：JWT 用「R ‖ S 定长拼接」，
 * JCA 用 DER。直接跳过转换的话，所有 ES256 令牌都会验签失败，
 * 而错误信息只说「签名不匹配」——看起来像密钥不对，实际是格式不对。
 */
class JwtTests {

    private static final byte[] HMAC_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    // ------------------------------------------------------------------ HS256

    @Test
    void hs256RoundTripPreservesClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "alice");
        claims.put("iss", "paimon-rest");
        claims.put("exp", 1234567890L);
        claims.put("scope", "PRINCIPAL_ROLE:ALL");

        Jwt.Decoded decoded = Jwt.decode(Jwt.signHs256(claims, HMAC_KEY));

        assertEquals("HS256", decoded.algorithm());
        assertEquals("JWT", decoded.header().get("typ"));
        assertEquals("alice", decoded.claim("sub"));
        assertEquals("paimon-rest", decoded.claim("iss"));
        assertEquals(1234567890L, decoded.numericClaim("exp"));
        assertEquals("PRINCIPAL_ROLE:ALL", decoded.claim("scope"));
        assertTrue(Jwt.verifyHmac(decoded, HMAC_KEY));
    }

    @Test
    void hmacSignatureRejectsAnotherKey() {
        String token = Jwt.signHs256(Map.of("sub", "alice"), HMAC_KEY);
        byte[] otherKey = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);

        assertFalse(Jwt.verifyHmac(Jwt.decode(token), otherKey),
                "换一把密钥必须验不过——否则签名等于没签");
    }

    /**
     * 篡改载荷后签名必须失效。
     *
     * <p>这是签名唯一的作用：能解出「alice」不代表令牌是 alice 的，
     * 只能代表有人写了 alice。改一个字符就改一次 base64，
     * 因此连重新编码都不必。
     */
    @Test
    void hmacSignatureRejectsTamperedPayload() {
        String token = Jwt.signHs256(Map.of("sub", "alice"), HMAC_KEY);
        String[] parts = token.split("\\.");
        String forgedPayload = ENCODER.encodeToString("{\"sub\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertFalse(Jwt.verifyHmac(Jwt.decode(forged), HMAC_KEY));
        assertEquals("admin", Jwt.decode(forged).claim("sub"),
                "前提：载荷确实被改成了 admin，测试才有意义");
    }

    /** 密钥短于摘要长度时必须拒绝签发：短密钥的 HMAC 可被暴力还原。 */
    @Test
    void signingRejectsShortKey() {
        byte[] shortKey = "too-short".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> Jwt.signHs256(Map.of("sub", "alice"), shortKey));
    }

    // ------------------------------------------------------------------ 算法

    /**
     * {@code alg: none} 必须被拒绝。
     *
     * <p>这是 JWT 最经典的误用：把 {"alg":"none"} 当合法输入，等于允许任何人
     * 伪造任意令牌——签名段留空即可。
     */
    @Test
    void noneAlgorithmIsNotSupported() {
        String forged = ENCODER.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString("{\"sub\":\"admin\"}".getBytes(StandardCharsets.UTF_8))
                + ".";

        assertFalse(Jwt.isSupportedAlgorithm("none"));
        assertFalse(Jwt.verifyHmac(Jwt.decode(forged), HMAC_KEY));
    }

    @Test
    void unknownAlgorithmIsNotSupported() {
        assertFalse(Jwt.isSupportedAlgorithm("HS512"));
        assertFalse(Jwt.isSupportedAlgorithm(null));
        assertTrue(Jwt.isSupportedAlgorithm("RS256"));
        assertTrue(Jwt.isSupportedAlgorithm("ES256"));
    }

    // ------------------------------------------------------------------ RS256

    @Test
    void rs256TokenVerifiesWithItsPublicKey() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String token = sign("RS256", "SHA256withRSA", keyPair.getPrivate(),
                "{\"sub\":\"alice\",\"iss\":\"https://idp.example.com\"}", "key-1");

        Jwt.Decoded decoded = Jwt.decode(token);
        assertEquals("key-1", decoded.keyId());
        assertEquals("alice", decoded.claim("sub"));
        assertTrue(Jwt.verifyWithPublicKey(decoded, keyPair.getPublic()));
    }

    @Test
    void rs256TokenRejectsAnotherKeyPair() throws Exception {
        String token = sign("RS256", "SHA256withRSA", rsaKeyPair().getPrivate(), "{\"sub\":\"alice\"}", "key-1");

        assertFalse(Jwt.verifyWithPublicKey(Jwt.decode(token), rsaKeyPair().getPublic()));
    }

    // ------------------------------------------------------------------ ES256

    /**
     * ES256 的签名在 JWT 里是「R 与 S 各自 32 字节拼接」，不是 DER。
     *
     * <p>这里按规范生成原始格式，验证 {@link Jwt} 会把它转成 DER 再交给 JCA。
     * 少了转换这一步，本用例会失败——而线上表现为「Keycloak 签的令牌一律验不过」。
     */
    @Test
    void es256RawSignatureIsConvertedToDer() throws Exception {
        KeyPair keyPair = ecKeyPair();
        String token = signRawEcdsa("ES256", "SHA256withECDSA", keyPair.getPrivate(),
                "{\"sub\":\"alice\"}", "ec-key", 32);

        Jwt.Decoded decoded = Jwt.decode(token);
        assertEquals(64, decoded.signature().length, "ES256 的原始签名应当是 64 字节（R 32 + S 32）");
        assertTrue(Jwt.verifyWithPublicKey(decoded, keyPair.getPublic()));
    }

    @Test
    void es256TokenRejectsAnotherKeyPair() throws Exception {
        String token = signRawEcdsa("ES256", "SHA256withECDSA", ecKeyPair().getPrivate(),
                "{\"sub\":\"alice\"}", "ec-key", 32);

        assertFalse(Jwt.verifyWithPublicKey(Jwt.decode(token), ecKeyPair().getPublic()));
    }

    /** 签名长度与算法不匹配（例如把 ES512 的签名标成 ES256）时必须拒绝。 */
    @Test
    void es256RejectsSignatureOfTheWrongLength() throws Exception {
        KeyPair keyPair = ecKeyPair();
        byte[] wrongLength = new byte[70];
        String header = ENCODER.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = ENCODER.encodeToString("{\"sub\":\"alice\"}".getBytes(StandardCharsets.UTF_8));
        String token = header + "." + payload + "." + ENCODER.encodeToString(wrongLength);

        assertFalse(Jwt.verifyWithPublicKey(Jwt.decode(token), keyPair.getPublic()));
    }

    // ------------------------------------------------------------------ 结构

    @Test
    void decodeRejectsMalformedTokens() {
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode("not-a-jwt"));
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode("a.b"));
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode("!!!.???.###"));
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode("a.b.c"),
                "载荷不是 JSON 时应当拒绝");
    }

    /** 阈值：一个纯字母数字的静态令牌不该被当成 JWT 解析成功。 */
    @Test
    void decodeRejectsOpaqueTokens() {
        assertThrows(IllegalArgumentException.class, () -> Jwt.decode("3f8a1c9e7b2d4f6a"));
    }

    /**
     * {@code aud} 允许是单值或数组。
     *
     * <p>两种形状都出现在真实 IdP 里（RFC 7519 允许），只接一种会让部分部署
     * 的受众校验静默失效。
     */
    @Test
    void audienceAcceptsBothSingleValueAndArray() {
        Jwt.Decoded single = Jwt.decode(Jwt.signHs256(Map.of("aud", "paimon"), HMAC_KEY));
        assertEquals(java.util.List.of("paimon"), single.listClaim("aud"));

        Jwt.Decoded multiple = Jwt.decode(
                Jwt.signHs256(Map.of("aud", java.util.List.of("a", "paimon")), HMAC_KEY));
        assertEquals(java.util.List.of("a", "paimon"), multiple.listClaim("aud"));

        Jwt.Decoded missing = Jwt.decode(Jwt.signHs256(Map.of("sub", "alice"), HMAC_KEY));
        assertEquals(java.util.List.of(), missing.listClaim("aud"));
    }

    // ------------------------------------------------------------------ 工具

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** 签一个签名格式与 JCA 一致的令牌（HS / RS 系列）。 */
    private static String sign(String algorithm, String jcaName, java.security.PrivateKey key,
                               String claimsJson, String keyId) throws Exception {
        String signingInput = header(algorithm, keyId) + "."
                + ENCODER.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance(jcaName);
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + ENCODER.encodeToString(signer.sign());
    }

    /** 签一个 ECDSA 令牌，签名按 JWT 规范转成「R ‖ S」的原始格式。 */
    private static String signRawEcdsa(String algorithm, String jcaName, java.security.PrivateKey key,
                                       String claimsJson, String keyId, int partLength) throws Exception {
        String signingInput = header(algorithm, keyId) + "."
                + ENCODER.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance(jcaName);
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] der = signer.sign();
        return signingInput + "." + ENCODER.encodeToString(derToRawEcdsa(der, partLength));
    }

    private static String header(String algorithm, String keyId) {
        return ENCODER.encodeToString(
                ("{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\",\"kid\":\"" + keyId + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** DER {@code SEQUENCE { INTEGER r, INTEGER s }} → 等长的「R ‖ S」。 */
    private static byte[] derToRawEcdsa(byte[] der, int partLength) {
        int offset = 1;
        // 跳过 SEQUENCE 的长度字节（长格式时多字节）
        if ((der[offset] & 0x80) != 0) {
            offset += 1 + (der[offset] & 0x7F);
        } else {
            offset += 1;
        }
        byte[] result = new byte[partLength * 2];
        for (int part = 0; part < 2; part++) {
            assertTrue(der[offset] == 0x02, "期望 DER INTEGER 标记");
            offset++;
            int length = der[offset] & 0xFF;
            offset++;
            // INTEGER 可能比 partLength 短（前导零被 DER 的最短编码去掉了），
            // 也可能长一位（最高位为 1 时 DER 补了 0x00），两种都要对齐到定长
            int start = offset + Math.max(0, length - partLength);
            int copied = Math.min(length, partLength);
            System.arraycopy(der, start, result, part * partLength + (partLength - copied), copied);
            offset += length;
        }
        return result;
    }
}
