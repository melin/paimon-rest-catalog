package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.dto.BranchDtos;
import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.dto.ConsumerDtos;
import io.github.melin.paimonrest.dto.DatabaseDtos;
import io.github.melin.paimonrest.dto.FunctionDtos;
import io.github.melin.paimonrest.dto.PartitionDtos;
import io.github.melin.paimonrest.dto.SemanticViewDtos;
import io.github.melin.paimonrest.dto.TableDtos;
import io.github.melin.paimonrest.dto.TagDtos;
import io.github.melin.paimonrest.dto.TypeDtos;
import io.github.melin.paimonrest.dto.ViewDtos;
import io.github.melin.paimonrest.service.BranchService;
import io.github.melin.paimonrest.service.CatalogService;
import io.github.melin.paimonrest.service.ConsumerService;
import io.github.melin.paimonrest.service.CredentialService;
import io.github.melin.paimonrest.service.DatabaseService;
import io.github.melin.paimonrest.service.FunctionService;
import io.github.melin.paimonrest.service.PartitionService;
import io.github.melin.paimonrest.service.SemanticViewService;
import io.github.melin.paimonrest.service.TableService;
import io.github.melin.paimonrest.service.TagService;
import io.github.melin.paimonrest.service.ViewService;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.ResourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 覆盖 REST Catalog 主要资源生命周期的集成测试。
 *
 * <p>直接调用服务层，验证 JPA 持久化、schema 变更语义、快照提交与回滚、
 * 以及各类错误码是否符合规格。
 *
 * <p>{@code test} profile 把数据源从默认的 MySQL 换成内存 H2，
 * 使本类不依赖本机是否有 MySQL 实例。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaimonRestCatalogApiTests {

    private static final String PREFIX = "paimon";

    @Autowired
    private CatalogService catalogService;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private PartitionService partitionService;
    @Autowired
    private TagService tagService;
    @Autowired
    private BranchService branchService;
    @Autowired
    private ViewService viewService;
    @Autowired
    private FunctionService functionService;
    @Autowired
    private SemanticViewService semanticViewService;
    @Autowired
    private ConsumerService consumerService;
    @Autowired
    private CredentialService credentialService;

    @Test
    void configExposesPrefixForClientBootstrap() {
        CommonDtos.ConfigResponse config = catalogService.config("paimon");
        assertEquals("paimon", config.defaults().get("prefix"));
        assertNotNull(config.defaults().get("warehouse"));
        assertNotNull(config.overrides());
    }

    @Test
    void tableLifecycleCoversSchemaChangesSnapshotsAndTags() {
        String database = "it_lifecycle";
        createDatabase(database);

        // 建表
        TableDtos.CreateTableRequest createRequest = new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema());
        tableService.create(PREFIX, database, createRequest);

        TableDtos.GetTableResponse created = tableService.get(PREFIX, database, "orders");
        assertEquals("orders", created.name());
        assertEquals(0L, created.schemaId());
        assertEquals(3, created.schema().getFields().size());
        assertEquals(List.of("dt"), created.schema().getPartitionKeys());
        assertTrue(created.path().endsWith("orders"), "表路径应包含表名: " + created.path());
        assertFalse(created.isExternal());

        // 表已存在 -> 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> tableService.create(PREFIX, database, createRequest));
        assertEquals(409, conflict.getStatus());
        assertEquals(ResourceType.TABLE, conflict.getResourceType());

        // schema 变更：加列 / 改名 / 改类型 / 改可空性 / 加属性
        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change("action", "addColumn", "fieldNames", List.of("email"), "dataType", "VARCHAR(100)"));
        changes.add(change("action", "setOption", "key", "bucket", "value", "4"));
        changes.add(change("action", "renameColumn", "fieldNames", List.of("name"), "newName", "full_name"));
        changes.add(change("action", "updateColumnType", "fieldNames", List.of("id"),
                "newDataType", "BIGINT", "keepNullability", true));
        changes.add(change("action", "updateColumnNullability", "fieldNames", List.of("email"),
                "newNullability", false));
        changes.add(change("action", "updateColumnComment", "fieldNames", List.of("full_name"),
                "newComment", "客户全名"));
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(changes));

        TableDtos.GetTableResponse altered = tableService.get(PREFIX, database, "orders");
        assertEquals(1L, altered.schemaId(), "一次变更请求只产生一个新 schema 版本");
        assertEquals(4, altered.schema().getFields().size());
        assertEquals("4", altered.schema().getOptions().get("bucket"));

        assertEquals("BIGINT NOT NULL", typeOf(altered.schema(), "id"),
                "keepNullability=true 时应保留原有的 NOT NULL 标记");
        assertEquals("VARCHAR(100) NOT NULL", typeOf(altered.schema(), "email"),
                "updateColumnNullability=false 应写入 NOT NULL 标记");
        TypeDtos.DataField fullName = altered.schema().getFields().stream()
                .filter(field -> "full_name".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("VARCHAR(20)", fullName.getType(), "只改名不应改变类型");
        assertEquals("客户全名", fullName.getDescription());

        // keepNullability=false 时丢弃原有的 NOT NULL 标记
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "updateColumnType", "fieldNames", List.of("id"),
                        "newDataType", "BIGINT", "keepNullability", false))));
        TableDtos.GetTableResponse retyped = tableService.get(PREFIX, database, "orders");
        assertEquals(2L, retyped.schemaId());
        assertEquals("BIGINT", typeOf(retyped.schema(), "id"));

        // 复杂类型：ARRAY 列正常加入，可空性变更则被拒绝
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("tags"),
                        "dataType", Map.of("type", "ARRAY", "element", "VARCHAR(10)")))));
        TypeDtos.DataField tags = fieldOf(tableService.get(PREFIX, database, "orders").schema(), "tags");
        assertTrue(tags.getType() instanceof Map, "复杂类型以 object 形式回传");
        assertEquals("ARRAY", ((Map<?, ?>) tags.getType()).get("type").toString());

        assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "updateColumnNullability",
                        "fieldNames", List.of("tags"), "newNullability", false)))));

        // 嵌套字段：ROW 类型下的子字段按路径新增
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("addr"),
                        "dataType", Map.of("type", "ROW", "fields", List.of())),
                change("action", "addColumn", "fieldNames", List.of("addr", "city"),
                        "dataType", "VARCHAR(50)"))));
        Object rowFields = ((Map<?, ?>) fieldOf(
                tableService.get(PREFIX, database, "orders").schema(), "addr").getType()).get("fields");
        assertTrue(rowFields instanceof List, "ROW 类型的子字段应为列表");
        assertEquals(1, ((List<?>) rowFields).size());
        assertEquals("city", ((TypeDtos.DataField) ((List<?>) rowFields).get(0)).getName());

        // 路径不存在或列重复时返回 400
        assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "addColumn",
                        "fieldNames", List.of("nope", "child"), "dataType", "INT")))));
        assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "addColumn",
                        "fieldNames", List.of("email"), "dataType", "INT")))));

        // 未支持的 action -> 400
        ApiException badAction = assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "explode",
                        "fieldNames", List.of("email"))))));
        assertEquals(400, badAction.getStatus());

        // 提交快照
        TableDtos.Snapshot snapshot = new TableDtos.Snapshot(1, "uuid-1", 1L, 1L,
                "base.manifest", "delta.manifest", null, "index.manifest",
                "tester", "commit-1", "APPEND", System.currentTimeMillis(),
                Map.of("bucket-0", 12L), 100L, 100L, 0L, 5L, null);
        TableDtos.CommitTableResponse committed = tableService.commit(PREFIX, database, "orders",
                new TableDtos.CommitTableRequest(created.id(), null, snapshot, null));
        assertTrue(committed.success());

        TableDtos.TableSnapshot latest = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(1L, latest.snapshot().id());
        assertEquals(100L, latest.recordCount());
        assertEquals(1, tableService.listSnapshots(PREFIX, database, "orders", null, null).snapshots().size());

        // 乐观并发：baseSnapshotUuid 不匹配时提交被拒绝
        TableDtos.CommitTableResponse stale = tableService.commit(PREFIX, database, "orders",
                new TableDtos.CommitTableRequest(created.id(), "uuid-stale", snapshot, null));
        assertFalse(stale.success());

        // 打标签
        tagService.create(PREFIX, database, "orders",
                new TagDtos.CreateTagRequest("v1", null, "7d", false));
        TagDtos.GetTagResponse tag = tagService.get(PREFIX, database, "orders", "v1");
        assertEquals(1L, tag.snapshot().id());
        assertEquals("7d", tag.tagTimeRetained());
        assertEquals(List.of("v1"), tagService.list(PREFIX, database, "orders", null, null, null).tags());

        // 分支
        branchService.create(PREFIX, database, "orders", new BranchDtos.CreateBranchRequest("dev", "v1"));
        assertEquals(List.of("dev"), branchService.list(PREFIX, database, "orders").branches());
        branchService.rename(PREFIX, database, "orders", "dev", new BranchDtos.RenameBranchRequest("dev2"));
        assertEquals(List.of("dev2"), branchService.list(PREFIX, database, "orders").branches());
        assertThrows(ApiException.class, () -> branchService.create(PREFIX, database, "orders",
                new BranchDtos.CreateBranchRequest("dev2", null)));

        // 第二个快照后回滚到第一个
        TableDtos.Snapshot second = new TableDtos.Snapshot(2, "uuid-2", 2L, 1L,
                "base2.manifest", "delta2.manifest", null, "index2.manifest",
                "tester", "commit-2", "APPEND", System.currentTimeMillis(),
                Map.of(), 150L, 50L, 0L, 9L, null);
        tableService.commit(PREFIX, database, "orders",
                new TableDtos.CommitTableRequest(created.id(), "uuid-1", second, null));
        assertEquals(2, tableService.listSnapshots(PREFIX, database, "orders", null, null).snapshots().size());

        tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "snapshot", "snapshotId", 1L), null));
        List<TableDtos.Snapshot> afterRollback =
                tableService.listSnapshots(PREFIX, database, "orders", null, null).snapshots();
        assertEquals(1, afterRollback.size(), "回滚应丢弃目标之后的快照");
        assertEquals(100L, tableService.getTableSnapshot(PREFIX, database, "orders").snapshot().recordCount());

        // 按标签回滚
        tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "tag", "tagName", "v1"), null));
        assertThrows(ApiException.class, () -> tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "tag", "tagName", "missing"), null)));

        // schema 回滚到版本 0
        long schemaIdBeforeRollback = tableService.get(PREFIX, database, "orders").schemaId();
        tableService.rollbackSchema(PREFIX, database, "orders", new TableDtos.RollbackSchemaRequest(0L));
        TableDtos.GetTableResponse rolledBack = tableService.get(PREFIX, database, "orders");
        assertEquals(schemaIdBeforeRollback + 1, rolledBack.schemaId(),
                "回滚 schema 本身也是一次变更，版本号继续递增");
        assertEquals(3, rolledBack.schema().getFields().size());
        assertEquals("name", rolledBack.schema().getFields().get(1).getName());
        assertNotNull(rolledBack.updatedAt());
    }

    @Test
    void partitionLifecycleAndStatisticsAccumulate() {
        String database = "it_partition";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "events"), ordersSchema()));

        Map<String, String> january = Map.of("dt", "2024-01-01");
        Map<String, String> february = Map.of("dt", "2024-02-01");

        PartitionDtos.CreatePartitionsResponse created = partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january, february), false, null, null, null));
        assertEquals(2, created.created().size());
        assertTrue(created.existed().isEmpty());

        // 重复创建 -> existed；ignoreIfExists=false 时 409
        PartitionDtos.CreatePartitionsResponse again = partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, null, null, null));
        assertEquals(1, again.existed().size());
        ApiException conflict = assertThrows(ApiException.class, () -> partitionService.create(PREFIX, database,
                "events", new PartitionDtos.CreatePartitionsRequest(List.of(january), false, null, null, null)));
        assertEquals(409, conflict.getStatus());

        // 统计累加
        PartitionDtos.PartitionStatistics stats = new PartitionDtos.PartitionStatistics(
                Map.of("dt", "2024-01-01"), 10L, 1024L, 2L, 1000L, 4);
        partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, List.of(stats), true, null));
        PartitionDtos.Partition partition = partitionService.list(PREFIX, database, "events", null, null, null)
                .partitions().stream()
                .filter(item -> "2024-01-01".equals(String.valueOf(item.spec().get("dt"))))
                .findFirst()
                .orElseThrow();
        assertEquals(10L, partition.recordCount());
        assertEquals(4, partition.totalBuckets());
        assertEquals(Boolean.FALSE, partition.done());

        // replaceStatistics=true 覆盖而非累加
        partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, List.of(stats), true, null));
        assertEquals(10L, firstPartition(january).recordCount(), "replaceStatistics=true 应覆盖存储值");

        // 负值表示未测量，不覆盖已有值
        PartitionDtos.PartitionStatistics unknown = new PartitionDtos.PartitionStatistics(
                Map.of("dt", "2024-01-01"), -1L, -1L, -1L, -1L, null);
        partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, List.of(unknown), true, null));
        assertEquals(10L, firstPartition(january).recordCount(), "未测量的字段应保留原值");

        // 按名称查询
        PartitionDtos.ListPartitionsResponse byNames = partitionService.listByNames(PREFIX, database, "events",
                new PartitionDtos.ListPartitionsByNamesRequest(List.of(february)));
        assertEquals(1, byNames.partitions().size());

        // mark done
        partitionService.markDone(PREFIX, database, "events",
                new PartitionDtos.MarkDonePartitionsRequest(List.of(Map.of("dt", "2024-01-01"))));
        assertEquals(Boolean.TRUE, firstPartition(january).done());
        ApiException missing = assertThrows(ApiException.class, () -> partitionService.markDone(PREFIX, database,
                "events", new PartitionDtos.MarkDonePartitionsRequest(List.of(Map.of("dt", "2099-01-01")))));
        assertEquals(ResourceType.PARTITION, missing.getResourceType());

        // drop：存在与不存在分别进 dropped / missing
        PartitionDtos.DropPartitionsResponse dropped = partitionService.drop(PREFIX, database, "events",
                new PartitionDtos.DropPartitionsRequest(List.of(january, Map.of("dt", "2099-01-01")), true));
        assertEquals(1, dropped.dropped().size());
        assertEquals(1, dropped.missing().size());
        assertEquals(1, partitionService.list(PREFIX, database, "events", null, null, null).partitions().size());

        // 名称模式匹配
        PartitionDtos.ListPartitionsResponse matched = partitionService.list(PREFIX, database, "events",
                null, null, "dt=2024-02%");
        assertEquals(1, matched.partitions().size());
        assertEquals(0, partitionService.list(PREFIX, database, "events", null, null, "dt=2099%").partitions().size());
    }

    @Test
    void viewsFunctionsConsumersAndCredentials() {
        String database = "it_objects";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema()));

        // 视图
        TypeDtos.ViewSchema viewSchema = new TypeDtos.ViewSchema();
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(0);
        field.setName("id");
        field.setType("BIGINT");
        viewSchema.getFields().add(field);
        viewSchema.setQuery("SELECT id FROM orders");
        viewService.create(PREFIX, database, new ViewDtos.CreateViewRequest(
                new CommonDtos.Identifier(database, "orders_view"), viewSchema));

        viewService.alter(PREFIX, database, "orders_view", new ViewDtos.AlterViewRequest(List.of(
                change("action", "addDialect", "dialect", "spark", "query", "SELECT id FROM spark.orders"),
                change("action", "setOption", "key", "comment", "value", "视图"))));

        ViewDtos.GetViewResponse view = viewService.get(PREFIX, database, "orders_view");
        assertEquals("SELECT id FROM orders", view.schema().getQuery());
        assertEquals("SELECT id FROM spark.orders", view.schema().getDialects().get("spark"));
        assertEquals(List.of("orders_view"), viewService.list(PREFIX, database, null, null, null).views());

        // 视图重命名
        viewService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(database, "orders_view"),
                new CommonDtos.Identifier(database, "orders_view_v2")));
        assertEquals(List.of("orders_view_v2"), viewService.list(PREFIX, database, null, null, null).views());

        // 函数
        Map<String, Object> definitions = new LinkedHashMap<>();
        definitions.put("default", Map.of("type", "sql", "definition", "a + b"));
        functionService.create(PREFIX, database, new FunctionDtos.CreateFunctionRequest(
                "add_one", List.of(field(0, "a", "INT")), List.of(field(0, "result", "INT")),
                true, definitions, "加法", Map.of("owner", "paimon")));
        functionService.alter(PREFIX, database, "add_one", new FunctionDtos.AlterFunctionRequest(List.of(
                change("action", "addDefinition", "name", "spark",
                        "definition", Map.of("type", "lambda", "language", "java")),
                change("action", "updateComment", "comment", "整数加法"))));

        FunctionDtos.GetFunctionResponse function = functionService.get(PREFIX, database, "add_one");
        assertEquals("整数加法", function.comment());
        assertEquals(2, function.definitions().size());
        assertTrue(function.definitions().containsKey("spark"));
        assertEquals(1, function.inputParams().size());

        ApiException dup = assertThrows(ApiException.class, () -> functionService.create(PREFIX, database,
                new FunctionDtos.CreateFunctionRequest("add_one", null, null, null, null, null, null)));
        assertEquals(409, dup.getStatus());
        assertThrows(ApiException.class, () -> functionService.alter(PREFIX, database, "add_one",
                new FunctionDtos.AlterFunctionRequest(List.of(change("action", "dropDefinition", "name", "nope")))));

        // 消费者
        consumerService.reset(PREFIX, database, "orders",
                new ConsumerDtos.ResetConsumerRequest("c1", 5L));
        assertEquals(1, consumerService.list(PREFIX, database, "orders", null, null).consumers().size());
        consumerService.reset(PREFIX, database, "orders",
                new ConsumerDtos.ResetConsumerRequest("c1", null));
        assertTrue(consumerService.list(PREFIX, database, "orders", null, null).consumers().isEmpty());

        // 凭证下发：范围限定到单表，带过期时间
        TableDtos.GetTableDataTokenResponse token = credentialService.token(PREFIX, database, "orders");
        assertNotNull(token.token().get("securityToken"));
        assertTrue(token.token().get("accessKeyId").startsWith("PAIMON-"));
        assertTrue(token.expiresAt() > System.currentTimeMillis());

        // 查询鉴权：请求未知列 -> 403
        TableDtos.AuthTableQueryResponse auth = credentialService.auth(PREFIX, database, "orders",
                new TableDtos.AuthTableQueryRequest(List.of("id", "dt")));
        assertTrue(auth.filter().isEmpty());
        ApiException forbidden = assertThrows(ApiException.class, () -> credentialService.auth(PREFIX, database,
                "orders", new TableDtos.AuthTableQueryRequest(List.of("nope"))));
        assertEquals(403, forbidden.getStatus());
    }

    @Test
    void semanticViewsAreUpsertedAndSizeLimited() {
        String database = "it_semantic";
        createDatabase(database);

        semanticViewService.upsert(PREFIX, database, "sales_model", new SemanticViewDtos.UpsertSemanticViewRequest(
                new SemanticViewDtos.SemanticViewDefinition("databricks-yaml", "version: 1\nmodel: sales")));
        assertEquals("version: 1\nmodel: sales",
                semanticViewService.get(PREFIX, database, "sales_model").definition().content());

        // 同名再提交即覆盖
        semanticViewService.upsert(PREFIX, database, "sales_model", new SemanticViewDtos.UpsertSemanticViewRequest(
                new SemanticViewDtos.SemanticViewDefinition("ossie-yaml", "model: sales_v2")));
        assertEquals("ossie-yaml", semanticViewService.get(PREFIX, database, "sales_model").definition().format());
        assertEquals(List.of("sales_model"),
                semanticViewService.list(PREFIX, database, null, null).semanticViews());

        // 超过 1 MiB -> 413，且不落库
        String tooLarge = "x".repeat(1048577);
        ApiException tooLargeError = assertThrows(ApiException.class, () -> semanticViewService.upsert(
                PREFIX, database, "huge", new SemanticViewDtos.UpsertSemanticViewRequest(
                        new SemanticViewDtos.SemanticViewDefinition("ossie-yaml", tooLarge))));
        assertEquals(413, tooLargeError.getStatus());
        assertThrows(ApiException.class, () -> semanticViewService.get(PREFIX, database, "huge"));

        ApiException blank = assertThrows(ApiException.class, () -> semanticViewService.upsert(
                PREFIX, database, "blank", new SemanticViewDtos.UpsertSemanticViewRequest(
                        new SemanticViewDtos.SemanticViewDefinition("ossie-yaml", " "))));
        assertEquals(400, blank.getStatus());

        semanticViewService.drop(PREFIX, database, "sales_model");
        assertThrows(ApiException.class, () -> semanticViewService.get(PREFIX, database, "sales_model"));
    }

    @Test
    void missingResourcesReturn404WithResourceType() {
        String database = "it_missing";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "t"), ordersSchema()));

        ApiException databaseMissing = assertThrows(ApiException.class,
                () -> databaseService.get(PREFIX, "no_such_database"));
        assertEquals(404, databaseMissing.getStatus());
        assertEquals(ResourceType.DATABASE, databaseMissing.getResourceType());

        ApiException tableMissing = assertThrows(ApiException.class,
                () -> tableService.get(PREFIX, database, "no_such_table"));
        assertEquals(ResourceType.TABLE, tableMissing.getResourceType());

        ApiException tagMissing = assertThrows(ApiException.class,
                () -> tagService.get(PREFIX, database, "t", "no_such_tag"));
        assertEquals(ResourceType.TAG, tagMissing.getResourceType());

        ApiException branchMissing = assertThrows(ApiException.class,
                () -> branchService.drop(PREFIX, database, "t", "no_such_branch"));
        assertEquals(ResourceType.BRANCH, branchMissing.getResourceType());

        ApiException viewMissing = assertThrows(ApiException.class,
                () -> viewService.get(PREFIX, database, "no_such_view"));
        assertEquals(ResourceType.VIEW, viewMissing.getResourceType());

        ApiException functionMissing = assertThrows(ApiException.class,
                () -> functionService.get(PREFIX, database, "no_such_function"));
        assertEquals(ResourceType.FUNCTION, functionMissing.getResourceType());

        ApiException semanticViewMissing = assertThrows(ApiException.class,
                () -> semanticViewService.get(PREFIX, database, "no_such_semantic_view"));
        assertEquals(ResourceType.SEMANTIC_VIEW, semanticViewMissing.getResourceType());

        ApiException tableIdMissing = assertThrows(ApiException.class,
                () -> tableService.getById(PREFIX, "no-such-table-id"));
        assertEquals(ResourceType.TABLE, tableIdMissing.getResourceType());
    }

    @Test
    void databaseAlterReportsRemovedUpdatedAndMissingKeys() {
        String database = "it_alter";
        DatabaseDtos.CreateDatabaseRequest request =
                new DatabaseDtos.CreateDatabaseRequest(database, Map.of("owner", "paimon", "keep", "1"));
        databaseService.create(PREFIX, request);

        DatabaseDtos.AlterDatabaseResponse response = databaseService.alter(PREFIX, database,
                new DatabaseDtos.AlterDatabaseRequest(List.of("keep", "absent"), Map.of("tier", "gold")));
        assertEquals(List.of("keep"), response.removed());
        assertEquals(List.of("tier"), response.updated());
        assertEquals(List.of("absent"), response.missing());

        DatabaseDtos.GetDatabaseResponse loaded = databaseService.get(PREFIX, database);
        assertNull(loaded.options().get("keep"));
        assertEquals("gold", loaded.options().get("tier"));
        assertEquals("paimon", loaded.options().get("owner"));
    }

    @Test
    void databaseDropCascadesToContainedObjects() {
        String database = "it_cascade";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "t1"), ordersSchema()));

        databaseService.drop(PREFIX, database);
        ApiException missing = assertThrows(ApiException.class, () -> databaseService.get(PREFIX, database));
        assertEquals(404, missing.getStatus());
        assertFalse(databaseService.list(PREFIX, null, null).databases().contains(database));
    }

    /**
     * 表重命名只改目录里的名称，不动数据位置（README 第 9 节第 5 条，与 Paimon 服务端一致）。
     *
     * <p>控制台的「重命名」按钮走的就是这条路，因此这里把它依赖的性质全部钉住：
     * 新名字可读、旧名字消失、{@code id} 与 {@code path} 不变（不是建了新表，也没搬动数据）。
     */
    @Test
    void renamingATableChangesTheNameButNotTheDataLocation() {
        String database = "it_rename";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "before"), ordersSchema()));
        TableDtos.GetTableResponse before = tableService.get(PREFIX, database, "before");
        assertTrue(before.path().endsWith("before"), "表路径应包含表名: " + before.path());

        tableService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(database, "before"),
                new CommonDtos.Identifier(database, "after")));

        TableDtos.GetTableResponse after = tableService.get(PREFIX, database, "after");
        assertEquals("after", after.name());
        assertEquals(before.id(), after.id(), "重命名是改名字，不是建新表");
        assertEquals(before.path(), after.path(), "重命名不该搬动数据");
        assertEquals(List.of("after"), tableService.list(PREFIX, database, null, null, null).tables());
        assertEquals(404, assertThrows(ApiException.class,
                () -> tableService.get(PREFIX, database, "before")).getStatus());

        // 目标名被占用 -> 409。「改成与原名相同」也落在这一条上，所以控制台在本地就拦掉了，
        // 不发这个注定失败的请求——否则用户点一下确定就收到一个「表已存在」的红报错。
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "taken"), ordersSchema()));
        ApiException conflict = assertThrows(ApiException.class, () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(new CommonDtos.Identifier(database, "after"),
                        new CommonDtos.Identifier(database, "taken"))));
        assertEquals(409, conflict.getStatus());
        assertEquals(ResourceType.TABLE, conflict.getResourceType());
        assertEquals("taken", conflict.getResourceName());
        assertEquals(409, assertThrows(ApiException.class, () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(new CommonDtos.Identifier(database, "after"),
                        new CommonDtos.Identifier(database, "after")))).getStatus());

        // 源表不存在 -> 404；请求不完整 -> 400
        assertEquals(404, assertThrows(ApiException.class, () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(new CommonDtos.Identifier(database, "ghost"),
                        new CommonDtos.Identifier(database, "somewhere")))).getStatus());
        assertEquals(400, assertThrows(ApiException.class,
                () -> tableService.rename(PREFIX, null)).getStatus());

        // 跨库重命名：规格允许（控制台没有暴露这个入口），数据位置同样不动
        createDatabase("it_rename_target");
        tableService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(database, "after"),
                new CommonDtos.Identifier("it_rename_target", "moved")));
        TableDtos.GetTableResponse moved = tableService.get(PREFIX, "it_rename_target", "moved");
        assertEquals("moved", moved.name());
        assertEquals(before.path(), moved.path(), "跨库重命名同样不搬数据");
    }

    // ------------------------------------------------------------------ 辅助

    private void createDatabase(String name) {
        if (!databaseService.list(PREFIX, null, null).databases().contains(name)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(name, Map.of("owner", "paimon")));
        }
    }

    private PartitionDtos.Partition firstPartition(Map<String, String> spec) {
        String dt = spec.get("dt");
        return partitionService.list(PREFIX, "it_partition", "events", null, null, null).partitions().stream()
                .filter(item -> dt.equals(String.valueOf(item.spec().get("dt"))))
                .findFirst()
                .orElseThrow();
    }

    private static TypeDtos.DataField fieldOf(TypeDtos.Schema schema, String name) {
        return schema.getFields().stream()
                .filter(field -> name.equals(field.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static String typeOf(TypeDtos.Schema schema, String name) {
        return String.valueOf(fieldOf(schema, name).getType());
    }

    /** 以 {@code key, value, key, value, ...} 形式构造变更项；首个键必须是 {@code action}。 */
    private static Map<String, Object> change(Object... keyValues) {
        Map<String, Object> change = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            change.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return change;
    }

    private static TypeDtos.Schema ordersSchema() {
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getFields().add(field(1, "name", "VARCHAR(20)"));
        schema.getFields().add(field(2, "dt", "VARCHAR(10)"));
        schema.getPrimaryKeys().add("id");
        schema.getPartitionKeys().add("dt");
        schema.getOptions().put("bucket", "1");
        schema.setComment("orders");
        return schema;
    }

    private static TypeDtos.DataField field(int id, String name, String type) {
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        return field;
    }
}
