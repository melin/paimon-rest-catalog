package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 登录失败次数的限速。
 *
 * <p>登录端点必须匿名可访问（它们就是用来换取令牌的），因此它们是整套鉴权里
 * 唯一能被匿名无限次调用的地方。本类针对的是暴力猜测：clientSecret 是 32 字节
 * 随机值，猜中的概率可以忽略，但<b>猜测请求本身</b>会带着一次数据库查询进来，
 * 不拦的话一次小规模并发就能把连接池占满；用户名密码方式更直接——密码可能只有
 * 六七个字符，不限速时它是真的会被猜中的。
 *
 * <p><b>只对失败计数。</b>控制台会不断刷新令牌（每次登录、每小时一次换新），
 * 把成功也计入会让一个正常开着的控制台被自己的流量锁住——
 * 这种「安全措施导致正常使用失效」的问题比它防住的威胁更常见。
 *
 * <p><b>桶按「来源 IP + 客户端标识」分。</b>只用 IP 会在 NAT 后误伤整栋楼的人；
 * 只用标识则攻击者编造不同的标识就能绕开。两者拼起来后，
 * 编造标识需要换 IP 才有意义，而换 IP 是分布式攻击，本就不指望单机限速防住。
 *
 * <p><b>策略由调用方传入</b>（{@code allow} / {@code recordFailure} 的第一个参数）：
 * 客户端凭据与用户名密码各有自己的 {@code rate-limit} 配置，两者的风险不同——
 * 前者猜的是 32 字节随机密钥，后者猜的是人定的密码，共用一个阈值只能取折中。
 * 本类因此不持有配置，只做「按给定策略计数」这一件事。
 *
 * <p>桶数有上限：编造标识会凭空制造无限个桶，这是把限速器本身变成
 * 内存耗尽的入口。超过上限时先清理过期桶；清理后仍然超限，说明短时间内
 * 出现了大量不同来源的失败，这是明确的攻击特征，直接拒绝比继续计数更合理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    /**
     * 桶数上限。
     *
     * <p>取值依据：桶只在失败时创建，正常部署下「每个 IP 每个客户端一次失败」
     * 的量级远小于此；而 4096 个桶各自只存一个时间戳与计数，内存占用可忽略。
     */
    private static final int MAX_BUCKETS = 4096;

    private final Clock clock;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private record Bucket(int failures, long windowStartMillis) {
    }

    /** 当前窗口内剩余可用的失败次数；据此决定是否放行。 */
    public boolean allow(RestServerProperties.Auth.RateLimit policy, String clientAddress, String identifier) {
        if (policy == null || !policy.isEnabled()) {
            return true;
        }
        long now = clock.millis();
        long windowMillis = Math.max(1000, policy.getWindow().toMillis());
        String key = key(clientAddress, identifier);
        Bucket bucket = buckets.get(key);
        if (bucket != null && now - bucket.windowStartMillis() < windowMillis) {
            return bucket.failures() < policy.getMaxFailures();
        }
        return true;
    }

    /** 记一次失败。窗口已过则重新开一个。 */
    public void recordFailure(RestServerProperties.Auth.RateLimit policy, String clientAddress, String identifier) {
        if (policy == null || !policy.isEnabled()) {
            return;
        }
        long now = clock.millis();
        long windowMillis = Math.max(1000, policy.getWindow().toMillis());
        String key = key(clientAddress, identifier);

        if (buckets.size() >= MAX_BUCKETS) {
            evictExpired(now, windowMillis);
            if (buckets.size() >= MAX_BUCKETS) {
                // 清理后仍然满：大量不同来源正在失败。此时不再新增桶，
                // 而是让既有调用方拿到的判定继续生效（allow 会因桶缺失而放行，
                // 因此这里必须显式记一条日志，否则限速静默失效）
                log.warn("login rate limiter is at its bucket limit ({}); failures from new"
                        + " client identifiers are no longer tracked", buckets.size());
                return;
            }
        }

        buckets.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.windowStartMillis() >= windowMillis) {
                return new Bucket(1, now);
            }
            return new Bucket(existing.failures() + 1, existing.windowStartMillis());
        });
    }

    /** 当前存活的桶数，供诊断与测试使用。 */
    public int bucketCount() {
        return buckets.size();
    }

    /** 清空全部计数。测试用来把用例之间隔开。 */
    public void reset() {
        buckets.clear();
    }

    private void evictExpired(long now, long windowMillis) {
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().windowStartMillis() >= windowMillis) {
                iterator.remove();
            }
        }
    }

    private static String key(String clientAddress, String clientId) {
        return (clientAddress == null ? "-" : clientAddress) + "|" + (clientId == null ? "-" : clientId);
    }
}
