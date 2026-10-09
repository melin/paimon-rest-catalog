package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.repo.PrincipalRepository;
import io.github.melin.paimonrest.dto.ConsoleDtos;
import io.github.melin.paimonrest.service.ConsolePasswordService;
import io.github.melin.paimonrest.service.OidcService;
import io.github.melin.paimonrest.service.TokenAuthenticationService;
import io.github.melin.paimonrest.support.AuthenticatedToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的登录引导与登录端点。
 *
 * <p><b>非 Polaris 规格端点</b>，与 {@code /api/console/v1/meta} 同属控制台的扩展命名空间。
 *
 * <p>引导端点回答登录页在渲染前必须知道的四件事：服务端要不要令牌、控制台要不要登录、
 * 支持哪些登录方式、各自的端点在哪儿，以及「我带的这个令牌算不算数」。
 *
 * <p><b>为什么必须在鉴权之外。</b>登录页要先知道服务端支持哪种登录，才谈得上发起登录；
 * 把它放在鉴权之后会形成死循环。这也意味着它是对匿名访问者可见的，
 * 因此暴露的内容按「公开信息」筛过一遍，理由写在 {@link ConsoleDtos.ConsoleAuth} 上。
 *
 * <p><b>它替代了原来的 {@code /session} 端点。</b>那个端点返回的是「是否已登录」，
 * 而后端已经没有「会话」这个概念了——令牌由谁签发、什么时候过期，全在令牌自己身上。
 * 这里如实反映这一点：返回的是当前令牌的状态，而不是服务端记住的某个会话。
 */
@RestController
@RequestMapping("/api/console/v1")
@RequiredArgsConstructor
public class ConsoleAuthController {

    private static final String BEARER = "Bearer ";

    /**
     * 客户端凭据流程的令牌端点。
     *
     * <p>用 {@code /api/catalog/v1/oauth/tokens} 而不是本工程的
     * {@code /api/console/}：这是 Polaris 的令牌端点路径，也是 Polaris 官方 console
     * 的默认 {@code VITE_OAUTH_TOKEN_URL}。对齐它意味着按 OAuth 2.0 写的客户端
     * 与 Polaris 的 console 都能直接指向本服务端，不必为本工程单独适配。
     */
    private static final String TOKEN_ENDPOINT = "/api/catalog/v1/oauth/tokens";

    private final RestServerProperties properties;

    private final TokenAuthenticationService tokens;

    private final OidcService oidc;

    private final ConsolePasswordService passwords;

    private final PrincipalRepository principalRepository;

    /** {@code GET /api/console/v1/auth}：登录方式与当前令牌状态。 */
    @GetMapping("/auth")
    public ConsoleDtos.ConsoleAuth auth(HttpServletRequest request) {
        RestServerProperties.Auth auth = properties.getAuth();
        boolean authEnabled = auth.isEnabled();
        boolean consoleRequired = auth.getConsole().loginRequired(authEnabled);

        // OIDC 的端点要先拿到发现文档才谈得上展示。拿不到时就不把 OIDC 列为可用方式——
        // 列出来而点了没反应，比不列出来更难排查
        ConsoleDtos.OidcClientConfig oidcConfig = consoleRequired
                ? oidc.metadata().map(this::oidcClientConfig).orElse(null)
                : null;

        return new ConsoleDtos.ConsoleAuth(
                authEnabled,
                consoleRequired,
                methods(consoleRequired, oidcConfig != null),
                TOKEN_ENDPOINT,
                oidcConfig,
                session(request));
    }

    /**
     * {@code POST /api/console/v1/login}：用户名 + 密码换令牌。
     *
     * <p>与令牌端点一样必须在鉴权之外——它就是用来换令牌的。防爆破由
     * {@link ConsolePasswordService} 的失败限速承担。
     *
     * <p>请求体缺省允许为空（{@code required = false}）：这样「什么都不发」
     * 与「发了但没填」都能走到服务层，拿到同一条 400 提示，
     * 而不是在参数解析阶段抛出一个与业务无关的异常。
     */
    @PostMapping("/login")
    public ConsoleDtos.LoginResponse login(
            @RequestBody(required = false) ConsoleDtos.PasswordLoginRequest body,
            HttpServletRequest request) {
        ConsolePasswordService.IssuedLogin issued = passwords.login(
                body == null ? null : body.username(),
                body == null ? null : body.password(),
                request.getRemoteAddr());
        return new ConsoleDtos.LoginResponse(
                issued.accessToken(), issued.tokenType(), issued.expiresInSeconds(), issued.principal());
    }

