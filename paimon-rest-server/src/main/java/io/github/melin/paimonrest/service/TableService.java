package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.entity.TableEntity;
import io.github.melin.paimonrest.domain.entity.TableSchemaVersionEntity;
import io.github.melin.paimonrest.domain.entity.TableSnapshotEntity;
import io.github.melin.paimonrest.domain.entity.TagEntity;
import io.github.melin.paimonrest.domain.repo.ConsumerRepository;
import io.github.melin.paimonrest.domain.repo.DatabaseRepository;
import io.github.melin.paimonrest.domain.repo.PartitionRepository;
import io.github.melin.paimonrest.domain.repo.TableRepository;
import io.github.melin.paimonrest.domain.repo.TableSchemaVersionRepository;
import io.github.melin.paimonrest.domain.repo.TableSnapshotRepository;
import io.github.melin.paimonrest.domain.repo.TagRepository;
import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.dto.TableDtos;
import io.github.melin.paimonrest.dto.TypeDtos;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Codecs;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.Paths;
import io.github.melin.paimonrest.support.Patterns;
import io.github.melin.paimonrest.support.ResourceType;
import io.github.melin.paimonrest.support.SchemaSupport;
import io.github.melin.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表生命周期：创建 / 注册 / 查询 / 变更 / 重命名 / 删除，以及快照与 schema 版本管理。
 *
 * <p>schema 每次变更都会写入 {@code paimon_table_schema} 形成历史版本，
 * 表实体上的 {@code schemaId} 指向当前版本，因此 {@code rollback-schema} 可以直接改回历史版本。
 * 快照按 {@code snapshot_id} 单调递增存放，{@code rollback} 会丢弃目标之后的所有快照。
 */
@Service
@RequiredArgsConstructor
public class TableService {

    private static final String OPTION_TABLE_TYPE = "type";
    private static final String DEFAULT_TABLE_TYPE = "PAIMON";

    private final TableRepository tableRepository;
    private final DatabaseRepository databaseRepository;
    private final TableSchemaVersionRepository schemaVersionRepository;
    private final TableSnapshotRepository snapshotRepository;
    private final TagRepository tagRepository;
    private final PartitionRepository partitionRepository;
    private final ConsumerRepository consumerRepository;
    private final TableLookup tableLookup;
    private final SchemaChangeService schemaChangeService;
    private final PartitionService partitionService;
    private final CatalogService catalogService;
    private final RestServerProperties properties;

    // ------------------------------------------------------------------ 列举

    @Transactional(readOnly = true)
    public TableDtos.ListTablesResponse list(String prefix, String databaseName,
                                             Integer maxResults, String pageToken, String tableNamePattern) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<String> names = new ArrayList<>();
        for (TableEntity table : tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (Patterns.matches(table.getName(), tableNamePattern)) {
                names.add(table.getName());
            }
        }
        Paging.Slice<String> slice = Paging.slice(names, Paging.offset(pageToken), pageSize(maxResults));
        return new TableDtos.ListTablesResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public TableDtos.ListTableDetailsResponse listDetails(String prefix, String databaseName,
                                                          Integer maxResults, String pageToken,
                                                          String tableNamePattern, String tableType) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<TableDtos.GetTableResponse> details = new ArrayList<>();
        for (TableEntity table : tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (!Patterns.matches(table.getName(), tableNamePattern)) {
                continue;
            }
            if (tableType != null && !tableType.isBlank() && !tableType.equalsIgnoreCase(table.getTableType())) {
                continue;
            }
            details.add(toResponse(table, database.getName()));
        }
        Paging.Slice<TableDtos.GetTableResponse> slice =
                Paging.slice(details, Paging.offset(pageToken), pageSize(maxResults));
        return new TableDtos.ListTableDetailsResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public TableDtos.ListTablesGloballyResponse listGlobally(String prefix, String databaseNamePattern,
                                                             String tableNamePattern, Integer maxResults,
                                                             String pageToken) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        List<CommonDtos.Identifier> identifiers = new ArrayList<>();
        for (TableEntity table : tableRepository.findAllByCatalogIdOrderByDatabaseIdAscNameAsc(catalog.getId())) {
            String databaseName = databaseNameOf(table.getDatabaseId());
            if (!Patterns.matches(databaseName, databaseNamePattern)
                    || !Patterns.matches(table.getName(), tableNamePattern)) {
                continue;
            }
            identifiers.add(new CommonDtos.Identifier(databaseName, table.getName()));
        }
        Paging.Slice<CommonDtos.Identifier> slice =
                Paging.slice(identifiers, Paging.offset(pageToken), pageSize(maxResults));
        return new TableDtos.ListTablesGloballyResponse(slice.items(), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ 单表读写

    @Transactional(readOnly = true)
    public TableDtos.GetTableResponse get(String prefix, String databaseName, String tableName) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        return toResponse(table, database.getName());
    }

