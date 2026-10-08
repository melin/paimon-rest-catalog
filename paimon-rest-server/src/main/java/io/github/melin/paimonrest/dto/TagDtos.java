package io.github.melin.paimonrest.dto;

import java.util.List;

/**
 * 标签（tag）相关 DTO。
 */
public final class TagDtos {

    private TagDtos() {
    }

    /** snapshotId 为空时指向最新快照；timeRetained 形如 {@code "1d"}、{@code "12h"}、{@code "30m"}。 */
    public record CreateTagRequest(
            String tagName,
            Long snapshotId,
            String timeRetained,
            Boolean ignoreIfExists) {
    }

    public record GetTagResponse(
            String tagName,
            TableDtos.Snapshot snapshot,
            Long tagCreateTime,
            String tagTimeRetained) {
    }

    public record ListTagsResponse(List<String> tags, String nextPageToken) {
    }

    public record TagInfo(
            String tagName,
            Long snapshotId,
            Long tagCreateTime,
            String tagTimeRetained) {
    }
}
