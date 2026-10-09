package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.domain.entity.AuditedEntity;
import io.github.melin.paimonrest.support.AuditPrincipal;
import org.junit.jupiter.api.Test;

/**
 * {@link AuditPrincipal} 的单元测试，以及它在 {@link AuditedEntity} 上的落点。
 *
 * <p>这条规则来自一个实际故障：门禁模式会把认不出的令牌当成主体名，
 * 而控制台访问令牌长 272 个字符，超过审计列的 {@code varchar(255)}，
 * 于是建表直接以 {@code Data too long for column 'created_by'} 失败——
 * 与认证毫无关系的错误信息，却是认证链路引起的问题。
 *
 * <p>因此这里盯住两件事：<b>长度一定有界</b>（任何输入都不能打出超过列宽的值），
 * 以及<b>超长时不留原文</b>（截断会把凭据的前 255 个字符写进元数据库，
 * 而元数据是能被读出来的）。
 */
class AuditPrincipalTests {

    /** 一段真实长度的控制台访问令牌：272 个字符，与实测签发的令牌等长。 */
    private static final String JWT_LIKE_TOKEN = jwtOfLength(272);

    /**
     * 造一个 JWT 形状、长度恰好为 {@code length} 的字符串。
     *
     * <p>不写成字面量：手抄一段 272 字符的字符串，抄少一位就会让「比列宽更长」
     * 这个前提悄悄失效，而用例仍然通过。
     */
    private static String jwtOfLength(int length) {
        StringBuilder token = new StringBuilder("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.");
        while (token.length() < length) {
            token.append("0fQ7t0mZ2c9V1k8pQ5r4s6u7w8x9y");
        }
        return token.substring(0, length);
    }

    @Test
    void aNameThatFitsTheColumnIsKeptAsIs() {
        assertEquals("alice", AuditPrincipal.of("alice"));
        assertEquals("service-account@paimon", AuditPrincipal.of("service-account@paimon"));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        // 令牌从请求头里取出时已经 trim 过，但主体名也可能来自配置，两处都要能收敛
        assertEquals("alice", AuditPrincipal.of("  alice  "));
    }

    @Test
    void aMissingNameBecomesAnonymous() {
        assertEquals(AuditPrincipal.ANONYMOUS, AuditPrincipal.of(null));
        assertEquals(AuditPrincipal.ANONYMOUS, AuditPrincipal.of(""));
        assertEquals(AuditPrincipal.ANONYMOUS, AuditPrincipal.of("   "));
    }

    @Test
    void aNameOfExactlyTheColumnWidthIsKept() {
        String name = "a".repeat(AuditPrincipal.MAX_LENGTH);

        assertEquals(name, AuditPrincipal.of(name), "恰好等于列宽仍然放得下，不该被摘要掉");
    }

    @Test
    void anOverLongNameBecomesADigestLabel() {
        String label = AuditPrincipal.of("a".repeat(AuditPrincipal.MAX_LENGTH + 1));

        assertTrue(label.startsWith(AuditPrincipal.DIGEST_PREFIX), () -> "实际=" + label);
        assertEquals(AuditPrincipal.DIGEST_PREFIX.length() + AuditPrincipal.DIGEST_CHARS, label.length());
        assertTrue(label.length() <= AuditPrincipal.MAX_LENGTH, "任何结果都必须放得进审计列");
    }

    /** 真正的故障场景：272 字符的访问令牌被当成主体名。 */
    @Test
    void anAccessTokenIsNeverStoredVerbatim() {
        String stored = AuditPrincipal.of(JWT_LIKE_TOKEN);

        assertTrue(JWT_LIKE_TOKEN.length() > AuditPrincipal.MAX_LENGTH,
                () -> "用例前提：这段令牌要长于列宽，实际 " + JWT_LIKE_TOKEN.length() + " 个字符");
        assertNotEquals(JWT_LIKE_TOKEN, stored, "原文不该出现在审计列里");
        assertFalse(stored.contains("eyJ"), "入库的值里不能出现令牌的任何片段");
        // 截断的写法会留下原文前缀，这里明确排除它
        assertFalse(JWT_LIKE_TOKEN.startsWith(stored), "不是截断：结果不该是原文的前缀");
        assertTrue(stored.length() <= AuditPrincipal.MAX_LENGTH);
    }

    @Test
    void theSameNameAlwaysYieldsTheSameLabel() {
        String token = JWT_LIKE_TOKEN;

        assertEquals(AuditPrincipal.of(token), AuditPrincipal.of(token), "同一个值必须得到同一个标签，否则无法追溯");
        assertNotEquals(AuditPrincipal.of(token), AuditPrincipal.of(token + "x"), "不同的值不该撞成同一个标签");
    }

    @Test
    void normalizationIsIdempotent() {
        String once = AuditPrincipal.of(JWT_LIKE_TOKEN);

        assertEquals(once, AuditPrincipal.of(once), "已经归一化的值再走一次不该改变");
    }

    /** 实体是唯一写审计列的地方，因此它必须自己兜住列宽。 */
    @Test
    void theEntityNeverWritesMoreThanTheColumnWidth() {
        ProbeEntity entity = new ProbeEntity();

        entity.markCreated(JWT_LIKE_TOKEN, 1_700_000_000_000L);

        assertTrue(entity.getOwner().length() <= AuditPrincipal.MAX_LENGTH, "owner 超列宽");
        assertTrue(entity.getCreatedBy().length() <= AuditPrincipal.MAX_LENGTH, "createdBy 超列宽");
        assertTrue(entity.getUpdatedBy().length() <= AuditPrincipal.MAX_LENGTH, "updatedBy 超列宽");
        assertEquals(entity.getCreatedBy(), entity.getOwner(), "同一时刻创建，两者应当一致");
        assertEquals(entity.getCreatedBy(), entity.getUpdatedBy(), "创建即首次更新");
        assertEquals(1_700_000_000_000L, entity.getCreatedAt());
        assertEquals(1_700_000_000_000L, entity.getUpdatedAt());
    }

    @Test
    void theEntityKeepsAShortNameAsTheAuditIdentity() {
        ProbeEntity entity = new ProbeEntity();

        entity.touch("alice", 1L);

        assertEquals("alice", entity.getUpdatedBy());
    }

    /** 只为验证基类行为存在的具体实体；不需要映射到任何表。 */
    private static final class ProbeEntity extends AuditedEntity {
    }
}
