package io.github.melin.paimonrest.config;

import io.github.melin.paimonrest.service.TokenAuthenticationService;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bearer 鉴权拦截器。
 *
 * <p>它要求令牌有两种情形，两者都作用在同一段路径上（见 {@code WebConfig}）：
 *
 * <ul>
 *   <li><b>整体鉴权</b>（{@code paimon.rest.auth.enabled=true}）：{@code /v1/**}、
 *       catalog API、管理 API 与控制台扩展端点全部要求令牌。
 *   <li><b>仅控制台门禁</b>（{@code paimon.rest.auth.console.required=true}，默认开，
 *       而整体鉴权关着）：<b>只有控制台扩展端点</b>要求令牌。这是「浏览器打开控制台
 *       要先登录」的实现方式，同时刻意<b>不</b>动数据面——把 {@code auth.enabled}
 *       默认打开会让开箱即用的 {@code curl /v1/config}、Spark 示例与两个验收脚本
 *       全部变成 401。代价是这层门禁挡的是界面而不是数据，这一点在登录页与
 *       {@code docs/console-auth.md} 里都写明了。
 * </ul>
 *
 * <p><b>本类只做两件事：取请求头里的令牌、把认证结果写进 {@link RequestContext}。</b>
 * 令牌来自哪里（服务端配置的静态令牌、控制台登录签发的访问令牌、外部 OIDC）
 * 由认证链判断，本类不关心也不会分叉——所以加一种认证方式不需要动这里。
 *
 * <p><b>「没有令牌」与「令牌无效」返回同一个响应。</b>区分它们对攻击者有用
 * （能确认「这个地址确实要鉴权」以及「我的令牌格式被识别了」），对使用者没用。
 * 两者都是 401 加同一句话。
 *
 * <p><b>例外路径由 {@code WebConfig} 声明</b>，本类看不到它们——拦截器根本不会被调用。
 * 那几个例外之所以必须存在（登录页要先读到「支持哪些登录方式」、登录与令牌端点本身
 * 就是用来换取令牌的），理由写在各自的控制器上。
 */
@Component
@RequiredArgsConstructor
public class BearerAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    /** 控制台自己的扩展端点。门禁只作用于这一段路径。 */
    private static final String CONSOLE_API = "/api/console/v1/";

    private final RestServerProperties properties;

    private final TokenAuthenticationService tokens;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = bearerToken(request);

        if (!requiresToken(request)) {
            // 不需要令牌时也要尽量认出令牌：静态令牌与已签发的访问令牌都能给出真实主体名，
            // 于是「本地用 -H 'Authorization: Bearer alice' 以指定主体调试」照常可用，
            // 而带着令牌访问的实体会被记为它自己的主体而不是 anonymous。
            // 认不出来就退回「令牌即主体名」——这是既有的本地调试约定，保持兼容。
            RequestContext.setPrincipal(token == null
                    ? properties.getAuth().getPrincipal()
                    : tokens.authenticate(token).map(AuthenticatedToken::principal).orElse(token));
            return true;
        }

        if (token == null) {
            throw new ApiException(401, null, null, "No auth for this resource");
        }
        AuthenticatedToken identity = tokens.authenticate(token)
                .orElseThrow(() -> new ApiException(401, null, null, "No auth for this resource"));
        RequestContext.setPrincipal(identity.principal());
        return true;
    }

    /**
     * 这一个请求是否必须带令牌。
     *
     * <p>整体鉴权开着时是全部受管路径；只有控制台门禁开着时是控制台端点那一段。
     * 判据用请求路径而不是「是哪个控制器」：拦截器拿不到处理器映射的业务含义，
     * 而路径前缀在这里就是稳定的契约（与 {@code WebConfig} 的注册模式同一份字符串）。
     */
    private boolean requiresToken(HttpServletRequest request) {
        if (properties.getAuth().isEnabled()) {
            return true;
        }
        return properties.getAuth().getConsole().isRequired()
                && request.getRequestURI().startsWith(CONSOLE_API);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
