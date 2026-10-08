package com.example.paimonrest.dto;

import java.util.List;
import java.util.Map;

/**
 * 表相关 DTO，含 schema 变更、提交、回滚与快照。
 *
 * <p>变更请求统一声明为 {@code List<Map<String,Object>>}：规格中的 {@code SchemaChange}
 * 是以 {@code action} 为判别字段的多态联合，服务端按 {@code action} 分派处理，
 * 避免把多态解析逻辑绑死在具体 JSON 库上。
 */
public final class TableDtos {

    private TableDtos() {
    }

    public record CreateTableRequest(CommonDtos.Identifier identifier, TypeDtos.Schema schema) {
    }

    public record RegisterTableRequest(CommonDtos.Identifier identifier, String path) {
    }

    public record GetTableResponse(
            String id,
            String database,
            String name,
            String path,
            Boolean isExternal,
            Long schemaId,
            TypeDtos.Schema schema,
            String owner,
            Long createdAt,
            String createdBy,
            Long updatedAt,
            String updatedBy) {
    }

    /** Alter table 请求体；变更项按 {@code action} 分派。 */
    public record AlterTableRequest(List<Map<String, Object>> changes) {
    }

    public record RenameTableRequest(CommonDtos.Identifier source, CommonDtos.Identifier destination) {
    }

    public record ListTablesResponse(List<String> tables, String nextPageToken) {
    }

    public record ListTableDetailsResponse(List<GetTableResponse> tableDetails, String nextPageToken) {
    }

    public record ListTablesGloballyResponse(List<CommonDtos.Identifier> tables, String nextPageToken) {
    }

    public record CommitTableRequest(
            String tableId,
            String baseSnapshotUuid,
            Snapshot snapshot,
            List<PartitionDtos.PartitionStatistics> statistics) {
    }

    public record CommitTableResponse(Boolean success) {
    }

    public record RollbackTableRequest(Map<String, Object> instant, Long fromSnapshot) {
    }

    public record RollbackSchemaRequest(Long schemaId) {
    }

    public record Snapshot(
            Integer version,
            String uuid,
            Long id,
            Long schemaId,
            String baseManifestList,
            String deltaManifestList,
            String changelogManifestList,
            String indexManifest,
            String commitUser,
            String commitIdentifier,
            String commitKind,
            Long timeMillis,
            Map<String, Long> logOffsets,
            Long totalRecordCount,
            Long deltaRecordCount,
            Long changelogRecordCount,
            Long watermark,
            String statistics) {
    }

    /** 最新快照及其聚合统计。 */
    public record TableSnapshot(
            Snapshot snapshot,
            Long recordCount,
            Long fileSizeInBytes,
            Long fileCount,
            Long lastFileCreationTime) {
    }

    public record GetTableSnapshotResponse(TableSnapshot snapshot) {
    }

    public record GetVersionSnapshotResponse(Snapshot snapshot) {
    }

    public record ListSnapshotsResponse(List<Snapshot> snapshots, String nextPageToken) {
    }

    public record GetTableDataTokenResponse(Map<String, String> token, Long expiresAt) {
    }

    public record AuthTableQueryRequest(List<String> select) {
    }

    /** filter 为行过滤表达式，columnMasking 为列名到脱敏规则的映射。 */
    public record AuthTableQueryResponse(List<String> filter, Map<String, String> columnMasking) {
    }
}
