package io.github.melin.paimonrest.dto;

import java.util.Map;

/**
 * 通用 DTO：错误响应、资源标识、配置发现。
 */
public final class CommonDtos {

    private CommonDtos() {
    }

    /**
     * 错误响应。{@code resourceType} / {@code resourceName} 可空，
     * 取值见 {@code io.github.melin.paimonrest.support.ResourceType}。
     */
    public record ErrorResponse(String message, String resourceType, String resourceName, Integer code) {
    }

    /** 由 database 名称与对象名称组成的全局标识。 */
    public record Identifier(String database, String object) {
    }

    /**
     * {@code GET /v1/config} 响应。
     *
     * <p>客户端合并顺序为 defaults → client properties → overrides，后者优先。
     */
    public record ConfigResponse(Map<String, String> defaults, Map<String, String> overrides) {
    }
}
