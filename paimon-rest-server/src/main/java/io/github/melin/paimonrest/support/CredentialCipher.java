package io.github.melin.paimonrest.support;

import io.github.melin.paimonrest.config.RestServerProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * catalog 存储配置里静态凭据的落库加解密。
 *
 * <p><b>为什么需要这一层。</b>对象存储的长期密钥与主体凭据不同：主体的
 * {@code clientSecret} 只需要「验证」，因此库里存的是摘要（见 {@link Secrets}）；
 * 而静态凭据要**原样下发给引擎**，服务端必须能还原明文。既然可还原，
 * 就不能让它以明文躺在 {@code paimon_catalog.storage_config_json} 里——
 * 一次数据库备份外泄就等价于对象存储被拿走。
 *
 * <p><b>密钥来源与失败方向。</b>密钥来自 {@code paimon.rest.storage.credential-secret-key}
 * （base64 的 32 字节，可用 {@code openssl rand -base64 32} 生成）。
 * 未配置时 {@link #available()} 为 false，写路径拒绝保存静态凭据（{@link #seal} 抛 400），
 * 而不是「先明文存下来、以后再说」——那等于给一个安全承诺开了一道后门。
 * 服务端其余功能不受影响：没有静态凭据的 catalog 一个字节都不经过这里。
 *
 * <p><b>取值非法时启动即失败。</b>配置了一个解不开的密钥属于运维错误，
 * 让它带着「加密功能形同关闭」的状态跑起来，现象会是「保存时一直报错」，
 * 且错误现场离配置现场很远。与 {@link FileIoType#require} 同一个取舍。
 *
 * <p><b>密文自带判别前缀。</b>落库的形态是 {@code v1:<base64(iv || 密文+认证标签)>}。
 * 前缀让「这串是密文还是历史遗留的明文」可判别——本特性上线前写入的 catalog 里
 * 该字段必然是空的，但一份从别处导入的 JSON 未必；把明文当密文解会得到
 * 一串无意义的字节，把密文当明文下发会把一段 base64 发给引擎并在那里认证失败。
 * 因此 {@link #reveal} 明确拒绝没有前缀的值，宁可报错也不猜。
 *
 * <p><b>GCM 的参数由实现固定，不放进配置。</b>IV 长度 12 字节、认证标签 128 位是
 * GCM 的推荐取值；把它们做成配置项只会多出「配错了才炸」的组合，
 * 而版本前缀已经承担了将来换算法/换参数的演化职责。
 */
@Component
public class CredentialCipher {

    /** 密文前缀，兼作「这串是密封值而不是明文」的判别依据。 */
    public static final String SEALED_PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String ALGORITHM = "AES";

    /** AES-256。取值与下面 {@link #parseKey} 的校验必须一致。 */
    private static final int KEY_BYTES = 32;

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    /** 生成密钥的命令，写进错误信息里，省得运维去翻文档。 */
    private static final String GENERATE_HINT = "openssl rand -base64 32";

    private final SecureRandom random = new SecureRandom();

    /** 为 null 表示未配置密钥，静态凭据功能关闭。 */
    private final SecretKeySpec key;

    public CredentialCipher(RestServerProperties properties) {
        this.key = parseKey(properties.getStorage().getCredentialSecretKey());
    }

    /** 本部署能否保存静态凭据。控制台与写路径都按它决定行为。 */
    public boolean available() {
        return key != null;
    }

    /** 该值是否是本类产生的密文。 */
    public static boolean sealed(String value) {
        return value != null && value.startsWith(SEALED_PREFIX);
    }

    /**
     * 加密一个明文。
     *
     * @throws ApiException 400 未配置加密密钥（写路径拒绝保存，而不是明文落库）
     */
    public String seal(String plaintext) {
        if (key == null) {
            throw ApiException.badRequest("this deployment cannot store static storage credentials: "
                    + "paimon.rest.storage.credential-secret-key is not configured"
                    + " (generate one with `" + GENERATE_HINT + "`)");
        }
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
            return SEALED_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException failure) {
            // 密钥长度与算法在本类的构造期已经固定，走到这里说明运行环境缺算法实现
            throw new IllegalStateException("failed to encrypt a storage credential", failure);
        }
    }

    /**
     * 还原一个密文。
     *
     * @throws ApiException 500 密文形态非法、或与当前密钥不匹配
     */
    public String reveal(String sealed) {
        if (sealed == null) {
            return null;
        }
        if (!sealed(sealed)) {
            throw new ApiException(500, null, null,
                    "stored storage credential is not sealed by this server; refusing to hand it"
                            + " out as a plaintext secret");
        }
        if (key == null) {
            throw new ApiException(500, null, null,
                    "catalog has static storage credentials but paimon.rest.storage.credential-secret-key"
                            + " is not configured; cannot decrypt them");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(sealed.substring(SEALED_PREFIX.length()));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(500, null, null,
                    "stored storage credential is not valid base64; it may have been corrupted");
        }
        if (payload.length <= IV_BYTES) {
            throw new ApiException(500, null, null,
                    "stored storage credential is too short to contain an initialization vector");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            // 认证标签校验失败只有两种原因：密钥换了，或密文被改过。两者都要人来处理
            throw new ApiException(500, null, null,
                    "stored storage credential cannot be decrypted with the configured"
                            + " paimon.rest.storage.credential-secret-key; it was sealed with a different key");
        }
    }

    /**
     * 解析配置里的密钥。
     *
     * <p>只接受 base64 的 32 字节：口令式的短密钥在这种「长期密钥可被离线爆破」的场景里
     * 起不到作用，而接受它会让运维以为配了就安全。
     */
    private static SecretKeySpec parseKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(
                    "paimon.rest.storage.credential-secret-key must be base64; generate one with `"
                            + GENERATE_HINT + "`");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("paimon.rest.storage.credential-secret-key must decode to "
                    + KEY_BYTES + " bytes (AES-256), got " + raw.length + "; generate one with `"
                    + GENERATE_HINT + "`");
        }
        return new SecretKeySpec(raw, ALGORITHM);
    }
}
