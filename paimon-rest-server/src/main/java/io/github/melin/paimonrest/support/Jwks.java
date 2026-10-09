package io.github.melin.paimonrest.support;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JWKS（JSON Web Key Set）到 {@link PublicKey} 的解析。
 *
 * <p>验签外部 IdP 签发的令牌需要它的公钥。JWKS 里公钥的表示与 JDK 的
 * {@code RSAPublicKeySpec} / {@code ECPublicKeySpec} 之间差了「base64url 大端字节」
 * 这一层转换，本类就做这一件事。
 *
 * <p><b>只解析公钥。</b>JWKS 里可能混有私钥（{@code d} 等字段），本类忽略它们——
 * 从一个只该含公钥的文档里读到私钥材料本身就该被当成配置错误，
 * 而不是顺手用上。
 *
 * <p><b>不认识的键直接跳过而不是整份失败。</b>一个 IdP 的 JWKS 里可能同时有
 * RS256 与 ES256 的公钥，甚至包含本服务端不支持的类型（例如 {@code oct} 对称密钥）。
 * 因为其中一条读不懂就让整份 JWKS 不可用，会把「IdP 换了个我们已经支持的算法」
 * 变成一次登录故障。
 */
public final class Jwks {

    private Jwks() {
    }

    /**
     * 解析 JWKS 文档。
     *
     * @param json 形如 {@code {"keys":[{...}]}} 的文档
     * @return kid → 公钥。kid 缺失的键归到一个空串键下，与 JWT 头部无 kid 的情况对应
     * @throws IllegalArgumentException 文档结构不合法（没有 keys 数组）
     */
    public static Map<String, PublicKey> parse(String json) {
        Object parsed;
        try {
            parsed = Json.read(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JWKS is not valid JSON");
        }
        Map<String, Object> root = Values.map(parsed);
        if (!(root.get("keys") instanceof List<?> keys)) {
            throw new IllegalArgumentException("JWKS has no \"keys\" array");
        }

        Map<String, PublicKey> result = new LinkedHashMap<>();
        for (Object item : keys) {
            Map<String, Object> jwk = Values.map(item);
            PublicKey key = toPublicKey(jwk);
            if (key == null) {
                continue;
            }
            result.putIfAbsent(Values.string(jwk.get("kid"), ""), key);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("JWKS contains no usable public key");
        }
        return result;
    }

    /** 单个 JWK → 公钥；类型不认识或材料不全时返回 {@code null}。 */
    private static PublicKey toPublicKey(Map<String, Object> jwk) {
        String keyType = Values.string(jwk.get("kty"));
        if (keyType == null) {
            return null;
        }
        try {
            return switch (keyType) {
                case "RSA" -> rsaKey(jwk);
                case "EC" -> ecKey(jwk);
                default -> null;
            };
        } catch (Exception e) {
            // 单条密钥解析失败不影响其余条目
            return null;
        }
    }

    private static PublicKey rsaKey(Map<String, Object> jwk) throws Exception {
        BigInteger modulus = decodeInteger(jwk.get("n"));
        BigInteger exponent = decodeInteger(jwk.get("e"));
        if (modulus == null || exponent == null) {
            return null;
        }
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private static PublicKey ecKey(Map<String, Object> jwk) throws Exception {
        BigInteger x = decodeInteger(jwk.get("x"));
        BigInteger y = decodeInteger(jwk.get("y"));
        String curve = Values.string(jwk.get("crv"));
        if (x == null || y == null || curve == null) {
            return null;
        }
        String curveName = switch (curve) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> null;
        };
        if (curveName == null) {
            return null;
        }
        // JDK 不能按名字直接取曲线参数，要先借 AlgorithmParameters 解析一次，
        // 再取出 ECParameterSpec——这是构造 ECPublicKeySpec 的唯一途径
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curveName));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    /** base64url 大端的无符号整数。JWK 的每个数值字段都是这个形状。 */
    private static BigInteger decodeInteger(Object value) {
        String encoded = Values.string(value);
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(encoded));
    }
}
