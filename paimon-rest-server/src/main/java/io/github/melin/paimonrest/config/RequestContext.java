package io.github.melin.paimonrest.config;

import io.github.melin.paimonrest.support.AuditPrincipal;

/**
 * 单次请求的上下文：保存当前认证主体，供审计字段写入。
 *
 * <p>与 Polaris 的做法一致，认证在服务端统一完成，各引擎请求都经过同一层鉴权，
 * 因此业务代码只需要读取主体名，不需要感知具体认证方式。
 *
 * <p>这里保存的是<b>原始</b>主体名：授权判定按它查授权链路，长度不受限。
 * 落库到审计列时才会归一化（超长的记摘要），见
 * {@link AuditPrincipal#of}——两处的要求不同，所以不在这一层改写。
 */
public final class RequestContext {

    private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setPrincipal(String principal) {
        PRINCIPAL.set(principal);
    }

    public static String principal() {
        String principal = PRINCIPAL.get();
        return principal == null || principal.isBlank() ? AuditPrincipal.ANONYMOUS : principal;
    }

    public static void clear() {
        PRINCIPAL.remove();
    }

    public static long now() {
        return System.currentTimeMillis();
    }
}
