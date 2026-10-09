package io.github.melin.paimonrest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可推进的固定时钟，供测试使用。
 *
 * <p>凡是「到期」「窗口」这类与时间有关的判定，测试都必须能把时间推过去。
 * 用真实等待的问题有两个：长周期（例如 1 小时的令牌 TTL）根本没法覆盖，
 * 而把周期改短又会引入负载导致的假失败——同一份测试在 CI 上偶发红，
 * 比不测更糟。
 */
final class TestClock extends Clock {

    private final ZoneId zone;

    private Instant instant;

    TestClock(String isoInstant) {
        this(isoInstant, ZoneId.of("UTC"));
    }

    TestClock(String isoInstant, ZoneId zone) {
        this.instant = Instant.parse(isoInstant);
        this.zone = zone;
    }

    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(instant.toString(), newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
