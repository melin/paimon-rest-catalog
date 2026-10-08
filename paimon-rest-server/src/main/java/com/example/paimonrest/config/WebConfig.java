package com.example.paimonrest.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：把鉴权与授权拦截器挂到 catalog API（{@code /v1/**}）
 * 与管理 API（{@code /api/management/v1/**}）上。
 *
 * <p>两套 API 共用同一层认证，与 Polaris 的做法一致：
 * 管理服务与 catalog 服务由同一进程承载，认证只做一次。
 *
 * <p>注册顺序即执行顺序：{@link BearerAuthInterceptor} 先解析主体，
 * {@link AuthorizationInterceptor} 再按
 * {@link CatalogAccessRules} 判定权限。授权拦截器只作用于 {@code /v1/**}，
 * 管理 API 的判定发生在控制器内（因为要等请求体解析出实体名）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final BearerAuthInterceptor bearerAuthInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(bearerAuthInterceptor)
                .addPathPatterns("/v1/**", "/api/management/v1/**");
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/v1/**");
    }
}
