package io.github.melin.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.TableEntity;
import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.dto.DatabaseDtos;
import io.github.melin.paimonrest.dto.TableDtos;
import io.github.melin.paimonrest.dto.TagDtos;
import io.github.melin.paimonrest.dto.TypeDtos;
import io.github.melin.paimonrest.service.CatalogService;
import io.github.melin.paimonrest.service.DatabaseService;
import io.github.melin.paimonrest.service.TableMetadataService;
import io.github.melin.paimonrest.service.TableService;
import io.github.melin.paimonrest.service.TagService;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Codecs;
import io.github.melin.paimonrest.support.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.paimon.Snapshot;
import org.apache.paimon.schema.TableSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * 表 schema 与快照物化到 Paimon 仓库的集成测试。
 *
 * <p>钉住的是「服务端数据库里的元数据」与「仓库里的文件」必须逐号对应：建表写
 * {@code schema/schema-0}，每次 schema 变更或回滚追加一个新的号，内容与
 * {@code GET /tables/{t}} 回给客户端的 schema 一致；提交快照写
 * {@code snapshot/snapshot-<id>} 并把 {@code snapshot/LATEST} 指过去，回滚则把
 * 目标之后的快照文件删掉。
 *
 * <p><b>为什么这些断言值得存在。</b>缺了 schema 文件，引擎建完表能读 schema，
 * 却在第一次写入时抛 {@code Cannot get latest schema for table <表名>}；
 * 缺了快照文件，用 REST catalog 读一切正常（读路径也走服务端），
 * 绕开服务端直接看仓库却是「一张没有快照的表」。两个缺口在「只有元数据」的视角下
 * 都完全不可见，只能靠直接看仓库目录发现。用本地文件系统（{@code file://}，Paimon 内置
 * {@code LocalFileIO}）就能断言到位，不需要对象存储、插件与网络。
 *
 * <p>每个用例独占一个库名（带随机后缀）：本类会真的往磁盘写文件，
 * 而 {@code purge-on-drop} 默认关闭，固定库名会让上一次运行的残留影响下一次。
 */
@SpringBootTest
@ActiveProfiles("test")
class TableMetadataMaterializationTests {

    private static final String PREFIX = "paimon";

    @Autowired
    private CatalogService catalogService;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableMetadataService tableMetadataService;
    @Autowired
    private TagService tagService;
    @Autowired
    private RestServerProperties properties;

    private String database;

    @BeforeEach
    void createDatabase() {
        database = "tm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(database, Map.of("owner", "paimon")));
    }

    /**
     * 清掉本用例建的表与库，并删掉它写下的目录。
     *
     * <p>删目录这一步是测试自己的清理，不是被测行为：{@code purge-on-drop} 默认关闭
     * （删数据不可逆，默认值不给它会删数据的取值），所以 {@code drop} 之后
     * 目录仍在原处。测试跑在临时仓库里，但也没必要给下一次留下残骸。
     */
    @AfterEach
    void dropDatabase() {
        if (database == null || database.isBlank()) {
            return;
        }
        try {
            for (String table : tableService.list(PREFIX, database, null, null, null).tables()) {
                try {
                    tableService.drop(PREFIX, database, table);
                } catch (RuntimeException ignored) {
                    // 用例自己已经把表删掉了，这里只为兜底
                }
            }
            databaseService.drop(PREFIX, database);
        } catch (RuntimeException ignored) {
            // 库可能已被用例删掉
        }
        deleteRecursively(databaseDirectory());
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    void creatingAnInternalTableWritesSchemaZero() throws IOException {
        assertTrue(catalogWarehouse().contains("paimon-rest-server-tests"),
                "测试仓库应由 application-test.yml 指向临时目录，而不是 application.yml 的默认仓库"
                        + "（那个路径正是文档里手工起实例、跑 Spark 示例时用的）：" + catalogWarehouse());

        String path = createTable("orders");

        Path schemaFile = schemaFile(path, 0L);
        assertTrue(Files.exists(schemaFile), "建表后应写出 schema-0，实际未见: " + schemaFile);

        TableSchema written = TableSchema.fromJson(Files.readString(schemaFile));
        assertEquals(0L, written.id(), "首个 schema 的 id 必须是 0");
        assertEquals(schemaFields(), written.fieldNames());
        assertEquals(List.of("dt"), written.partitionKeys());
        assertEquals(List.of("id"), written.primaryKeys());
        assertEquals("1", written.options().get("bucket"));
        assertEquals("orders", written.comment());
        assertFalse(written.fields().get(0).type().isNullable(),
                "Paimon 会把主键列标成 NOT NULL，写出的 schema 必须已经过这一步归一化");

        // 只写 schema-0：不存在的号不能凭空冒出来
        assertFalse(Files.exists(schemaFile(path, 1L)));
    }

    @Test
    void alteringSchemaAppendsTheNextSchemaFile() throws IOException {
        String path = createTable("orders");
        TableDtos.GetTableResponse created = tableService.get(PREFIX, database, "orders");

        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("email"), "dataType", "VARCHAR(100)"),
                change("action", "setOption", "key", "bucket", "value", "4"))));

        long schemaId = tableService.get(PREFIX, database, "orders").schemaId();
        assertEquals(created.schemaId() + 1, schemaId);

        Path schemaFile = schemaFile(path, schemaId);
        assertTrue(Files.exists(schemaFile), "改表后应写出 schema-" + schemaId);

        TableSchema written = TableSchema.fromJson(Files.readString(schemaFile));
        assertEquals(schemaId, written.id(), "文件里的 schemaId 必须与数据库里的版本号一致");
        assertEquals(4, written.fields().size(), "新加的一列要出现在文件里");
        assertEquals("4", written.options().get("bucket"), "选项变更同样要落到文件里");
        assertTrue(written.fieldNames().contains("email"));

        // 改一步只追加一号，不应跳到下一号
        assertFalse(Files.exists(schemaFile(path, schemaId + 1)));
    }

    @Test
    void rollingBackSchemaWritesTheOldContentUnderANewId() throws IOException {
        String path = createTable("orders");
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("email"), "dataType", "VARCHAR(100)"))));
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("note"), "dataType", "VARCHAR(50)"))));

        long beforeRollback = tableService.get(PREFIX, database, "orders").schemaId();
        tableService.rollbackSchema(PREFIX, database, "orders", new TableDtos.RollbackSchemaRequest(0L));

        long schemaId = tableService.get(PREFIX, database, "orders").schemaId();
        assertEquals(beforeRollback + 1, schemaId, "回滚本身也是一次向前的新版本");

        Path schemaFile = schemaFile(path, schemaId);
        assertTrue(Files.exists(schemaFile), "回滚后应写出 schema-" + schemaId);

        TableSchema written = TableSchema.fromJson(Files.readString(schemaFile));
        assertEquals(schemaId, written.id());
        assertEquals(schemaFields(), written.fieldNames(),
                "回滚后文件里的内容应是版本 0 的那份，而不是刚被改过的那份");

        // 历史版本仍在，说明回滚是追加而非就地改写
        assertTrue(Files.exists(schemaFile(path, 1L)));
        assertTrue(Files.exists(schemaFile(path, 2L)));
    }

    @Test
    void registeredExternalTableIsNotTouched() {
        String externalPath = catalogWarehouse() + "/external.db/legacy_" + database;
        tableService.register(PREFIX, database, new TableDtos.RegisterTableRequest(
                new CommonDtos.Identifier(database, "legacy"), externalPath));

        assertFalse(Files.exists(tablePath(externalPath)),
                "register 只登记位置：不接管数据的表，服务端不该在它的目录里写任何东西");
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 提交快照后仓库里要出现 {@code snapshot/snapshot-<id>} 与 {@code snapshot/LATEST}，
     * 且文件内容必须是客户端发来的**原文**。
     *
     * <p>原文这条是重点：规格里的 {@code Snapshot} 只建模了一部分字段，客户端送的是完整的
     * Paimon {@code Snapshot}（带 {@code properties} / {@code nextRowId} 等）。若按 DTO 重新拼
     * 一份落盘，这些字段会静默消失——而缺 {@code properties}（序列号水位）不会报错，
     * 只会让读端重算序列号，表现为去重与变更日志语义悄悄改变。
     */
    @Test
    void committingASnapshotWritesTheSnapshotFileAndLatestHint() throws IOException {
        String path = createTable("orders");

        tableService.commit(PREFIX, database, "orders", commitBody(1L, "uuid-1"));
        tableService.commit(PREFIX, database, "orders", commitBody(2L, "uuid-2"));

        Path first = snapshotFile(path, 1L);
        Path second = snapshotFile(path, 2L);
        assertTrue(Files.exists(first), "提交快照后应写出 snapshot-1，实际未见: " + first);
        assertTrue(Files.exists(second), "第二个快照应写出 snapshot-2");

        String written = Files.readString(second);
        assertTrue(written.contains("\"properties\""),
                "落到仓库的必须是客户端原文；按 DTO 重新拼一份会把规格没建模的字段丢掉。实际内容: " + written);
        assertTrue(written.contains("paimon.max.sequence.number"));
        assertEquals(2L, Snapshot.fromJson(written).id(),
                "写下去的东西至少要能被 Paimon 自己的模型反解出来");
        assertEquals("2", Files.readString(latestHintFile(path)).trim(),
                "LATEST 提示要指向最新快照，落后的话绕开服务端的读端会读到旧数据");

        // 首个快照不得被覆盖成第二份内容
        assertEquals(1L, Snapshot.fromJson(Files.readString(first)).id());
    }

    /**
     * {@code VERSION AS OF <数字>} 按**快照 id** 取，不按 {@code Snapshot.version}。
     *
     * <p>这是「多次写入后查到的还是第一次的数据」那个 bug 的回归用例。客户端每个快照都送
     * {@code version=3}（快照文件格式版本），若把它当查询键，所有快照会命中同一行，
     * 于是查 3 拿到的是 id 最小的那个快照。顺带钉住 {@code LATEST} / {@code EARLIEST} /
     * 标签名三种非数字取值。
     */
    @Test
    void versionLookupUsesTheSnapshotIdNotTheWireFormatVersion() {
        createTable("orders");
        tableService.commit(PREFIX, database, "orders", commitBody(1L, "uuid-1"));
        tableService.commit(PREFIX, database, "orders", commitBody(2L, "uuid-2"));

        assertEquals(List.of(3, 3), tableService.listSnapshots(PREFIX, database, "orders", null, null)
                        .snapshots().stream().map(TableDtos.Snapshot::version).toList(),
                "客户端送的 version 是快照文件格式版本，每个快照都一样——所以它当不了查询键");

        assertEquals(2L, tableService.getVersionSnapshot(PREFIX, database, "orders", "2").snapshot().id(),
                "数字指的是快照 id：查 2 应拿到快照 2，而不是「version 等于 2」的行");
        assertEquals(2L, tableService.getVersionSnapshot(PREFIX, database, "orders", "LATEST").snapshot().id());
        assertEquals(1L, tableService.getVersionSnapshot(PREFIX, database, "orders", "EARLIEST").snapshot().id());

        ApiException missing = assertThrows(ApiException.class,
                () -> tableService.getVersionSnapshot(PREFIX, database, "orders", "9"));
        assertEquals(404, missing.getStatus());

        // 非数字、非 LATEST/EARLIEST 的一律当标签名
        tagService.create(PREFIX, database, "orders", new TagDtos.CreateTagRequest("v1", 1L, null, false));
        assertEquals(1L, tableService.getVersionSnapshot(PREFIX, database, "orders", "v1").snapshot().id());
        assertThrows(ApiException.class,
                () -> tableService.getVersionSnapshot(PREFIX, database, "orders", "no-such-tag"));
    }

    /**
     * 回滚把目标之后的快照文件删掉，并把 {@code LATEST} 指回目标。
     *
     * <p>只改库不删文件，得到的是「数据库说只有快照 1、仓库里有 1/2/3 且 LATEST 指着 3」——
     * 绕开服务端的读端会看到一张回滚根本没发生的表。
     */
    @Test
    void rollingBackASnapshotRemovesTheLaterSnapshotFiles() throws IOException {
        String path = createTable("orders");
        for (long id = 1; id <= 3; id++) {
            tableService.commit(PREFIX, database, "orders", commitBody(id, "uuid-" + id));
        }

        tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "snapshot", "snapshotId", 1L), null));

        assertTrue(Files.exists(snapshotFile(path, 1L)));
        assertFalse(Files.exists(snapshotFile(path, 2L)), "回滚后目标之后的快照文件必须删掉");
        assertFalse(Files.exists(snapshotFile(path, 3L)));
        assertEquals("1", Files.readString(latestHintFile(path)).trim(),
                "LATEST 要指回目标快照，否则读端会去找一个已经不存在的快照");
    }

    // ------------------------------------------------------------------ 失败策略

    /**
     * 服务端没有该 scheme 的 FileIO 实现时，默认（best-effort）不阻断建表。
     *
     * <p>严格模式下同一件事会变成 500，见 {@code TableMetadataFailFastTests}。
     * 这里直接构造一个 {@code s3://} 位置的表实体：本模块的类路径里没有
     * {@code paimon-s3}（对象存储的 FileIO 是插件，见 {@code TableMetadataService}），
     * 因此查找实现这一步就会失败，而且不会真的连网。
     */
    @Test
    void missingFileIoImplementationIsRecordedInsteadOfFailingTheRequest() {
        CatalogEntity catalog = catalogService.resolve(PREFIX);
        TableEntity table = new TableEntity();
        table.setId("tm-probe");
        table.setCatalogId(catalog.getId());
        table.setName("probe");
        table.setPath("s3://no-such-bucket/probe.db/probe");
        table.setSchemaDoc(Codecs.write(ordersSchema()));

        // 默认配置下不应抛异常
        assertFalse(properties.getTableMetadata().isFailOnError(), "本用例假设默认是尽力而为");
        tableMetadataService.materializeNewTable(table);

        // 把开关临时拨到严格，同一份输入应变成 500；恢复原值以免影响其它用例
        properties.getTableMetadata().setFailOnError(true);
        try {
            ApiException failure = assertThrows(ApiException.class,
                    () -> tableMetadataService.materializeNewTable(table));
            assertEquals(500, failure.getStatus());
            assertTrue(failure.getMessage().contains("write schema-0"), failure.getMessage());
        } finally {
            properties.getTableMetadata().setFailOnError(false);
        }
    }

    // ------------------------------------------------------------------ 辅助

    /** 建一张标准订单表，返回服务端给出的表路径。 */
    private String createTable(String table) {
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, table), ordersSchema()));
        return tableService.get(PREFIX, database, table).path();
    }

    private String catalogWarehouse() {
        return catalogService.resolve(PREFIX).getWarehouse();
    }

    /** 本用例那个库对应的目录；用于测试收尾时清理。 */
    private Path databaseDirectory() {
        return Paths.get(URI.create(catalogWarehouse() + "/" + database + ".db"));
    }

    private static Path tablePath(String serverPath) {
        return Paths.get(URI.create(serverPath));
    }

    private static Path schemaFile(String serverPath, long schemaId) {
        return tablePath(serverPath).resolve("schema").resolve("schema-" + schemaId);
    }

    private static Path snapshotFile(String serverPath, long snapshotId) {
        return tablePath(serverPath).resolve("snapshot").resolve("snapshot-" + snapshotId);
    }

    /** {@code SnapshotManager.commitLatestHint} 写下的提示文件，内容是快照 id 的十进制文本。 */
    private static Path latestHintFile(String serverPath) {
        return tablePath(serverPath).resolve("snapshot").resolve("LATEST");
    }

    /**
     * 造一份「客户端形状」的提交报文并解析成 JSON 树——就是 controller 收到的东西。
     *
     * <p>手工拼 JSON 而不是走 {@code CommitTableRequest}：客户端送来的是完整的 Paimon
     * {@code Snapshot}，里面有规格没建模的字段（这里放了 {@code properties} 与
     * {@code nextRowId}）。用 DTO 序列化恰好会把要验证的那部分丢掉，用例就成了空跑。
     *
     * <p>{@code version} 固定写 3、{@code id} 逐号递增，与客户端行为一致：
     * 前者是快照文件格式版本（每个快照都一样），后者才是快照 id。
     */
    private static JsonNode commitBody(long snapshotId, String uuid) {
        return Json.read("""
                {"snapshot": {
                   "version": 3,
                   "uuid": "%s",
                   "id": %d,
                   "schemaId": 0,
                   "baseManifestList": "base-manifest-list-%d",
                   "baseManifestListSize": 12,
                   "deltaManifestList": "delta-manifest-list-%d",
                   "indexManifest": "index-manifest-%d",
                   "commitUser": "tester",
                   "commitIdentifier": %d,
                   "commitKind": "APPEND",
                   "timeMillis": 1700000000000,
                   "totalRecordCount": 1,
                   "deltaRecordCount": 1,
                   "nextRowId": 1,
                   "properties": {"paimon.max.sequence.number": "%d"}
                 }}""".formatted(uuid, snapshotId, snapshotId, snapshotId, snapshotId, snapshotId, snapshotId),
                JsonNode.class);
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响断言结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static List<String> schemaFields() {
        return List.of("id", "name", "dt");
    }

    /** 与 {@code PaimonRestCatalogApiTests.ordersSchema()} 保持同一份形状。 */
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

    /** 以 {@code key, value, key, value, ...} 形式构造变更项；首个键必须是 {@code action}。 */
    private static Map<String, Object> change(Object... keyValues) {
        Map<String, Object> change = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            change.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return change;
    }
}
