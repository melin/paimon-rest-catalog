package io.github.melin.paimonrest.config;

import io.github.melin.paimonrest.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bearer 鉴权拦截器。
 *
 * <p>{@code paimon.rest.auth.enabled=false}（默认）时放行所有请求；
 * 为 {@code true} 时要求 {@code Authorization: Bearer <token>}，
 * 且 token 必须在 {@code paimon.rest.auth.tokens} 中登记，否则返回 401。
 *
 * <p><b>令牌到主体的映射。</b>授权判定（{@code paimon.rest.authorization.enabled}）是按
 * 主体名查授权链路的，因此这里要把令牌映射成主体名：
 * {@code paimon.rest.auth.token-principals} 给出显式映射；
 * 未配置映射时退化为「令牌即主体名」，便于本地用
 * {@code -H 'Authorization: Bearer alice'} 直接以指定主体身份调试。
 *
 * <p><b>注意。</b>鉴权关闭时任何令牌都会被接受并当作主体名，
 * 若此时开启了授权，调用方可以任意冒用主体。因此启动时会对此组合给出告警，
 * 见 {@link io.github.melin.paimonrest.config.DataInitializer}。
 */
@Component
@RequiredArgsConstructor
public class BearerAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final RestServerProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;

        if (!properties.getAuth().isEnabled()) {
            RequestContext.setPrincipal(token != null
                    ? resolvePrincipal(token)
                    : properties.getAuth().getPrincipal());
            return true;
        }

        if (token == null || !properties.getAuth().getTokens().contains(token)) {
            throw new ApiException(401, null, null, "No auth for this resource");
        }
        RequestContext.setPrincipal(resolvePrincipal(token));
        return true;
    }

    /** 令牌 → 主体名：优先取显式映射，否则用令牌本身。 */
    private String resolvePrincipal(String token) {
        String mapped = properties.getAuth().getTokenPrincipals().get(token);
        return mapped == null || mapped.isBlank() ? token : mapped;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }
}