    /**
     * 可用的登录方式。
     *
     * <p>控制台不需要登录时返回空列表：此时服务端根本不看令牌，列出登录方式只会让人以为
     * 「这个地址是有访问控制的」。前端据此直接放行并显示一条说明。
     *
     * <p><b>每一种方式都必须真的可用才列进来。</b>四种方式各有自己的判据：用户名密码看
     * {@code console.password.enabled}，客户端凭据看 {@code console.client-credentials.enabled}，
     * OIDC 看发现文档是否拿得到，静态令牌看 {@code auth.tokens} 里是否登记了令牌。
     * 列一个用不了的方式比不列更糟——使用者会照着填，然后收到一条与服务端配置无关的错误，
     * 排查方向从一开始就是错的。这与 OIDC 的处理是同一条理由。
     *
     * <p>因此本方法可能返回空列表，而 {@code consoleRequired=true} 仍然成立：
     * 服务端要求登录，却一个方式都没开。这是配置错误，不是「不需要登录」，
     * 前端会据此显示一条运维提示而不是放行。
     *
     * <p>顺序即展示顺序：用户名密码在最前（开箱即用，不需要先建主体），
     * 客户端凭据与 OIDC 居中，静态令牌永远最后——它是降级入口，不是推荐路径。
     */
    private List<String> methods(boolean consoleRequired, boolean oidcAvailable) {
        if (!consoleRequired) {
            return List.of();
        }
        List<String> methods = new ArrayList<>();
        if (passwords.enabled()) {
            methods.add(ConsoleDtos.AuthMethod.PASSWORD);
        }
        if (properties.getAuth().getConsole().getClientCredentials().isEnabled()) {
            methods.add(ConsoleDtos.AuthMethod.CLIENT_CREDENTIALS);
        }
        if (oidcAvailable) {
            methods.add(ConsoleDtos.AuthMethod.OIDC);
        }
        // 静态令牌不配就没有这一项：它与其他三种不同，没有 enabled 开关，
        // 「开启」的唯一表现就是 auth.tokens 里有值。空列表时列出一个降级入口，
        // 填进去的令牌必然被拒——那正好是「列了用不了的方式」的坏例子
        if (properties.getAuth().hasStaticTokens()) {
            methods.add(ConsoleDtos.AuthMethod.STATIC_TOKEN);
        }
        return methods;
    }

    private ConsoleDtos.OidcClientConfig oidcClientConfig(OidcService.Metadata metadata) {
        RestServerProperties.Auth.Oidc oidcProperties = properties.getAuth().getConsole().getOidc();
        return new ConsoleDtos.OidcClientConfig(
                metadata.issuer(),
                metadata.authorizationEndpoint(),
                metadata.tokenEndpoint(),
                oidcProperties.getClientId(),
                oidcProperties.getRedirectUri(),
                oidcProperties.getScope());
    }

    /**
     * 当前令牌的状态。
     *
     * <p>不通过拦截器拿主体：本端点不在鉴权范围内，拦截器不会运行，因此这里
     * 直接认证一次请求头里的令牌——这样「令牌无效」也只是一个字段为 false，
     * 而不是整个请求 401（登录页需要能读到认证方式，即使手上拿的是个废令牌）。
     */
    private ConsoleDtos.CurrentSession session(HttpServletRequest request) {
        String token = bearerToken(request);
        AuthenticatedToken identity = tokens.authenticate(token).orElse(null);
        if (identity == null) {
            return new ConsoleDtos.CurrentSession(false, null, null, null, false);
        }
        return new ConsoleDtos.CurrentSession(
                true,
                identity.principal(),
                tokens.sourceOf(token),
                identity.expiresAtMillis(),
                rotationRequired(identity.principal()));
    }

    /**
     * 主体是否被标记为「凭据待轮换」。
     *
     * <p>查不到主体时返回 false：OIDC 登录者可能只存在于 IdP 而不在
     * {@code paimon_principal} 表里，那种情况下没有「待轮换」可言。
     */
    private boolean rotationRequired(String principal) {
        return principalRepository.findByName(principal)
                .map(principalEntity -> principalEntity.isCredentialRotationRequired())
                .orElse(false);
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
