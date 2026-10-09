package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.CredentialCipher;
import org.junit.jupiter.api.Test;

/**
 * 静态凭据加解密的纯单元测试，不起 Spring 上下文。
 *
 * <p>这一层要钉住的不是「能不能加解密」——那是 JDK 的事——而是几条只在边界上才看得出来的约定：
 * 未配置密钥时必须拒绝加密（而不是明文落库）、换密钥后必须解密失败而不是给出乱码、
 * 明文与密文必须可判别（否则一段历史遗留的明文会被当密文解出无意义字节发给引擎）。
 * 这些错了都不会抛异常，只会在很远的地方表现为「引擎认证失败」。
 */
class CredentialCipherTests {

    /** base64 的 32 字节（AES-256）。用固定值而不是随机生成，失败时好复现。 */
    private static final String KEY_A = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String KEY_B = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";

    private static final String SECRET = "minio-secret-0123456789";

    private static CredentialCipher cipher(String key) {
        RestServerProperties properties = new RestServerProperties();
        properties.getStorage().setCredentialSecretKey(key);
        return new CredentialCipher(properties);
    }

    @Test
    void sealedSecretRoundTrips() {
        CredentialCipher cipher = cipher(KEY_A);

        String sealed = cipher.seal(SECRET);

        assertTrue(CredentialCipher.sealed(sealed), "密文应带判别前缀: " + sealed);
        assertFalse(sealed.contains(SECRET), "密文里不应出现明文: " + sealed);
        assertEquals(SECRET, cipher.reveal(sealed));
    }

    /**
     * 同一个明文两次加密必须得到不同的密文。
     *
     * <p>这条守的是 IV 是否真的每次随机：固定 IV 的 GCM 不只是「密文可被比对」，
     * 它会在同一密钥下泄漏明文异或值，进而让攻击者恢复出内容。
     */
    @Test
    void sealingIsNotDeterministic() {
        CredentialCipher cipher = cipher(KEY_A);

        assertNotEquals(cipher.seal(SECRET), cipher.seal(SECRET));
    }

    @Test
    void nullStaysNull() {
        CredentialCipher cipher = cipher(KEY_A);

        assertNull(cipher.seal(null));
        assertNull(cipher.reveal(null));
    }

    /** 未配置密钥：加密必须拒绝，且要给出生成密钥的办法。 */
    @Test
    void sealIsRejectedWhenNoKeyIsConfigured() {
        CredentialCipher cipher = cipher(null);

        assertFalse(cipher.available());
        ApiException failure = assertThrows(ApiException.class, () -> cipher.seal(SECRET));
        assertEquals(400, failure.getStatus());
        assertTrue(failure.getMessage().contains("credential-secret-key"), failure.getMessage());
        assertTrue(failure.getMessage().contains("openssl rand -base64 32"),
                "错误信息应给出生成密钥的命令: " + failure.getMessage());
    }

    /** 换了密钥的部署里，旧密文必须报错，不能返回一段乱码当密钥用。 */
    @Test
    void revealFailsWhenTheKeyChanged() {
        String sealed = cipher(KEY_A).seal(SECRET);

        ApiException failure = assertThrows(ApiException.class, () -> cipher(KEY_B).reveal(sealed));
        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("credential-secret-key"), failure.getMessage());
    }

    /** 密文被改过（认证标签不匹配）同样要报错。 */
    @Test
    void revealFailsOnTamperedCiphertext() {
        CredentialCipher cipher = cipher(KEY_A);
        String sealed = cipher.seal(SECRET);
        // 改掉最后一个字符即可破坏认证标签；base64 解码仍然成功
        char last = sealed.charAt(sealed.length() - 1);
        String tampered = sealed.substring(0, sealed.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertEquals(500, assertThrows(ApiException.class, () -> cipher.reveal(tampered)).getStatus());
    }

    /**
     * 明文不能当密文用。
     *
     * <p>判别的意义在这条：本特性上线前写入的 catalog、或从别处导入的 JSON，
     * 该字段里可能是明文。当成密文去解会得到一段无意义的字节（甚至解密失败），
     * 而把它当明文下发就等于把一段没人校验过的字符串交给引擎。
     * 明确拒绝，把问题停在「这份数据不该在这里」。
     */
    @Test
    void revealRejectsUnsealedValues() {
        CredentialCipher cipher = cipher(KEY_A);

        ApiException failure = assertThrows(ApiException.class, () -> cipher.reveal("plain-secret"));
        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("not sealed"), failure.getMessage());
        assertFalse(CredentialCipher.sealed("plain-secret"));
        assertFalse(CredentialCipher.sealed(""));
        assertFalse(CredentialCipher.sealed(null));
    }

    @Test
    void revealFailsWhenPayloadIsMalformed() {
        CredentialCipher cipher = cipher(KEY_A);

        assertEquals(500, assertThrows(ApiException.class,
                () -> cipher.reveal("v1:!!!not-base64!!!")).getStatus());
        assertEquals(500, assertThrows(ApiException.class,
                () -> cipher.reveal("v1:AAAA")).getStatus());
    }

    /**
     * 取值非法（不是 base64、或不是 32 字节）属于配置错误，构造期即失败。
     *
     * <p>若只把它记成「加密功能不可用」，线上现象会是「保存静态凭据一直报错」，
     * 而错误现场离配置现场很远。
     */
    @Test
    void malformedKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> cipher("not-base64!!"));
        assertThrows(IllegalStateException.class, () -> cipher("c2hvcnQ="));
    }

    /** 空白取值按「未配置」处理，而不是配置错误——默认配置里该键就是空的。 */
    @Test
    void blankKeyMeansDisabled() {
        assertFalse(cipher("").available());
        assertFalse(cipher("   ").available());
    }
}
