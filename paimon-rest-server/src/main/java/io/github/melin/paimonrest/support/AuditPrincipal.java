package io.github.melin.paimonrest.support;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 写进审计列的主体名。
 *
 * <p><b>为什么需要它。</b>{@code owner} / {@code created_by} / {@code updated_by}
 * 三列是 {@code varchar(255)}，而主体名的长度没有上界：令牌认证在认不出令牌时
 * 会退化为「令牌即主体名」（便于本地用 {@code Bearer alice} 调试），
 * 控制台签发的访问令牌本身就有 272 个字符——于是每一次写入都撞上
 * MySQL 的 {@code Data too long for column 'created_by'}，
 * 现象是「建表报 500」，而错误信息里看不出与认证有关。
 *
 * <p><b>超长时不截断，改记摘要。</b>截断会把凭据的前 255 个字符原样存进元数据库，
 * 而元数据是可以被列表接口读出来的；摘要则既不泄露原文，又保留「同一个值得到同一个
 * 标签」的可追溯性。长度上限只与列定义有关，因此这里与实体上的 {@code length}
 * 共用 {@link #MAX_LENGTH}。
 *
 * <p><b>代价说明白。</b>超长的主体名在审计列里看不到原名。这不是信息损失，
 * 而是「超过 255 字符的字符串不是一个主体名，而多半是凭据被当成了名字」——
 * 告警会给出该配的东西（{@code paimon.rest.auth.token-principals}）。
 * 授权判定读的是 {@code RequestContext} 里的原名，不受这里影响。
 */
@Slf4j
public final class AuditPrincipal {

    /** 请求没有携带任何身份时记录的审计主体名。 */
    public static final String ANONYMOUS = "anonymous";

    /** 与 {@code owner} / {@code created_by} / {@code updated_by} 三列的 {@code varchar(255)} 对应。 */
    public static final int MAX_LENGTH = 255;

    /** 摘要标签前缀。带前缀是为了让「这是摘要」在数据里一眼可见，不必翻文档。 */
    public static final String DIGEST_PREFIX = "sha256:";

    /** 摘要标签取前多少位十六进制字符。12 位（48 bit）足以区分实际可能出现的取值。 */
    public static final int DIGEST_CHARS = 12;

    /** 每个进程只告警一次：同一类配置问题不该在每次写入时刷屏。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private AuditPrincipal() {
    }

    /**
     * 归一化一个主体名，保证结果能放进审计列。
     *
     * <p>幂等：对已经归一化过的值再调用不会改变结果（摘要标签只有 19 个字符）。
     */
    public static String of(String principal) {
        if (principal == null || principal.isBlank()) {
            return ANONYMOUS;
        }
        String name = principal.trim();
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        String label = DIGEST_PREFIX + Digests.sha256Hex(name).substring(0, DIGEST_CHARS);
        warnOnce(name.length(), label);
        return label;
    }

    private static void warnOnce(int length, String label) {
        if (!WARNED.compareAndSet(false, true)) {
            return;
        }
        log.warn("a principal name of {} characters exceeds the audit columns (varchar({})) and was recorded"
                + " as {}. A name this long is usually a credential used as a principal: map tokens to real"
                + " names with paimon.rest.auth.token-principals (static tokens) or point the OIDC principal"
                + " claim at a name-shaped claim. The value is hashed rather than truncated so no part of a"
                + " secret reaches the metadata database", length, MAX_LENGTH, label);
    }
}
