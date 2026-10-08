package com.example.paimonrest.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 让 JSON 请求体同时接受 {@code Content-Type: text/plain}。
 *
 * <p><b>为什么需要它。</b>Apache Paimon 官方 REST 客户端（1.3.x，底层是
 * Apache HttpClient 5）在发送请求体时把媒体类型标成了 {@code text/plain; charset=UTF-8}，
 * 而实体本身是 JSON。抓到的原始请求如下：
 *
 * <pre>
 * POST /v1/paimon/databases HTTP/1.1
 * Content-Type: text/plain; charset=UTF-8
 *
 * {"name":"e2e_demo","options":{"owner":"melin"}}
 * </pre>
 *
 * <p>本服务端没有在任何映射上声明 {@code consumes}，因此 415 不是映射层面的拒绝，
 * 而是消息转换器层面：{@code text/plain} 只被 {@code StringHttpMessageConverter} 支持，
 * 它读不了 DTO，于是 Spring 报
 * {@code Content-Type 'text/plain;charset=UTF-8' is not supported.}
 * 客户端拿到这句，最终抛出 {@code RESTException: Unable to process: ...}——
 * 只看客户端报错很难定位到服务端。
 *
 * <p>这里给「本来就支持 {@code application/json} 的转换器」补上 {@code text/plain}，
 * 而不是逐个大改 39 处 {@code @RequestBody} 的 {@code consumes}。
 * 只挑原生支持 {@code application/json} 的转换器，因此不会把 {@code text/plain}
 * 误挂到 XML / CBOR / Smile 这些转换器上。
 *
 * <p>代价是放宽了入参校验：请求体是 JSON、媒体类型却标成 {@code text/plain} 的调用方
 * 也能通过。这是为了与现网客户端互通的必要让步。
 *
 * <p>相关回归测试见 {@code JsonContentTypeCompatibilityTests}——它按客户端真实的
 * {@code Content-Type} 发请求，避免这个兼容性行为被后来的重构悄悄改回去。
 */
@Configuration
public class JsonContentTypeConfig implements WebMvcConfigurer {

    /** 客户端实际发出的媒体类型，带 charset。 */
    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType("text", "plain", StandardCharsets.UTF_8);

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (!(converter instanceof AbstractHttpMessageConverter<?> configurable)) {
                continue;
            }
            List<MediaType> supported = configurable.getSupportedMediaTypes();
            if (!supported.contains(MediaType.APPLICATION_JSON) || supported.contains(TEXT_PLAIN_UTF8)) {
                continue;
            }
            List<MediaType> extended = new ArrayList<>(supported);
            extended.add(TEXT_PLAIN_UTF8);
            configurable.setSupportedMediaTypes(extended);
        }
    }
}