    @Transactional(readOnly = true)
    public TableDtos.GetTableResponse getById(String prefix, String tableId) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        TableEntity table = tableRepository.findById(tableId)
                .filter(candidate -> catalog.getId().equals(candidate.getCatalogId()))
                .orElseThrow(() -> ApiException.tableNotExist(tableId));
        return toResponse(table, databaseNameOf(table.getDatabaseId()));
    }

    @Transactional
    public void create(String prefix, String databaseName, TableDtos.CreateTableRequest request) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        String tableName = requireObjectName(request == null ? null : request.identifier(), databaseName);
        if (tableRepository.existsByDatabaseIdAndName(database.getId(), tableName)) {
            throw ApiException.tableAlreadyExist(tableName);
        }
        TypeDtos.Schema schema = request.schema() == null ? new TypeDtos.Schema() : request.schema();
        SchemaSupport.normalize(schema);

        CatalogEntity catalog = catalogService.resolve(prefix);
        TableEntity table = new TableEntity();
        table.setId(Paging.newId());
        table.setCatalogId(database.getCatalogId());
        table.setDatabaseId(database.getId());
        table.setName(tableName);
        table.setPath(Paths.tableLocation(properties.getPathTemplate(),
                catalog.getWarehouse(), databaseName, tableName));
        table.setExternal(false);
        table.setSchemaId(0);
        table.setSchemaDoc(Codecs.write(schema));
        table.setTableType(tableType(schema));
        table.markCreated(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        saveSchemaVersion(table, 0, schema);
    }

    /**
     * 注册已存在于对象存储的表：只登记元数据位置，不接管数据。
     */
    @Transactional
    public void register(String prefix, String databaseName, TableDtos.RegisterTableRequest request) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        String tableName = requireObjectName(request == null ? null : request.identifier(), databaseName);
        String path = request == null ? null : request.path();
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("Table path must not be blank");
        }
        if (tableRepository.existsByDatabaseIdAndName(database.getId(), tableName)) {
            throw ApiException.tableAlreadyExist(tableName);
        }
        TypeDtos.Schema schema = new TypeDtos.Schema();
        TableEntity table = new TableEntity();
        table.setId(Paging.newId());
        table.setCatalogId(database.getCatalogId());
        table.setDatabaseId(database.getId());
        table.setName(tableName);
        table.setPath(path);
        table.setExternal(true);
        table.setSchemaId(0);
        table.setSchemaDoc(Codecs.write(schema));
        table.setTableType(DEFAULT_TABLE_TYPE);
        table.markCreated(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        saveSchemaVersion(table, 0, schema);
    }

    @Transactional
    public void drop(String prefix, String databaseName, String tableName) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        partitionRepository.deleteByTableId(table.getId());
        consumerRepository.deleteByTableId(table.getId());
        tableRepository.delete(table);
    }

    @Transactional
    public void rename(String prefix, TableDtos.RenameTableRequest request) {
        if (request == null || request.source() == null || request.destination() == null) {
            throw ApiException.badRequest("rename requires source and destination identifiers");
        }
        String sourceDatabase = requireIdentifierPart(request.source().database(), "source.database");
        String destinationDatabase = requireIdentifierPart(request.destination().database(), "destination.database");
        String sourceTable = requireIdentifierPart(request.source().object(), "source.object");
        String destinationTable = requireIdentifierPart(request.destination().object(), "destination.object");

        DatabaseEntity targetDatabase = tableLookup.requireDatabase(prefix, destinationDatabase);
        TableEntity table = tableLookup.requireTable(prefix, sourceDatabase, sourceTable);
        if (tableRepository.existsByDatabaseIdAndName(targetDatabase.getId(), destinationTable)) {
            throw ApiException.tableAlreadyExist(destinationTable);
        }
        // 只改目录中的名称，数据位置保持不变
        table.setDatabaseId(targetDatabase.getId());
        table.setName(destinationTable);
        table.touch(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
    }

    /**
     * 应用 schema 变更。
     *
     * <p>每次变更生成新的 schema 版本，历史版本保留以便回滚。
     * 变更列表为空时不做任何改动，也不产生新版本。
     */
    @Transactional
    public void alter(String prefix, String databaseName, String tableName, TableDtos.AlterTableRequest request) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        List<Map<String, Object>> changes = request == null ? null : request.changes();
        if (changes == null || changes.isEmpty()) {
            return;
        }
        TypeDtos.Schema schema = Codecs.readSchema(table.getSchemaDoc());
        schemaChangeService.apply(schema, changes);

        long schemaId = table.getSchemaId() + 1;
        table.setSchemaId(schemaId);
        table.setSchemaDoc(Codecs.write(schema));
        table.setTableType(tableType(schema));
        table.touch(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        saveSchemaVersion(table, schemaId, schema);
    }

    /**
     * 回滚 schema 到历史版本。
     *
     * <p>与 Paimon 一致，回滚本身也是一次 schema 变更：把历史内容写入新的 schemaId，
     * 而不是把版本号退回去，这样快照与 schema 的对应关系始终单调。
     */
    @Transactional
    public void rollbackSchema(String prefix, String databaseName, String tableName,
                               TableDtos.RollbackSchemaRequest request) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        Long schemaId = request == null ? null : request.schemaId();
        if (schemaId == null) {
            throw ApiException.badRequest("schemaId is required");
        }
        TableSchemaVersionEntity version = schemaVersionRepository
                .findByTableIdAndSchemaId(table.getId(), schemaId)
                .orElseThrow(() -> new ApiException(404, ResourceType.TABLE, table.getName(),
                        "The given schema does not exist"));
        long newSchemaId = table.getSchemaId() + 1;
        table.setSchemaId(newSchemaId);
        table.setSchemaDoc(version.getSchemaDoc());
        table.touch(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        saveSchemaVersion(table, newSchemaId, Codecs.readSchema(version.getSchemaDoc()));
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 提交新的表快照。
     *
     * <p>{@code baseSnapshotUuid} 提供时作为乐观并发控制：与当前最新快照不一致即拒绝提交，
     * 返回 {@code success=false} 交由客户端重试。
     */
    @Transactional
    public TableDtos.CommitTableResponse commit(String prefix, String databaseName, String tableName,
                                                TableDtos.CommitTableRequest request) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        if (request == null || request.snapshot() == null) {
            throw ApiException.badRequest("commit requires a snapshot");
        }
        if (request.tableId() != null && !request.tableId().equals(table.getId())) {
            throw ApiException.badRequest("tableId does not match the addressed table");
        }
        TableDtos.Snapshot snapshot = request.snapshot();
        if (snapshot.id() == null) {
            throw ApiException.badRequest("snapshot.id is required");
        }
        if (request.baseSnapshotUuid() != null
                && table.getLatestSnapshotUuid() != null
                && !request.baseSnapshotUuid().equals(table.getLatestSnapshotUuid())) {
            return new TableDtos.CommitTableResponse(false);
        }

        TableSnapshotEntity entity = snapshotRepository
                .findByTableIdAndSnapshotId(table.getId(), snapshot.id())
                .orElseGet(TableSnapshotEntity::new);
        entity.setId(entity.getId() == null ? Paging.newId() : entity.getId());
        entity.setTableId(table.getId());
        entity.setSnapshotId(snapshot.id());
        entity.setVersion(snapshot.version() != null ? snapshot.version() : nextVersion(table.getId()));
        entity.setUuid(snapshot.uuid());
        entity.setSchemaId(snapshot.schemaId() != null ? snapshot.schemaId() : table.getSchemaId());
        entity.setBaseManifestList(snapshot.baseManifestList());
        entity.setDeltaManifestList(snapshot.deltaManifestList());
        entity.setChangelogManifestList(snapshot.changelogManifestList());
        entity.setIndexManifest(snapshot.indexManifest());
        entity.setCommitUser(snapshot.commitUser());
        entity.setCommitIdentifier(snapshot.commitIdentifier());
        entity.setCommitKind(snapshot.commitKind());
        entity.setTimeMillis(snapshot.timeMillis() != null ? snapshot.timeMillis() : RequestContext.now());
        entity.setLogOffsets(snapshot.logOffsets() == null ? new LinkedHashMap<>() : snapshot.logOffsets());
        entity.setTotalRecordCount(snapshot.totalRecordCount());
        entity.setDeltaRecordCount(snapshot.deltaRecordCount());
        entity.setChangelogRecordCount(snapshot.changelogRecordCount());
        entity.setWatermark(snapshot.watermark());
        entity.setStatistics(snapshot.statistics());
        entity.setRecordCount(snapshot.totalRecordCount());
        snapshotRepository.save(entity);

        partitionService.applyCommitStatistics(table.getId(), request.statistics());

        table.setLatestSnapshotId(snapshot.id());
        table.setLatestSnapshotUuid(snapshot.uuid());
        table.touch(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        return new TableDtos.CommitTableResponse(true);
    }

    /**
     * 回滚到指定快照。目标之后的快照记录会被丢弃。
     */
    @Transactional
    public void rollback(String prefix, String databaseName, String tableName,
                         TableDtos.RollbackTableRequest request) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        Long targetSnapshotId = resolveRollbackTarget(table, request);
        if (targetSnapshotId == null) {
            throw ApiException.badRequest("rollback requires a snapshotId, a tagName or fromSnapshot");
        }
        TableSnapshotEntity target = snapshotRepository
                .findByTableIdAndSnapshotId(table.getId(), targetSnapshotId)
                .orElseThrow(() -> ApiException.snapshotNotExist(targetSnapshotId));

        snapshotRepository.deleteByTableIdAndSnapshotIdGreaterThan(table.getId(), targetSnapshotId);
        table.setLatestSnapshotId(target.getSnapshotId());
        table.setLatestSnapshotUuid(target.getUuid());
        table.touch(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
    }

    @Transactional(readOnly = true)
    public TableDtos.GetTableSnapshotResponse getTableSnapshot(String prefix, String databaseName, String tableName) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        TableSnapshotEntity entity = snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(table.getId())
                .orElseThrow(() -> ApiException.snapshotNotExist(0));
        return new TableDtos.GetTableSnapshotResponse(toTableSnapshot(entity));
    }

    @Transactional(readOnly = true)
    public TableDtos.GetVersionSnapshotResponse getVersionSnapshot(String prefix, String databaseName,
                                                                  String tableName, String version) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        int parsed;
        try {
            parsed = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Snapshot version must be an integer: " + version);
        }
        TableSnapshotEntity entity = snapshotRepository.findByTableIdAndVersion(table.getId(), parsed)
                .orElseThrow(() -> ApiException.snapshotNotExist(parsed));
        return new TableDtos.GetVersionSnapshotResponse(toSnapshot(entity));
    }

    @Transactional(readOnly = true)
    public TableDtos.ListSnapshotsResponse listSnapshots(String prefix, String databaseName, String tableName,
                                                         Integer maxResults, String pageToken) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        List<TableDtos.Snapshot> snapshots = new ArrayList<>();
        for (TableSnapshotEntity entity : snapshotRepository.findAllByTableIdOrderBySnapshotIdDesc(table.getId())) {
            snapshots.add(toSnapshot(entity));
        }
        Paging.Slice<TableDtos.Snapshot> slice =
                Paging.slice(snapshots, Paging.offset(pageToken), pageSize(maxResults));
        return new TableDtos.ListSnapshotsResponse(slice.items(), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ 辅助

    private Long resolveRollbackTarget(TableEntity table, TableDtos.RollbackTableRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, Object> instant = request.instant();
        if (instant != null && !instant.isEmpty()) {
            String type = Values.string(instant.get("type"), "snapshot");
            if ("tag".equalsIgnoreCase(type)) {
                String tagName = Values.string(instant.get("tagName"));
                if (tagName == null) {
                    throw ApiException.badRequest("Tag instant requires tagName");
                }
                TagEntity tag = tagRepository.findByTableIdAndTagName(table.getId(), tagName)
                        .orElseThrow(() -> ApiException.tagNotExist(tagName));
                return tag.getSnapshotId();
            }
            return Values.longValue(instant.get("snapshotId"));
        }
        return request.fromSnapshot();
    }

    private int nextVersion(String tableId) {
        return snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(tableId)
                .map(entity -> (entity.getVersion() == null ? 0 : entity.getVersion()) + 1)
                .orElse(1);
    }

    private void saveSchemaVersion(TableEntity table, long schemaId, TypeDtos.Schema schema) {
        TableSchemaVersionEntity version = new TableSchemaVersionEntity();
        version.setId(Paging.newId());
        version.setTableId(table.getId());
        version.setSchemaId(schemaId);
        version.setSchemaDoc(Codecs.write(schema));
        version.setCreatedAt(RequestContext.now());
        schemaVersionRepository.save(version);
    }

    /** 由 database 实体 id 取回其名称。 */
    private String databaseNameOf(String databaseId) {
        return databaseRepository.findById(databaseId).map(DatabaseEntity::getName).orElse("");
    }

    private String requireObjectName(CommonDtos.Identifier identifier, String databaseName) {
        if (identifier == null) {
            throw ApiException.badRequest("identifier is required");
        }
        if (identifier.database() != null && !identifier.database().equals(databaseName)) {
            throw ApiException.badRequest("identifier.database does not match the database in the path");
        }
        String object = identifier.object();
        if (object == null || object.isBlank()) {
            throw ApiException.badRequest("identifier.object must not be blank");
        }
        return object;
    }

    private String requireIdentifierPart(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " must not be blank");
        }
        return value;
    }

    private String tableType(TypeDtos.Schema schema) {
        String type = schema.getOptions().get(OPTION_TABLE_TYPE);
        return type == null || type.isBlank() ? DEFAULT_TABLE_TYPE : type;
    }

    private int pageSize(Integer maxResults) {
        return Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize());
    }

    /** 把表实体映射为规格的 {@code GetTableResponse}。 */
    public TableDtos.GetTableResponse toResponse(TableEntity table, String databaseName) {
        return new TableDtos.GetTableResponse(
                table.getId(),
                databaseName,
                table.getName(),
                table.getPath(),
                table.isExternal(),
                table.getSchemaId(),
                Codecs.readSchema(table.getSchemaDoc()),
                table.getOwner(),
                table.getCreatedAt(),
                table.getCreatedBy(),
                table.getUpdatedAt(),
                table.getUpdatedBy());
    }

    private TableDtos.Snapshot toSnapshot(TableSnapshotEntity entity) {
        return new TableDtos.Snapshot(
                entity.getVersion(),
                entity.getUuid(),
                entity.getSnapshotId(),
                entity.getSchemaId(),
                entity.getBaseManifestList(),
                entity.getDeltaManifestList(),
                entity.getChangelogManifestList(),
                entity.getIndexManifest(),
                entity.getCommitUser(),
                entity.getCommitIdentifier(),
                entity.getCommitKind(),
                entity.getTimeMillis(),
                entity.getLogOffsets() == null ? Map.of() : new LinkedHashMap<>(entity.getLogOffsets()),
                entity.getTotalRecordCount(),
                entity.getDeltaRecordCount(),
                entity.getChangelogRecordCount(),
                entity.getWatermark(),
                entity.getStatistics());
    }

    /** 取指定快照的 DTO，不存在时抛出 404 SNAPSHOT。 */
    @Transactional(readOnly = true)
    public TableDtos.Snapshot snapshot(String tableId, long snapshotId) {
        return snapshotRepository.findByTableIdAndSnapshotId(tableId, snapshotId)
                .map(this::toSnapshot)
                .orElseThrow(() -> ApiException.snapshotNotExist(snapshotId));
    }

    private TableDtos.TableSnapshot toTableSnapshot(TableSnapshotEntity entity) {        return new TableDtos.TableSnapshot(
                toSnapshot(entity),
                entity.getRecordCount(),
                entity.getFileSizeInBytes(),
                entity.getFileCount(),
                entity.getLastFileCreationTime());
    }
}
