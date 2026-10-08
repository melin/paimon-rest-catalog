package com.example.paimonrest.support;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code paimon.rest.credential-manager.type} 的取值：服务端是否下发数据访问凭据。
 *
 * <p>对应 Polaris 的 {@code polaris.credential-manager.type}——那里按
 * {@code @Identifier} 选择 {@code PolarisCredentialManager} 实现。
 * 本工程的可替换点没有 Polaris 那么多，因此只保留一个有实际部署意义的开关：
 * 引擎自带云凭据（实例角色、工作负载标识）时，服务端下发的那份凭据是多余的，
 * 甚至会与引擎自己的凭据链互相干扰；这类部署需要一个明确关掉下发的方式。
 */
public enum CredentialManagerType {

    /** 按 catalog 的存储配置签发凭据。 */
    DEFAULT("default"),

    /**
     * 不下发凭据。
     *
     * <p>读表接口走到凭据下发这一步时返回 501：这是「本部署未提供该能力」，
     * 与 403「你没有权限」是两件事，用同一个状态码会让调用方以为换个身份就能拿到。
     */
    NOOP("noop");

    private final String wireName;

    CredentialManagerType(String wireName) {
        this.wireName = wireName;
    }

    /** 配置中的取值。 */
    public String wireName() {
        return wireName;
    }

    /** 按配置取值解析，未知取值返回空。 */
    public static Optional<CredentialManagerType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.wireName.equalsIgnoreCase(value.trim()))
                .findFirst();
    }

    /**
     * 按配置取值解析，未知取值直接抛异常。
     *
     * <p>刻意不退回 {@link #DEFAULT}：把一个拼错的 {@code noop} 当成默认值处理，
     * 会让本该停止下发凭据的部署继续发凭据——方向恰好是错误的那个。
     */
    public static CredentialManagerType require(String value) {
        return parse(value).orElseThrow(() -> new IllegalStateException(
                "paimon.rest.credential-manager.type=" + value + " is not a known credential manager; expected one of "
                        + Arrays.stream(values()).map(CredentialManagerType::wireName)
                        .collect(Collectors.joining(", "))));
    }
}
