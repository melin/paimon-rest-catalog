package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ConsoleDtos;
import io.github.melin.paimonrest.service.ClientCredentialsService;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Json;
import io.github.melin.paimonrest.support.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 令牌端点（客户端凭据流程）。
 *
 * <p><b>路径与 Polaris 对齐</b>（{@code /api/catalog/v1/oauth/tokens}）：按 OAuth 2.0
 * 写的客户端库、以及 Polaris 官方的 console（其默认 {@code VITE_OAUTH_TOKEN_URL}
 * 就是这个地址）都能直接指向本服务端。
 *
 * <p><b>它必须在鉴权之外</b>——它就是用来换取令牌的，要求它自带令牌会形成死循环。
 * 因此它是整套鉴权里唯一能匿名反复调用的端点，防爆破由
 * {@code ClientCredentialsService} 的失败限速承担。
 *
 * <p><b>响应形状用 RFC 6749 的字段名</b>（{@code access_token} 等），不是本工程
 * 其余 DTO 的驼峰。这个端点的消费者是通用 OAuth 客户端，不是控制台一个——
 * 「与规范一致」在这里比「与工程内一致」重要。同理，错误也用 RFC 6749 第 5.2 节
 * 的形状，因此本类自己接住异常，不让它走到把错误统一成
 * {@code ErrorResponse} 的全局处理器。
 *
 * <p><b>支持两种客户端凭据传递方式</b>：请求体参数（{@code client_id} /
 * {@code client_secret}）与 HTTP Basic（RFC 6749 第 2.3.1 节）。规范允许两者，
 * 只支持一种会让一半的现成客户端直接不可用，而实现第二种只需要十几行。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OAuthTokenController {

    private static final String BASIC = "Basic ";

    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    private final ClientCredentialsService clientCredentials;

    /**
     * {@code POST /api/catalog/v1/oauth/tokens}。
     *
     * <p>{@code consumes} 放开到任意类型：规范要求 form-urlencoded，
     * 但用 {@code curl -d '{...}'} 或前端 {@code fetch} 手工发 JSON 是很自然的做法，
     * 拒绝它们只会让人以为端点写错了。两种都接，解析在 {@link #parameters} 里分支。
     */
    @PostMapping(value = "/api/catalog/v1/oauth/tokens", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> tokens(HttpServletRequest request) throws IOException {
        try {
            return issue(request);
        } catch (ApiException e) {
            // 整个方法体只在这一处转成 OAuth 错误形状，因此「本端点的任何失败都是
            // RFC 6749 第 5.2 节的形状」这条性质不依赖于每条异常路径都记得自己转换——
            // 包括参数解析阶段抛出的（JSON 体不合法），它此前会漏到全局处理器，
            // 变成 catalog API 的 {message} 形状
            return oauthError(HttpStatus.valueOf(e.getStatus()),
                    e.getErrorCode() != null ? e.getErrorCode() : errorCodeOf(e.getStatus()),
                    e.getMessage());
        }
    }

    private ResponseEntity<Object> issue(HttpServletRequest request) throws IOException {
        Map<String, String> parameters = parameters(request);

        String grantType = parameters.get("grant_type");
        if (grantType == null || grantType.isBlank()) {
            return oauthError(HttpStatus.BAD_REQUEST, "invalid_request",
                    "grant_type is required; this endpoint supports " + GRANT_CLIENT_CREDENTIALS);
        }
        if (!GRANT_CLIENT_CREDENTIALS.equals(grantType)) {
            // 单独回 unsupported_grant_type 而不是 invalid_request：规范区分这两者，
            // 而「我用的 grant_type 不对」与「我少传了参数」对调用方要做的事不一样
            return oauthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "only " + GRANT_CLIENT_CREDENTIALS + " is supported");
        }

        String clientId = parameters.get("client_id");
        String clientSecret = parameters.get("client_secret");
        BasicCredentials basic = basicCredentials(request);
        if (basic != null) {
            // Basic 优先于请求体：规范要求「不得同时使用两种方式」，
            // 同时出现时以 HTTP 头为准比报错更宽容，也避免了两处凭据不一致时的歧义
            clientId = basic.clientId();
            clientSecret = basic.clientSecret();
        }

        ClientCredentialsService.IssuedToken issued = clientCredentials.issue(
                clientId, clientSecret, parameters.get("scope"), request.getRemoteAddr());
        return ResponseEntity.ok(new ConsoleDtos.TokenResponse(
                issued.accessToken(), issued.tokenType(), issued.expiresInSeconds(), issued.scope()));
    }

    /**
     * 把内部错误码翻译成 RFC 6749 的 {@code error} 取值。
     *
     * <p>只在异常没带协议级错误码时兜底（例如 JSON 体解析失败这种请求形状问题）。
     * 服务层认得出的原因会自带更精确的码——「scope 取值不支持」是 {@code invalid_scope}，
     * 不是 {@code invalid_request}；规范把这两者分成了不同的码，而状态码都是 400。
     *
     * <p>翻译而不是直接透传状态码：OAuth 客户端按 {@code error} 字符串分支，
     * 只在 {@code error_description} 里能看到细节。缺了这一步，
     * 客户端库会把「凭据不对」与「服务端 500」都当成「未知错误」。
     */
    private static String errorCodeOf(int status) {
        return switch (status) {
            case 401 -> "invalid_client";
            case 429 -> "temporarily_unavailable";
            default -> "invalid_request";
        };
    }

    private static ResponseEntity<Object> oauthError(HttpStatus status, String error, String description) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            // RFC 6749 第 5.2 节：401 的 invalid_client 应带上挑战头。
            // 通用的 OAuth 客户端库在拿到 401 而没有这个头时行为不一致
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return builder.body(new ConsoleDtos.TokenError(error, description));
    }

    /**
     * 读取请求参数。
     *
     * <p>按 Content-Type 分支，而不是两者都读：{@code getParameterMap()} 会消费请求体，
     * 先读 {@code getInputStream()} 再读参数表只会拿到空表（反之亦然）。
     */
    private static Map<String, String> parameters(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType != null
                && contentType.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE)) {
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return Map.of();
            }
            try {
                return Values.stringMap(Json.read(body));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("the request body is not valid JSON");
            }
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                parameters.put(name, values[0]);
            }
        });
        return parameters;
    }

    /** RFC 6749 第 2.3.1 节的 HTTP Basic 客户端凭据。 */
    private record BasicCredentials(String clientId, String clientSecret) {
    }

    private static BasicCredentials basicCredentials(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BASIC)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(
                    Base64.getDecoder().decode(header.substring(BASIC.length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return null;
        }
        // 规范要求这两个值在放进 Basic 之前先做 form-urlencode，
        // 因此这里要解回来——否则 clientSecret 里含 ':' 或非 ASCII 字符时永远对不上
        return new BasicCredentials(
                urlDecode(decoded.substring(0, colon)),
                urlDecode(decoded.substring(colon + 1)));
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
