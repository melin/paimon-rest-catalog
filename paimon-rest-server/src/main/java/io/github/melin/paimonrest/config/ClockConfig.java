package io.github.melin.paimonrest.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源。
 *
 * <p>把 {@link Clock} 注册成 bean，是为了让「令牌过期」「失败限速的窗口」这类
 * 与时间有关的判定可以注入固定时钟。直接用 {@code System.currentTimeMillis()}
 * 的话，验证「令牌在 TTL 之后失效」只能在测试里 {@code Thread.sleep}——
 * 既慢又不稳定（机器一卡就假失败）。
 *
 * <p>使用方：{@code AccessTokenService}（签发与校验 exp）、
 * {@code OidcService}（时钟偏移内的 exp / nbf）、
 * {@code LoginAttemptLimiter}（失败计数的窗口）。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
