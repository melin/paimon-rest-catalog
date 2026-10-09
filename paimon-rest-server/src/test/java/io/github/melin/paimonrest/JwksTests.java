package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.support.Jwks;
import io.github.melin.paimonrest.support.Jwt;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Jwks} 的单元测试。
 *
 * <p>JWKS 解析的每一处都容易「看起来对」：n / e / x / y 是 base64url 的无符号大端
 * 整数，而 JDK 的 {@code BigInteger(byte[])} 是<b>有符号</b>的——少一个
 * {@code new BigInteger(1, bytes)} 就会在最高位为 1 时得到负数，
 * 生成的公钥无法验签，而错误信息只会说「签名不匹配」。
 *
 * <p>另一个容易漏的点是「跳过不认识的键」：一个 IdP 的 JWKS 里可能同时有
 * RSA 与 EC 的公钥、甚至混有对称密钥。因为一条读不懂就让整份 JWKS 失败，
 * 会把一次成功的算法新增变成一次登录故障。
 */
class JwksTests {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Test
    void rsaJwksProducesAUsablePublicKey() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String jwks = jwks(rsaJwk(keyPair.getPublic(), "key-1"));

        Map<String, PublicKey> keys = Jwks.parse(jwks);

        assertEquals(1, keys.size());
        assertTrue(keys.containsKey("key-1"));
        // 能验签才是真的可用：只断言「解析出了非空对象」会漏掉 n / e 取反、
        // 字节顺序错这类问题
        assertTrue(Jwt.verifyWithPublicKey(
                Jwt.decode(rs256Token(keyPair, "{\"sub\":\"alice\"}", "key-1")), keys.get("key-1")));
    }

    @Test
    void ecJwksProducesAUsablePublicKey() throws Exception {
        KeyPair keyPair = ecKeyPair();
        String jwks = jwks(ecJwk(keyPair.getPublic(), "ec-1"));

        Map<String, PublicKey> keys = Jwks.parse(jwks);

        assertTrue(keys.containsKey("ec-1"));
        assertTrue(Jwt.verifyWithPublicKey(
                Jwt.decode(es256Token(keyPair, "{\"sub\":\"alice\"}", "ec-1")), keys.get("ec-1")));
    }

    /** RSA 与 EC 混在一份 JWKS 里时，两种都要能解析出来。 */
    @Test
    void mixedKeyTypesAreAllParsed() throws Exception {
        KeyPair rsa = rsaKeyPair();
        KeyPair ec = ecKeyPair();
        String jwks = jwks(rsaJwk(rsa.getPublic(), "rsa-1"), ecJwk(ec.getPublic(), "ec-1"), rsaJwk(rsa.getPublic(), "rsa-2"));

        Map<String, PublicKey> keys = Jwks.parse(jwks);

        assertEquals(3, keys.size());
        assertTrue(keys.get("rsa-1") instanceof RSAPublicKey);
        assertTrue(keys.get("ec-1") instanceof ECPublicKey);
    }

    /**
     * 不认识或不完整的键被跳过，不影响其余键。
     *
     * <p>四条坏数据分别对应四类真实情况：对称密钥（{@code oct}）、
     * 缺公钥字段的残缺条目、曲线不支持（{@code secp256k1}）、非 JSON 对象。
     */
    @Test
    void unusableKeysAreSkippedWithoutFailingTheWholeDocument() throws Exception {
        KeyPair rsa = rsaKeyPair();
        String jwks = "{\"keys\":["
                + "{\"kty\":\"oct\",\"kid\":\"symmetric\",\"k\":\"c2VjcmV0\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"incomplete\",\"n\":\"AQAB\"},"
                + "{\"kty\":\"EC\",\"kid\":\"odd-curve\",\"crv\":\"secp256k1\",\"x\":\"AA\",\"y\":\"AA\"},"
                + "\"not-an-object\","
                + rsaJwk(rsa.getPublic(), "good")
                + "]}";

        Map<String, PublicKey> keys = Jwks.parse(jwks);

        assertEquals(1, keys.size(), "只有 good 这一条可用");
        assertTrue(keys.containsKey("good"));
    }

    /**
     * 私钥材料被忽略。
     *
     * <p>JWKS 里出现 {@code d}（RSA 私钥指数）说明配置出了问题——本类不做任何
     * 补救，既不用它也不报错，只当它不存在。用它会让一个本该被发现的
     * 配置泄漏悄悄可用。
     */
    @Test
    void privateKeyMaterialIsIgnored() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String jwk = rsaJwk(keyPair.getPublic(), "key-1")
                .replace("}", ",\"d\":\"AQAB\",\"p\":\"AQAB\"}");

        Map<String, PublicKey> keys = Jwks.parse(jwks(jwk));

        assertEquals(1, keys.size());
        assertTrue(Jwt.verifyWithPublicKey(
                Jwt.decode(rs256Token(keyPair, "{\"sub\":\"alice\"}", "key-1")), keys.get("key-1")));
    }

    /** 没有 kid 的键归到空串下，与「JWT 头部无 kid」的取值对应。 */
    @Test
    void keyWithoutKidIsKeptUnderAnEmptyKey() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String jwk = rsaJwk(keyPair.getPublic(), null);

        Map<String, PublicKey> keys = Jwks.parse(jwks(jwk));

        assertTrue(keys.containsKey(""));
    }

    /** 同名 kid 重复出现时保留先到的那条，而不是抛异常。 */
    @Test
    void duplicateKidsKeepTheFirstOne() throws Exception {
        KeyPair first = rsaKeyPair();
        KeyPair second = rsaKeyPair();
        Map<String, PublicKey> keys = Jwks.parse(jwks(
                rsaJwk(first.getPublic(), "dup"), rsaJwk(second.getPublic(), "dup")));

        assertEquals(1, keys.size());
        assertEquals(first.getPublic(), keys.get("dup"));
    }

    @Test
    void malformedDocumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Jwks.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> Jwks.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> Jwks.parse("{\"keys\":[]}"),
                "一条可用键都没有时应当报错，而不是返回空表让人以为验签失败是密钥问题");
        assertThrows(IllegalArgumentException.class, () -> Jwks.parse("{\"keys\":[{\"kty\":\"oct\"}]}"));
    }

    // ------------------------------------------------------------------ 工具

    /** 把若干 JWK 片段拼成一份 JWKS 文档。 */
    private static String jwks(String... jwkFragments) {
        return "{\"keys\":[" + String.join(",", jwkFragments) + "]}";
    }

    private static String rsaJwk(PublicKey key, String kid) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        return (kid == null ? "{" : "{\"kid\":\"" + kid + "\",")
                + "\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\","
                + "\"n\":\"" + unsigned(rsa.getModulus()) + "\","
                + "\"e\":\"" + unsigned(rsa.getPublicExponent()) + "\"}";
    }

    private static String ecJwk(PublicKey key, String kid) throws Exception {
        ECPublicKey ec = (ECPublicKey) key;
        int fieldSize = (ec.getParams().getCurve().getField().getFieldSize() + 7) / 8;
        return (kid == null ? "{" : "{\"kid\":\"" + kid + "\",")
                + "\"kty\":\"EC\",\"alg\":\"ES256\",\"use\":\"sig\",\"crv\":\"P-256\","
                + "\"x\":\"" + ENCODER.encodeToString(fixedLength(ec.getW().getAffineX(), fieldSize)) + "\","
                + "\"y\":\"" + ENCODER.encodeToString(fixedLength(ec.getW().getAffineY(), fieldSize)) + "\"}";
    }

    /**
     * 无符号大端字节。
     *
     * <p>{@code BigInteger.toByteArray()} 在最高位为 1 时会补一个前导 {@code 0x00}
     * 表示正数，而 JWK 的编码里不带它。少了这一步，解析方（以及真实的 IdP 客户端）
     * 会把前导零算成数值的一部分吗？——不会，但多出来的一个字节会让 base64 串
     * 与「官方生成的」不一样，跨实现的比对会失败。
     */
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return ENCODER.encodeToString(bytes);
    }

    private static byte[] fixedLength(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        byte[] result = new byte[length];
        System.arraycopy(bytes, 0, result, length - bytes.length, bytes.length);
        return result;
    }

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

    private static String rs256Token(KeyPair keyPair, String claimsJson, String keyId) throws Exception {
        return signed("RS256", "SHA256withRSA", keyPair, claimsJson, keyId,
                signature -> signature);
    }

    private static String es256Token(KeyPair keyPair, String claimsJson, String keyId) throws Exception {
        return signed("ES256", "SHA256withECDSA", keyPair, claimsJson, keyId,
                der -> derToRawEcdsa(der, 32));
    }

    /** 签名结果的转换交给调用方：RS* 直接可用，ES* 需要 DER → 「R ‖ S」。 */
    private static String signed(String algorithm, String jcaName, KeyPair keyPair, String claimsJson,
                                 String keyId, java.util.function.UnaryOperator<byte[]> transform)
            throws Exception {
        String header = ENCODER.encodeToString(
                ("{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\",\"kid\":\"" + keyId + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "."
                + ENCODER.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance(jcaName);
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + ENCODER.encodeToString(transform.apply(signer.sign()));
    }

    private static byte[] derToRawEcdsa(byte[] der, int partLength) {
        int offset = 1;
        offset += (der[offset] & 0x80) != 0 ? 1 + (der[offset] & 0x7F) : 1;
        byte[] result = new byte[partLength * 2];
        for (int part = 0; part < 2; part++) {
            assertTrue(der[offset] == 0x02);
            offset++;
            int length = der[offset] & 0xFF;
            offset++;
            int start = offset + Math.max(0, length - partLength);
            int copied = Math.min(length, partLength);
            System.arraycopy(der, start, result, part * partLength + (partLength - copied), copied);
            offset += length;
        }
        return result;
    }
}
