package io.github.melin.paimonrest.dto;

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

    /**
     * `GET .../tables/{table}/token` 的响应：一份范围限定到单表的存储凭据与它的失效时刻。
     *
     * <p><b>为什么同一个时刻回两个名字。</b>规格把有效期字段定义为 `expiresAt`
     * （`spec/rest-catalog-open-api.yaml` 的 `GetTableDataTokenResponse`），
     * 而 Paimon 客户端读的是 `expiresAtMillis`
     * （`paimon-api` 的 `org.apache.paimon.rest.responses.GetTableTokenResponse`
     * 上写着 `@JsonProperty("expiresAtMillis")`）——两侧不自洽，只有后一个名字能被客户端认出来。
     *
     * <p>只回 `expiresAt` 的后果不是报错而是**静默劣化**：客户端把有效期读成 0
     * （反序列化时字段缺失即默认值），而 `RESTTokenFileIO` 判定「有效期不足一小时就刷新」，
     * 于是每一次文件操作前都会重新请求一次本接口——把凭据缓存彻底架空。
     *
     * <p>两个名字都回是为了同时伺候「照规格写的客户端」与「Paimon 自己的客户端」。
     * 多一个字段对前者无害：Paimon 客户端的 ObjectMapper 不拒绝未知字段。
     */
    public record GetTableDataTokenResponse(Map<String, String> token, Long expiresAt,
                                            Long expiresAtMillis) {
    }

    public record AuthTableQueryRequest(List<String> select) {
    }

    /** filter 为行过滤表达式，columnMasking 为列名到脱敏规则的映射。 */
    public record AuthTableQueryResponse(List<String> filter, Map<String, String> columnMasking) {
    }
}
