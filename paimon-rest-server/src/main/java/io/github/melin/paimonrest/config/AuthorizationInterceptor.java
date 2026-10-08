package io.github.melin.paimonrest.config;

import io.github.melin.paimonrest.service.AuthorizationService;
import io.github.melin.paimonrest.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * catalog API（{@code /v1/**}）的授权拦截器。
 *
 * <p>在 {@link BearerAuthInterceptor} 之后执行，因此这里读取到的
 * {@link RequestContext#principal()} 已经是解析后的主体名。判定用的映射表见
 * {@link CatalogAccessRules}。
 *
 * <p><b>失败关闭。</b>授权开启时，{@code /v1/**} 下未在映射表登记的端点返回 403 而不是放行。
 * 只有 {@link CatalogAccessRules#isPublic(String)} 明示豁免的端点可以匿名访问。
 *
 * <p>管理 API（{@code /api/management/v1/**}）不经过本拦截器：那些端点的授权要求
 * 依赖请求体里的实体名（例如要授予的是哪个 catalog role），由控制器在拿到参数后判定。
 */
@Component
@RequiredArgsConstructor
public class AuthorizationInterceptor implements HandlerInterceptor {

    private final AuthorizationService authorizationService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!authorizationService.enabled()) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith("/v1/")) {
            return true;
        }
        if (!(handler instanceof HandlerMethod)) {
            // 静态资源等非端点处理器
            return true;
        }
        if (CatalogAccessRules.isPublic(uri)) {
            return true;
        }
        String action = request.getMethod() + " " + uri;
        CatalogAccessRules.Requirement requirement = CatalogAccessRules.resolve(uri, request.getMethod());
        if (requirement == null) {
            throw ApiException.forbidden("The caller does not have permission to " + action
                    + ": no authorization rule is registered for this endpoint");
        }
        authorizationService.require(requirement.catalog(), requirement.resourceType(),
                requirement.namespace(), requirement.objectName(), requirement.privilege(), action);
        return true;
    }
}
