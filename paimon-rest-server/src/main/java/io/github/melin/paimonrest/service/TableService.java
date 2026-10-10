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
import io.github.melin.paimonrest.support.Json;
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
import tools.jackson.databind.JsonNode;

/**
 * 表生命周期：创建 / 注册 / 查询 / 变更 / 重命名 / 删除，以及快照与 schema 版本管理。
 *
 * <p>schema 每次变更都会写入 {@code paimon_table_schema} 形成历史版本，
 * 表实体上的 {@code schemaId} 指向当前版本，因此 {@code rollback-schema} 可以直接改回历史版本。
 * 快照按 {@code snapshot_id} 单调递增存放，{@code rollback} 会丢弃目标之后的所有快照。
op *
 * <p><b>本类只维护「服务端数据库里的」元数据，写入 Paimon 仓库那一步交给
 * {@link TableMetadataService}。</b>两者必须成对发生，而且顺序不能反：先落库再落仓库，
 * 才谈得上「失败时数据库一致性有保证」——严格模式下仓库写入失败会让整个事务回滚
 * （{@code @Transactional} 的方法里抛异常），catalog 里不会留下一张没有 schema 文件的表。
 * 反过来先写仓库再落库，失败时留下的是仓库里的孤儿目录，而那种残留不会有任何接口
 * 能把它清理掉。为什么需要写仓库、写的是什么，见 {@link TableMetadataService} 的类注释。
 */
@Service
@RequiredArgsConstructor
public class TableService {

    private static final String OPTION_TABLE_TYPE = "type";
    private static final String DEFAULT_TABLE_TYPE = "PAIMON";

    /**
     * 客户端没送 {@code Snapshot.version} 时的兜底值：Paimon 当前的快照**文件格式版本**。
     *
     * <p>这个值是 Paimon 的 {@code Snapshot.CURRENT_VERSION}，它是 {@code protected}，取不到，
     * 只能写死。正常不会有走到这里的分支——客户端每个快照都会把 {@code version} 序列化出来；
     * 有兜底只是为了不让手工构造的请求体在库里留下 null。
     */
    private static final int SNAPSHOT_FORMAT_VERSION = 3;

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
    private final TableMetadataService tableMetadataService;

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

