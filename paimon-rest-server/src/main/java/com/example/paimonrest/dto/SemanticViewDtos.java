package com.example.paimonrest.dto;

import java.util.List;

/**
 * 语义视图（Semantic View）相关 DTO。
 *
 * <p>定义内容整篇保存（不做解析），上限 1 MiB UTF-8 字节。
 */
public final class SemanticViewDtos {

    private SemanticViewDtos() {
    }

    /** format 取值如 {@code databricks-yaml}、{@code snowflake-yaml}、{@code ossie-yaml}。 */
    public record SemanticViewDefinition(String format, String content) {
    }

    public record UpsertSemanticViewRequest(SemanticViewDefinition definition) {
    }

    public record GetSemanticViewResponse(String name, SemanticViewDefinition definition) {
    }

    public record ListSemanticViewsResponse(List<String> semanticViews, String nextPageToken) {
    }
}
