package io.github.melin.paimonrest.config;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 控制台静态资源的映射。
 *
 * <p>控制台是一个 history 路由的 Vue 单页应用（基址 {@code /console/}），
 * 因此服务端要承担两件事：
 *
 * <ol>
 *   <li>把 {@code /console/**} 下的真实文件（HTML、JS、CSS）按静态资源返回；
 *   <li>把「不是文件」的路径交给入口页，让前端路由接管。用户刷新
 *       {@code /console/catalogs} 或分享这条链接时，走的都是这一条。
 * </ol>
 *
 * <p><b>为什么用资源解析器而不是一个回退控制器。</b>控制器映射
 * （{@code @GetMapping("/console/**")}）的优先级高于静态资源处理器，一旦挂上去就会
 * 把 assets 也接管过来，于是得在控制器里自己判断「这是文件还是路由」，
 * 再想办法把文件请求交还给静态资源链——绕一圈，还要处理转发回自身导致的循环。
 * {@link PathResourceResolver} 本来就在这条链上，「文件不存在则退回入口页」
 * 正是它的职责范围。
 *
 * <p><b>判断依据是路径末段有没有扩展名。</b>只对「像前端路由」的路径回退，
 * 缺失的 {@code .js} / {@code .css} 仍然返回 404——否则一次构建产物不同步
 * （浏览器缓存里是旧 HTML，引用了已被替换掉的带哈希文件）会表现为「页面白屏但状态码 200」，
 * 比直接 404 难查得多。
 */
@Slf4j
@Configuration
public class ConsoleWebConfig implements WebMvcConfigurer {

    /** 控制台在 classpath 中的位置，与 vite.config.js 的 outDir 指向同一处。 */
    private static final String CONSOLE_LOCATION = "classpath:/static/console/";

    private static final String CONSOLE_ENTRY = "index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console/**")
                .addResourceLocations(CONSOLE_LOCATION)
                // 关掉资源链缓存：解析结果依赖请求路径，缓存只会增加一层「改了配置要重启」的困扰，
                // 而控制台是内网管理界面，省下的这点 IO 没有意义。
                .resourceChain(false)
                .addResolver(new SinglePageAppResolver());
    }

    /**
     * {@code /console} 与 {@code /console/} 的规范化。
     *
     * <p>两条映射分别解决一个具体问题：
     *
     * <ul>
     *   <li><b>{@code /console} → {@code /console/}</b>：不依赖「{@code /**} 是否匹配零段路径」
     *       这个细节，显式一条重定向。history 模式的基址与 Vite 生成的相对路径都以斜杠结尾，
     *       这一步不是可有可无的规范化。
     *   <li><b>{@code /console/} → 转发到入口页</b>：这条**必须**存在，且不能由资源解析器接管。
     *       访问目录式路径时，Spring 交给资源处理器的路径是空的，而
     *       {@code ResourceHttpRequestHandler} 对空路径直接抛
     *       {@code NoResourceFoundException}（报错文本是 {@code No static resource .}——
     *       末尾那个点号来自消息模板，不是路径的一部分，容易读错），
     *       解析器根本没有机会介入。用视图转发绕开这一步。
     * </ul>
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/console", "/console/");
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
    }

    /** 命中文件就返回文件，否则退回入口页。 */
    private static final class SinglePageAppResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            String path = normalize(resourcePath);

            Resource requested = location.createRelative(path);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }

            // 末段带扩展名说明请求的是具体文件，缺失就是真的缺失，不要回退
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            if (lastSegment.contains(".")) {
                return null;
            }

            Resource entry = location.createRelative(CONSOLE_ENTRY);
            return entry.exists() && entry.isReadable() ? entry : null;
        }

        /**
         * 把「指向目录而不是文件」的路径归一成入口页。
         *
         * <p>退到这里的目录式路径是带尾巴的形态（如 {@code /console/assets/}）；
         * 裸的 {@code /console/} 到不了这一步，它由
         * {@link #addViewControllers} 里的一条转发承接，原因见那里的说明。
         */
        private static String normalize(String resourcePath) {
            if (resourcePath == null || resourcePath.isEmpty() || ".".equals(resourcePath)) {
                return CONSOLE_ENTRY;
            }
            return resourcePath.endsWith("/") ? resourcePath + CONSOLE_ENTRY : resourcePath;
        }
    }
}
