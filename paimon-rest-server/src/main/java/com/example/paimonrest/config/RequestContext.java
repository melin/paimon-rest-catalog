package com.example.paimonrest.config;

/**
 * 单次请求的上下文：保存当前认证主体，供审计字段写入。
 *
 * <p>与 Polaris 的做法一致，认证在服务端统一完成，各引擎请求都经过同一层鉴权，
 * 因此业务代码只需要读取主体名，不需要感知具体认证方式。
 */
public final class RequestContext {

    private static final String DEFAULT_PRINCIPAL = "anonymous";

    private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setPrincipal(String principal) {
        PRINCIPAL.set(principal);
    }

    public static String principal() {
        String principal = PRINCIPAL.get();
        return principal == null || principal.isBlank() ? DEFAULT_PRINCIPAL : principal;
    }

    public static void clear() {
        PRINCIPAL.remove();
    }

    public static long now() {
        return System.currentTimeMillis();
    }
}
