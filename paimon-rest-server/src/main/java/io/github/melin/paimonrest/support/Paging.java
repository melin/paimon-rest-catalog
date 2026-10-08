package io.github.melin.paimonrest.support;

import java.util.List;
import java.util.UUID;

/**
 * 分页工具：把不透明的 {@code pageToken} 编解码为列表偏移量。
 *
 * <p>规格只要求 token 对客户端不透明、服务端可自解释；这里使用
 * {@code base64url("offset:N")}，实现简单且无状态。
 */
public final class Paging {

    private static final String PREFIX = "offset:";

    private Paging() {
    }

    public static int pageSize(Integer requested, int defaultSize, int maxSize) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultSize, maxSize);
        }
        return Math.min(requested, maxSize);
    }

    public static String encode(int offset) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIX + offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 解析 token；null / 空串表示第一页。 */
    public static int offset(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(token),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.startsWith(PREFIX)) {
                return Math.max(0, Integer.parseInt(decoded.substring(PREFIX.length())));
            }
        } catch (RuntimeException ignored) {
            // 落到下面的统一报错
        }
        throw ApiException.badRequest("Invalid pageToken");
    }

    /** 对已排序的完整结果集做切片。 */
    public static <T> Slice<T> slice(List<T> all, int offset, int size) {
        if (offset >= all.size()) {
            return new Slice<>(List.of(), null);
        }
        int end = Math.min(all.size(), offset + size);
        List<T> items = List.copyOf(all.subList(offset, end));
        String next = end < all.size() ? encode(end) : null;
        return new Slice<>(items, next);
    }

    public record Slice<T>(List<T> items, String nextPageToken) {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
