package io.github.melin.paimonrest.support;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWT 的编解码与验签。
 *
 * <p><b>为什么自己写而不是引依赖。</b>本工程需要的能力只有三件事：
 * 用 HMAC 签一个令牌（控制台自己签发的）、用 RSA / EC 公钥验一个令牌
 * （外部 OIDC 的）、把 base64url 的 JSON 解出来。这三件事在 JDK 里都有
 * （{@code Mac}、{@code Signature}、{@code Base64}），加起来两百来行。
 * 引入 Spring Security 的 resource-server 会连带改变整个 FilterChain 的
 * 默认行为（默认拦下所有请求、自带 CSRF 与登录页），而本工程的认证挂在
 * {@code HandlerInterceptor} 上——混用两套机制意味着两处都要读一遍才知道
 * 一个请求最终会不会被放行。nimbus-jose-jwt 则会把验签与 HTTP 拉取 JWKS
 * 绑在一起，而本工程只想在拉取那一层自己控制缓存与失败行为。
 *
 * <p><b>只实现验签需要的算法。</b>签发只用 HS256（{@link #signHs256}）；
 * 验签覆盖 {@code HS*} / {@code RS*} / {@code ES*}，因为外部 IdP 用哪一组不由我们决定。
 * {@code none} 被显式拒绝——它是 JWT 最常见的误用，把{"alg":"none"}当合法输入
 * 等于让任何人伪造任意令牌。
 *
 * <p><b>本类只做密码学与编码，不做任何策略判断。</b>过期、签发者、受众这些
 * 校验留给调用方（{@code AccessTokenService} / {@code OidcService}），
 * 因为它们对「过期时间该怎么宽容」的答案不同：自签发的令牌容忍度为 0，
 * 外部 IdP 的令牌要留时钟偏移。
 */
public final class Jwt {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** HS256 要求的最小密钥长度（字节）。RFC 7518 第 3.2 节：密钥不短于摘要输出。 */
    public static final int MIN_HMAC_KEY_BYTES = 32;

    private Jwt() {
    }

    /** 一个已解析但**未验证**的令牌。验签前不要读其中任何一个字段。 */
    public record Decoded(Map<String, Object> header, Map<String, Object> claims,
                          String signingInput, byte[] signature) {

        public String algorithm() {
            return Values.string(header.get("alg"));
        }

        public String keyId() {
            return Values.string(header.get("kid"));
        }

        /** 取一个 claim 的字符串形式；缺失时返回 {@code null}。 */
        public String claim(String name) {
            return Values.string(claims.get(name));
        }

        /** 取一个 claim 的数值形式（JWT 的 NumericDate 是秒）；缺失或非数值时返回 {@code null}。 */
        public Long numericClaim(String name) {
            return Values.longValue(claims.get(name));
        }

        /**
         * 取一个 claim 的字符串列表。
         *
         * <p>JWT 规范里单值与多值是允许互相转换的（{@code aud} 尤其常见），
         * 因此两种形状都要接。
         */
        public java.util.List<String> listClaim(String name) {
            Object value = claims.get(name);
            if (value instanceof java.util.List<?> list) {
                return Values.stringList(list);
            }
            String single = Values.string(value);
            return single == null ? java.util.List.of() : java.util.List.of(single);
        }
    }

    /**
     * 用 HMAC-SHA256 签发一个令牌。
     *
     * @param claims 载荷。{@code iat} / {@code exp} 等时间声明由调用方填好——
     *               本方法不读时钟，以免同一个令牌在测试里出现两个签发时间
     * @param key    签名密钥，至少 {@value #MIN_HMAC_KEY_BYTES} 字节
     */
    public static String signHs256(Map<String, Object> claims, byte[] key) {
        requireKeyLength(key);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        String signingInput = encode(header) + "." + encode(claims);
        return signingInput + "." + ENCODER.encodeToString(hmac(signingInput, key));
    }

    /**
     * 用共享密钥验签，并用常量时间比较签名。
     *
     * <p>比较用 {@link java.security.MessageDigest#isEqual}：
     * {@code Arrays.equals} 会在第一个不同的字节处返回，理论上可被用来逐字节试探签名。
     */
    public static boolean verifyHmac(Decoded decoded, byte[] key) {
        if (!"HS256".equals(decoded.algorithm())) {
            return false;
        }
        if (key == null || key.length < MIN_HMAC_KEY_BYTES) {
            return false;
        }
        byte[] expected = hmac(decoded.signingInput(), key);
        return java.security.MessageDigest.isEqual(expected, decoded.signature());
    }

    /**
     * 用公钥验签。
     *
     * @param publicKey 与令牌头部 {@code alg} 相匹配的公钥；算法不支持时返回 false
     */
    public static boolean verifyWithPublicKey(Decoded decoded, PublicKey publicKey) {
        if (publicKey == null) {
            return false;
        }
        String algorithm = decoded.algorithm();
        String jcaName = jcaSignatureName(algorithm);
        if (jcaName == null) {
            return false;
        }
        try {
            byte[] signatureBytes = signatureBytesForJca(decoded, algorithm);
            if (signatureBytes == null) {
                return false;
            }
            Signature verifier = Signature.getInstance(jcaName);
            verifier.initVerify(publicKey);
            verifier.update(decoded.signingInput().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            // 验签失败的原因很多（密钥类型不符、签名格式错、provider 不支持），
            // 对调用方而言都是「这个令牌不可信」，不需要区分
            return false;
        }
    }

    /**
     * 解析令牌，不验签。
     *
     * <p>切分用 {@code split("\\.", -1)} 保留空的尾段：{@code alg:none} 的伪造令牌
     * 形如 {@code header.payload.}——签名段是空的。用默认的 {@code split("\\.")}
     * 会把尾部的空串丢掉，于是三段变成两段，报出「结构不对」。
     * 那确实也拒绝了它，但错在理由上：排查的人会去查编码问题，
     * 而真正的原因是「这个令牌声称自己不需要签名」。保留空段后，
     * 它会被正常解析并在验签那一步被拒，理由才对得上。
     *
     * @throws IllegalArgumentException 结构不合法（段数不对、base64 解不开、载荷不是 JSON object）
     */
    public static Decoded decode(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is empty");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("token must have three dot-separated parts");
        }
        Map<String, Object> header = decodePart(parts[0], "header");
        Map<String, Object> claims = decodePart(parts[1], "payload");
        byte[] signature;
        try {
            signature = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("signature is not valid base64url");
        }
        return new Decoded(header, claims, parts[0] + "." + parts[1], signature);
    }

    /** 令牌是否使用了不被接受的算法。用于把「算法不支持」与「签名不对」分开报日志。 */
    public static boolean isSupportedAlgorithm(String algorithm) {
        return "none".equalsIgnoreCase(algorithm) ? false : jcaSignatureName(algorithm) != null;
    }

    // ------------------------------------------------------------------ 内部

    private static byte[] hmac(String signingInput, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String encode(Map<String, Object> value) {
        return ENCODER.encodeToString(Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodePart(String part, String name) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(part);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("token " + name + " is not valid base64url");
        }
        Object parsed;
        try {
            parsed = Json.read(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("token " + name + " is not valid JSON");
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("token " + name + " is not a JSON object");
        }
        return (Map<String, Object>) parsed;
    }

    private static String jcaSignatureName(String algorithm) {
        return switch (algorithm == null ? "" : algorithm) {
            case "HS256" -> "HmacSHA256";
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            case "ES256" -> "SHA256withECDSA";
            case "ES384" -> "SHA384withECDSA";
            case "ES512" -> "SHA512withECDSA";
            default -> null;
        };
    }

    /**
     * 把 JWT 的签名格式转成 JCA 期望的格式。
     *
     * <p>ECDSA 是唯一的差异：JWT 用的是「R 与 S 各自定长拼接」的原始格式
     * （RFC 7518 第 3.4 节），而 {@code SHA256withECDSA} 期望 DER 编码的
     * {@code SEQUENCE { INTEGER r, INTEGER s }}。不做这一步转换，
     * 所有 ES256 令牌都会验签失败，而错误信息只会说「签名不匹配」——
     * 看起来像密钥不对，实际是格式不对。
     *
     * @return JCA 可直接使用的签名字节；算法不支持时返回 {@code null}
     */
    private static byte[] signatureBytesForJca(Decoded decoded, String algorithm) {
        Integer partLength = switch (algorithm == null ? "" : algorithm) {
            case "ES256" -> 32;
            case "ES384" -> 48;
            case "ES512" -> 66;
            default -> null;
        };
        if (partLength == null) {
            return decoded.signature();
        }
        byte[] raw = decoded.signature();
        if (raw.length != partLength * 2) {
            // 长度不对说明令牌本身有问题，交给调用方当验签失败处理
            return null;
        }
        byte[] r = java.util.Arrays.copyOfRange(raw, 0, partLength);
        byte[] s = java.util.Arrays.copyOfRange(raw, partLength, partLength * 2);
        return derEncodeEcdsaSignature(r, s);
    }

    private static byte[] derEncodeEcdsaSignature(byte[] r, byte[] s) {
        byte[] rDer = derInteger(r);
        byte[] sDer = derInteger(s);
        int contentLength = rDer.length + sDer.length;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x30);
        writeDerLength(out, contentLength);
        out.writeBytes(rDer);
        out.writeBytes(sDer);
        return out.toByteArray();
    }

    /** DER 的 INTEGER 是「有符号大端、最短编码」，因此最高位为 1 时要补一个 0x00。 */
    private static byte[] derInteger(byte[] value) {
        BigInteger integer = new BigInteger(1, value);
        byte[] magnitude = integer.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x02);
        writeDerLength(out, magnitude.length);
        out.writeBytes(magnitude);
        return out.toByteArray();
    }

    private static void writeDerLength(java.io.ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        // 长格式：先写字节数（最高位置 1），再写长度本身
        int bytes = 0;
        for (int remaining = length; remaining > 0; remaining >>= 8) {
            bytes++;
        }
        out.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            out.write((length >> shift) & 0xFF);
        }
    }

    private static void requireKeyLength(byte[] key) {
        if (key == null || key.length < MIN_HMAC_KEY_BYTES) {
            throw new IllegalArgumentException("HMAC signing key must be at least "
                    + MIN_HMAC_KEY_BYTES + " bytes");
        }
    }
}
