package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.service.LoginAttemptLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link LoginAttemptLimiter} 的单元测试。
 *
 * <p>限速最容易出的错不是「防不住」，而是<b>防过头</b>：把成功的请求也计入，
 * 会让一个正常开着的控制台被自己的刷新流量锁住；把窗口算错，会让锁定期无限延长。
 * 因此这里重点覆盖边界：恰好到阈值、窗口刚过、不同来源互不影响。
 */
class LoginAttemptLimiterTests {

    private final TestClock clock = new TestClock("2026-01-01T00:00:00Z");

    @Test
    void failuresWithinTheWindowAreAllowedUpToTheLimit() {
        LoginAttemptLimiter limiter = limiter(3, Duration.ofMinutes(1), true);

        assertTrue(limiter.allow(policy, "10.0.0.1", "console"));
        limiter.recordFailure(policy, "10.0.0.1", "console");
        assertTrue(limiter.allow(policy, "10.0.0.1", "console"));
        limiter.recordFailure(policy, "10.0.0.1", "console");
        assertTrue(limiter.allow(policy, "10.0.0.1", "console"));
        limiter.recordFailure(policy, "10.0.0.1", "console");

        // 第 3 次失败后正好到达阈值，下一次不再放行
        assertFalse(limiter.allow(policy, "10.0.0.1", "console"), "达到阈值后应当拒绝");
    }

    /** 窗口过去后恢复放行，而不是永久锁死。 */
    @Test
    void theWindowResetsOnItsOwn() {
        LoginAttemptLimiter limiter = limiter(2, Duration.ofMinutes(1), true);
        limiter.recordFailure(policy, "10.0.0.1", "console");
        limiter.recordFailure(policy, "10.0.0.1", "console");
        assertFalse(limiter.allow(policy, "10.0.0.1", "console"));

        clock.advance(Duration.ofSeconds(59));
        assertFalse(limiter.allow(policy, "10.0.0.1", "console"), "窗口未过时仍应拒绝");

        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.allow(policy, "10.0.0.1", "console"), "窗口过半后应当恢复");
    }

    /**
     * 桶键是「来源地址 + 客户端标识」。
     *
     * <p>两个维度各自独立：同一台机器上的两个客户端互不影响（否则一个人试错
     * 会把另一个团队锁住），同一个客户端从不同来源来也互不影响
     * （否则攻击者换 IP 就能绕开——那正是这个键要防的）。
     */
    @Test
    void bucketsAreIndependentPerAddressAndClientId() {
        LoginAttemptLimiter limiter = limiter(1, Duration.ofMinutes(1), true);

        limiter.recordFailure(policy, "10.0.0.1", "console");
        assertFalse(limiter.allow(policy, "10.0.0.1", "console"));
        assertTrue(limiter.allow(policy, "10.0.0.2", "console"), "另一个来源不该受影响");
        assertTrue(limiter.allow(policy, "10.0.0.1", "spark"), "另一个客户端标识不该受影响");
    }

    /** 关闭限速时永远放行，且不记录任何状态。 */
    @Test
    void disabledLimiterAlwaysAllows() {
        LoginAttemptLimiter limiter = limiter(1, Duration.ofMinutes(1), false);

        for (int i = 0; i < 100; i++) {
            limiter.recordFailure(policy, "10.0.0.1", "console");
        }

        assertTrue(limiter.allow(policy, "10.0.0.1", "console"));
        assertEquals(0, limiter.bucketCount(), "关闭时不该留下任何桶");
    }

    /**
     * 桶数有上限，且达到上限时不会抛异常。
     *
     * <p>攻击者编造不同的 clientId 会凭空制造无限个桶——这是把限速器本身
     * 变成内存耗尽入口的做法。这里只断言「不崩」，因为达到上限后的降级行为
     * （停止为新来源计数）是刻意的：它优先保证正常请求还能进来。
     */
    @Test
    void bucketCountIsBounded() {
        LoginAttemptLimiter limiter = limiter(1, Duration.ofMinutes(1), true);

        for (int i = 0; i < 10_000; i++) {
            limiter.recordFailure(policy, "10.0.0." + (i % 256), "client-" + i);
        }

        assertTrue(limiter.bucketCount() <= 4096,
                () -> "桶数应当有上限，实际 " + limiter.bucketCount());
    }

    /** 达到桶数上限后，过期的桶会被清理，腾出空间给新的来源。 */
    @Test
    void expiredBucketsAreEvictedWhenTheLimitIsReached() {
        LoginAttemptLimiter limiter = limiter(1, Duration.ofMinutes(1), true);
        for (int i = 0; i < 4096; i++) {
            limiter.recordFailure(policy, "10.0.0.1", "client-" + i);
        }

        // 窗口过去后，这 4096 个桶都是垃圾
        clock.advance(Duration.ofMinutes(2));
        assertTrue(limiter.allow(policy, "10.0.0.1", "fresh-client"), "新来源在计数前应当被放行");
        limiter.recordFailure(policy, "10.0.0.1", "fresh-client");

        assertEquals(1, limiter.bucketCount(), "过期的桶应当已被清理，只剩新记下的这个");
        assertFalse(limiter.allow(policy, "10.0.0.1", "fresh-client"),
                "新的失败确实被记下了（maxFailures=1，一次即达阈值）");
    }

    /** 重置能清空计数，让用例之间互不干扰。 */
    @Test
    void resetClearsEverything() {
        LoginAttemptLimiter limiter = limiter(1, Duration.ofMinutes(1), true);
        limiter.recordFailure(policy, "10.0.0.1", "console");
        assertFalse(limiter.allow(policy, "10.0.0.1", "console"));

        limiter.reset();

        assertTrue(limiter.allow(policy, "10.0.0.1", "console"));
        assertEquals(0, limiter.bucketCount());
    }

    /** 当前用例使用的限速策略；由 {@link #limiter} 建好，供各断言与计数调用传入。 */
    private RestServerProperties.Auth.RateLimit policy;

    private LoginAttemptLimiter limiter(int maxFailures, Duration window, boolean enabled) {
        policy = new RestServerProperties.Auth.RateLimit();
        policy.setEnabled(enabled);
        policy.setMaxFailures(maxFailures);
        policy.setWindow(window);
        return new LoginAttemptLimiter(clock);
    }
}
