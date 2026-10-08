package com.example.paimonrest.service;

import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import com.example.paimonrest.support.Json;
import com.example.paimonrest.support.VendedStorageCredential;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 已下发凭据的短期复用缓存，容量由
 * {@code paimon.rest.storage-credential-cache.max-entries} 决定。
 *
 * <p><b>为什么要缓存。</b>读一张表是高频动作，而凭据解析要读配置、算过期时间。
 * 更实际的原因是语义：同一份未过期的凭据重复下发，调用方看到的是同一个凭据，
 * 而不是每次读表都换一把——后者会让「凭据泄漏了，作废它」这件事变得没有着力点。
 *
 * <p><b>键里放什么。</b>catalog id、整份存储配置的 JSON、表路径。放整份配置而不是
 * 只放存储类型，是因为区域、端点、{@code stsUnavailable}、具名存储都会改变下发结果；
 * 只放类型会让「改了 S3 端点」这类修改在缓存过期前不生效。
 *
 * <p><b>上限的作用。</b>条目在凭据过期时自然失效，上限只兜住
 * 「大量不同的表在同一时刻各自持有一份未过期凭据」的内存占用。
 * 淘汰用访问序（LRU）而不是插入序：持续读同一批表的场景下，
 * 插入序会把还在用的条目淘汰掉。
 *
 * <p>并发上直接对整个映射加锁。下发凭据本身是低频动作（每张表每小时一次量级），
 * 换成细粒度并发结构带来的复杂度换不到可观测的收益。
 */
@Component
@RequiredArgsConstructor
public class StorageCredentialCache {

    private final RestServerProperties properties;

    /** 访问序映射，容量由 {@code maxEntries} 约束。 */
    private final Map<String, Cached> entries = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 取一份未过期的凭据。
     *
     * <p>过期条目在读取时顺手删除，而不是等定时清理：
     * 定时任务在没有流量的部署里纯属空转，而读路径本来就要判断过期。
     */
    public synchronized Optional<Cached> get(String key) {
        Cached entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis() <= System.currentTimeMillis()) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /**
     * 放入一份刚签发的凭据。
     *
     * <p>{@code ttlSeconds} 非正时不入缓存：存进去立刻过期，
     * 只会白占一个可能被其它条目挤掉的位置。
     *
     * @param issuedAtMillis 签发时刻，来自调用方，便于测试注入固定时间
     * @return 该凭据连同它的绝对过期时刻
     */
    public synchronized Cached put(String key, VendedStorageCredential credential, long issuedAtMillis) {
        Cached cached = new Cached(credential, issuedAtMillis + credential.ttlSeconds() * 1000L);
        if (credential.ttlSeconds() > 0) {
            entries.put(key, cached);
            evictOverflow();
        }
        return cached;
    }

    /** 当前条目数，供测试与诊断使用。 */
    public synchronized int size() {
        return entries.size();
    }

    /** 容量上限。 */
    public long maxEntries() {
        return properties.getStorageCredentialCache().getMaxEntries();
    }

    /** 清空缓存，供测试隔离使用。 */
    public synchronized void clear() {
        entries.clear();
    }

    /**
     * 组装缓存键。
     *
     * <p>存储配置用 JSON 文本参与键，而不是重写一遍各字段：新增字段时
     * 这里不用跟着改，漏改的代价是缓存命中率下降而非结果错误——
     * 这个失效方向是可以接受的。
     */
    public static String key(String catalogId, StorageConfigInfo storage, String tablePath) {
        return catalogId + "|" + Json.write(storage) + "|" + (tablePath == null ? "" : tablePath);
    }

    /**
     * 淘汰超出容量的最久未使用条目。
     *
     * <p>不用 {@code LinkedHashMap.removeEldestEntry}：那个钩子在 {@code put} 内部调用，
     * 想让它读 {@code properties} 就得把上限塞进匿名子类，
     * 与其让上限藏在一个构造表达式里，不如在调用点显式写明。
     */
    private void evictOverflow() {
        long max = maxEntries();
        if (max <= 0) {
            entries.clear();
            return;
        }
        Iterator<String> oldest = entries.keySet().iterator();
        while (entries.size() > max && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** 缓存条目：凭据与它的绝对过期时刻。 */
    public record Cached(VendedStorageCredential credential, long expiresAtMillis) {
    }
}
