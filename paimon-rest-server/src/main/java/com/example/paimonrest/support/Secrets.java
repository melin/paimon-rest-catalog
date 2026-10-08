package com.example.paimonrest.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 主体凭据的生成与摘要。
 *
 * <p>管理规格要求在创建、轮换、重置主体时返回 {@code PrincipalWithCredentials}，
 * 其中 {@code credentials.clientSecret} 是明文密钥。明文只在这一次响应当中出现：
 * 库中只保存 {@code SHA-256(salt || secret)}，之后无法还原，
 * 这与 Polaris 的凭据语义一致。
 */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成的客户端密钥字节数，编码后长度约为 43 个字符。 */
    private static final int SECRET_BYTES = 32;

    private static final int SALT_BYTES = 16;

    private Secrets() {
    }

    /** 生成新的客户端密钥明文。 */
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 生成新的 clientId。 */
    public static String newClientId() {
        return Paging.newId();
    }

    /** 生成盐值（十六进制）。 */
    public static String newSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 计算密钥摘要。
     *
     * <p>沿用「盐值在前、密钥在后」的拼接顺序，与既有库中的数据保持兼容。
     *
     * @param salt   十六进制盐值
     * @param secret 明文密钥
     * @return 十六进制摘要
     */
    public static String hash(String salt, String secret) {
        return Digests.sha256Hex(salt, secret);
    }

    /** 常量时间比较，避免摘要比对泄漏长度以外的信息。 */
    public static boolean matches(String expectedHash, String salt, String candidateSecret) {
        if (expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                hash(salt, candidateSecret).getBytes(StandardCharsets.UTF_8));
    }
}
