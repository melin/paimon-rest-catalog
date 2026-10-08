package io.github.melin.paimonrest.dto;

import java.util.List;
import java.util.Map;

/**
 * SQL 视图相关 DTO。变更项按 {@code action} 分派：
 * {@code setOption} / {@code removeOption} / {@code updateComment} /
 * {@code addDialect} / {@code updateDialect} / {@code dropDialect}。
 */
public final class ViewDtos {

    private ViewDtos() {
    }

    public record CreateViewRequest(CommonDtos.Identifier identifier, TypeDtos.ViewSchema schema) {
    }

    public record AlterViewRequest(List<Map<String, Object>> changes) {
    }

    public record GetViewResponse(
            String id,
            String name,
            TypeDtos.ViewSchema schema,
            String owner,
            Long createdAt,
            String createdBy,
            Long updatedAt,
            String updatedBy) {
    }

    public record ListViewsResponse(List<String> views, String nextPageToken) {
    }

    public record ListViewDetailsResponse(List<GetViewResponse> viewDetails, String nextPageToken) {
    }

    public record ListViewsGloballyResponse(List<CommonDtos.Identifier> views, String nextPageToken) {
    }
}
