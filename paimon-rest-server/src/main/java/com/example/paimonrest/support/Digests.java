package com.example.paimonrest.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 字符串摘要。
 *
 * <p><b>存在的理由。</b>MySQL 的索引键上限是 3072 字节，utf8mb4 下即 768 个字符；
 * 而有些需要参与唯一约束的值长度没有上界——命名空间路径可以有很多级，
 * 分区 spec 的规范化文本也随字段数增长。把这类值直接放进唯一索引，
 * MySQL 会以 {@code ERROR 1071: Specified key was too long} 拒绝建表。
 *
 * <p>做法是让无上界的值改为「入库原文供等值查询 + 入库摘要供唯一约束」：
 * 摘要定长 {@value #HEX_LENGTH} 个字符，索引长度与值的实际长度解耦，
 * 而唯一性语义不变（SHA-256 的碰撞概率可忽略）。
 */
public final class Digests {

    /** SHA-256 十六进制摘要的字符数。 */
    public static final int HEX_LENGTH = 64;

    private Digests() {
    }

    /**
     * 依次拼接后取 SHA-256 摘要。
     *
     * <p>{@code null} 按空串处理，便于调用方免去判空。
     *
     * @return 小写十六进制摘要，长度恒为 {@value #HEX_LENGTH}
     */
    public static String sha256Hex(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256，走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
