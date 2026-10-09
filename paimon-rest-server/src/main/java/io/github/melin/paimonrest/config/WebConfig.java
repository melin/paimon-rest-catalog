package io.github.melin.paimonrest.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：把鉴权与授权拦截器挂到 catalog API、管理 API 与控制台扩展端点上。
 *
 * <p>三套 API 共用同一层认证，与 Polaris 的做法一致：管理服务与 catalog 服务
 * 由同一进程承载，认证只做一次。
 *
 * <p><b>认证范围是「默认保护，显式豁免」。</b>路径通配符收得比实际需要的宽
 * （{@code /api/catalog/v1/**} 目前只有一个端点），因为漏保护一个新端点是
 * 静默的——它上线后能匿名访问，而没有任何测试会发现。反过来，
 * 多保护一个端点会立刻表现为 401，一眼可见。豁免项因此逐个列出，
 * 每条都有必须存在的理由：
 *
 * <ul>
 *   <li>{@code /api/console/v1/auth}：登录页要先知道「支持哪些登录方式」，
 *       它被挡在鉴权后面会形成死循环。
 *   <li>{@code /api/console/v1/login}：用它换令牌。
 *   <li>{@code /api/catalog/v1/oauth/tokens}：同上，OAuth 版本。
 * </ul>
 *
 * <p>控制台自身的静态资源（{@code /console/**}）不在认证范围内。这不是豁免，
 * 而是浏览器无法在一个文档请求上带 {@code Authorization} 头——保护它的唯一办法是
 * Cookie 会话，而引入 Cookie 会把 CSRF 一并引入，对一组不含任何数据的
 * JS 与 CSS 来说不值得。页面里的数据请求仍然逐个走鉴权。
 *
 * <p><b>三条豁免路径的使用范围由 {@link BearerAuthInterceptor} 决定</b>：
 * 它在「整体鉴权开着」或「控制台要求登录」时都要求令牌，
 * 后者只作用于 {@code /api/console/v1/**}——理由写在那个类上。
 *
 * <p>注册顺序即执行顺序：{@link BearerAuthInterceptor} 先解析主体，
 * {@link AuthorizationInterceptor} 再按 {@link CatalogAccessRules} 判定权限。
 * 授权拦截器只作用于 {@code /v1/**}，管理 API 的判定发生在控制器内
 * （因为要等请求体解析出实体名）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final BearerAuthInterceptor bearerAuthInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(bearerAuthInterceptor)
                .addPathPatterns(
                        "/v1/**",
                        "/api/catalog/v1/**",
                        "/api/management/v1/**",
                        "/api/console/v1/**")
                .excludePathPatterns(
                        "/api/console/v1/auth",
                        "/api/console/v1/login",
                        "/api/catalog/v1/oauth/tokens");
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/v1/**");
    }
}
