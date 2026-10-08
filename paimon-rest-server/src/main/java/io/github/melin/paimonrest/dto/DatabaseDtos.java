package io.github.melin.paimonrest.dto;

import java.util.List;
import java.util.Map;

/**
 * database（命名空间）相关 DTO。
 */
public final class DatabaseDtos {

    private DatabaseDtos() {
    }

    public record CreateDatabaseRequest(String name, Map<String, String> options) {
    }

    public record CreateDatabaseResponse(String name, Map<String, String> options) {
    }

    public record GetDatabaseResponse(
            String id,
            String name,
            String location,
            Map<String, String> options,
            String owner,
            Long createdAt,
            String createdBy,
            Long updatedAt,
            String updatedBy) {
    }

    public record ListDatabasesResponse(List<String> databases, String nextPageToken) {
    }

    /** removals 删除属性键，updates 覆盖属性。 */
    public record AlterDatabaseRequest(List<String> removals, Map<String, String> updates) {
    }

    /** removed 为实际删除的键，missing 为不存在而跳过的键。 */
    public record AlterDatabaseResponse(List<String> removed, List<String> updated, List<String> missing) {
    }
}
