package io.github.melin.paimonrest.support;

/**
 * 一个已验证令牌的身份。
 *
 * <p>三种令牌来源（静态令牌、控制台自签发的访问令牌、外部 OIDC 令牌）验证完
 * 都归结成这一个形状：<b>主体名</b>与<b>失效时刻</b>。下游（授权判定、审计字段）
 * 只认主体名，不关心令牌是谁发的——这正是「认证方式可替换、授权逻辑只有一套」的实现方式。
 *
 * <p>{@code expiresAtMillis} 允许为 {@code null}，表示令牌没有失效时刻。
 * 只有静态令牌是这种情况：它写在服务端配置里，生命周期由运维决定，
 * 服务端无从得知。用 {@code 0} 表示「不过期」会让「已过期」的判断多一个特例，
 * 所以用可空引用把这两种情况分开。
 */
public record AuthenticatedToken(String principal, Long expiresAtMillis) {

    public AuthenticatedToken {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal must not be blank");
        }
    }
}