        // 落库之后再落仓库：schema/schema-0 是引擎后续写入该表的前提，
        // 缺失时的表现是引擎侧一句 Cannot get latest schema（见 TableMetadataService）
        tableMetadataService.materializeNewTable(table);
    }

    /**
     * 注册已存在于对象存储的表：只登记元数据位置，不接管数据。
     *
     * <p>刻意不调用 {@link TableMetadataService}：{@code register} 的语义就是
     * 「仓库里已经有一张表，本服务端只登记它的位置」。往它的目录里写 schema
     * 等于用这里的空 schema 覆盖那张表的元数据，是数据损坏，不是补全。
     * 实体上的 {@code external=true} 也把这一点带进了后续所有写入路径，
     * 因此 alter / rollback-schema 同样不会碰它。
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
        // 表目录是否一起删由 paimon.rest.table-metadata.purge-on-drop 决定，默认不删
        tableMetadataService.dropTableDirectory(table);
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

        // 数据库里的 schemaId 与仓库里的 schema-<n> 用同一个号，见 TableMetadataService
        tableMetadataService.materializeSchemaVersion(table, schemaId);
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

        // 回滚也是一次向前的新版本（内容取历史版本），仓库里同样写成 schema-<newSchemaId>
        tableMetadataService.materializeSchemaVersion(table, newSchemaId);
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 提交新的表快照。
     *
     * <p>{@code baseSnapshotUuid} 提供时作为乐观并发控制：与当前最新快照不一致即拒绝提交，
     * 返回 {@code success=false} 交由客户端重试。
     *
     * <p><b>入口收的是已解析的请求体，不是 DTO。</b>除了 DTO 里那些要落库的字段，
     * 还要把 {@code snapshot} 那段 JSON **原文**交给仓库物化——DTO 只覆盖规格建模过的字段，
     * 而客户端（{@code org.apache.paimon.Snapshot}）还会带 {@code properties}、
     * {@code operation}、{@code nextRowId} 等，按 DTO 重新拼一份就会丢。理由见
     * {@link TableMetadataService#materializeSnapshot}。
     */
    @Transactional
    public TableDtos.CommitTableResponse commit(String prefix, String databaseName, String tableName,
                                                JsonNode body) {
        return commit(prefix, databaseName, tableName,
                body == null ? null : Json.read(body.toString(), TableDtos.CommitTableRequest.class),
                body == null ? null : body.path("snapshot").toString());
    }

    /**
     * 兼容入口：调用方在 Java 里直接构造 DTO（服务端内部与测试）。
     *
     * <p>快照原文由 DTO 序列化而来，因此只含规格建模过的字段；真正走 HTTP 的那条路
     * （上面的重载）拿到的是客户端原文。两条路对数据库的影响完全一致，差别只在落到仓库里的
     * 快照文件有多完整。
     */
    @Transactional
    public TableDtos.CommitTableResponse commit(String prefix, String databaseName, String tableName,
                                                TableDtos.CommitTableRequest request) {
        return commit(prefix, databaseName, tableName, request,
                request == null || request.snapshot() == null ? null : Json.write(request.snapshot()));
    }

    private TableDtos.CommitTableResponse commit(String prefix, String databaseName, String tableName,
                                                 TableDtos.CommitTableRequest request, String snapshotDoc) {
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
        // 存的是快照**文件格式版本**（客户端恒送 3），不是自增序号：
        // 读路径按版本号取快照的是 getVersionSnapshot，而它按「快照 id」查，与这里无关。
        entity.setVersion(snapshot.version() != null ? snapshot.version() : SNAPSHOT_FORMAT_VERSION);
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

        // 先落库再落仓库，与 schema 同一顺序（理由见类注释）
        tableMetadataService.materializeSnapshot(table, snapshot.id(), snapshotDoc);
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

        // 仓库里那些目标之后的快照文件要一起消失，否则绕开服务端的读端会看到回滚前的表
        tableMetadataService.discardSnapshotsAfter(table, targetSnapshotId);
    }

    @Transactional(readOnly = true)
    public TableDtos.GetTableSnapshotResponse getTableSnapshot(String prefix, String databaseName, String tableName) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        TableSnapshotEntity entity = snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(table.getId())
                .orElseThrow(() -> ApiException.snapshotNotExist(0));
        return new TableDtos.GetTableSnapshotResponse(toTableSnapshot(entity));
    }

    /**
     * 按「版本」取快照，解析顺序与 Paimon REST 客户端一致
     * （{@code RESTApi.loadSnapshot(identifier, version)} 的 javadoc）：
     * {@code EARLIEST} 取最早的、{@code LATEST} 取最新的、**数字按快照 id 查**、其余当标签名。
     *
     * <p><b>数字指的是快照 id，不是 {@code Snapshot.version}。</b>后者是快照
     * **文件格式版本**，客户端每个快照都送同一个值（{@code Snapshot.CURRENT_VERSION}，当前是 3），
     * 把它当查询键会让所有快照命中同一行，于是 {@code SELECT ... VERSION AS OF 3} 返回
     * id 最小的那个快照——表现是「查出来的不是最新写入的数据，而快照数量又是对的」。
     * 快照 id 由客户端单调分配（{@code latestSnapshotId + 1}，从 1 起），因此按 id 查
     * 才与「第 n 个快照」这个直觉一致。
     */
    @Transactional(readOnly = true)
    public TableDtos.GetVersionSnapshotResponse getVersionSnapshot(String prefix, String databaseName,
                                                                  String tableName, String version) {
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        if (version == null || version.isBlank()) {
            throw ApiException.badRequest("Snapshot version is required");
        }
        String trimmed = version.trim();
        TableSnapshotEntity entity;
        if ("LATEST".equalsIgnoreCase(trimmed)) {
            entity = snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(table.getId())
                    .orElseThrow(() -> ApiException.snapshotNotExist(0));
        } else if ("EARLIEST".equalsIgnoreCase(trimmed)) {
            entity = snapshotRepository.findFirstByTableIdOrderBySnapshotIdAsc(table.getId())
                    .orElseThrow(() -> ApiException.snapshotNotExist(0));
        } else if (isDigits(trimmed)) {
            long snapshotId;
            try {
                snapshotId = Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Snapshot id is out of range: " + version);
            }
            entity = snapshotRepository.findByTableIdAndSnapshotId(table.getId(), snapshotId)
                    .orElseThrow(() -> ApiException.snapshotNotExist(snapshotId));
        } else {
            // 剩下的一律当标签名。标签本身就是「给某个快照起的名字」，取不到就是 404
            TagEntity tag = tagRepository.findByTableIdAndTagName(table.getId(), trimmed)
                    .orElseThrow(() -> ApiException.tagNotExist(trimmed));
            entity = snapshotRepository.findByTableIdAndSnapshotId(table.getId(), tag.getSnapshotId())
                    .orElseThrow(() -> ApiException.snapshotNotExist(tag.getSnapshotId()));
        }
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

    /**
     * 是否是一串十进制数字。
     *
     * <p>「像不像快照 id」用不着更聪明的判断：客户端的版本串只有
     * {@code LATEST} / {@code EARLIEST} / 数字 / 标签名四种，数字之外的一律按标签名去查，
     * 因此这里用 {@code Integer.parseInt} 那种「先试后catch」的写法反而更绕。
     */
    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

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
