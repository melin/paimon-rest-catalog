package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.TableEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.ResourceType;
import io.github.melin.paimonrest.support.StorageConfigs;
import io.github.melin.paimonrest.support.VendedStorageCredential;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileIOLoader;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.utils.JsonSerdeUtil;
import org.apache.paimon.utils.SnapshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把表 schema 与快照物化进 Paimon 表的物理目录
 * （{@code <table>/schema/schema-<n>}、{@code <table>/snapshot/snapshot-<n>}）。
 *
 * <p><b>为什么这件事属于服务端。</b>Paimon 的目录实现分两类，责任边界不一样：
 *
 * <ul>
 *   <li>{@code HiveCatalog} 这类「拥有表」的目录：{@code createTableImpl} 里先
 *       {@code schemaManager(identifier, location).createTable(schema, externalTable)}
 *       把目录与 schema 文件写出来，再去 HMS 登记。表能写是因为目录实现自己
 *       落过这份文件。
 *   <li>{@code RESTCatalog} 是**瘦客户端**：{@code createTable} 只做一次 HTTP POST
 *       （{@code api.createTable(identifier, newSchema)}），客户端代码里没有任何
 *       写 schema 文件的路径（唯一一处 {@code schemaManager.createTable} 在
 *       {@code inferSchemaIfExternalPaimonTable} 里，用于「挂载已存在的 Paimon 表」，
 *       且被 {@code latest().isPresent()} 挡着）。物化这件事因此整体移到了服务端，
 *       由服务端的实现负责——Paimon 官方 REST 服务端是内嵌一个文件系统目录来做的，
 *       本服务端此前只写数据库，于是就留下了这个缺口。
 * </ul>
 *
 * <p><b>缺的不是「新建表的便利」，而是后续所有写入的前提。</b>
 * {@code FileStoreCommitImpl.tryCommitOnce} 在写完 manifest 之后要取当前 schemaId，
 * 走的是 {@code SchemaManager.latestOrThrow("Cannot get latest schema for table " + name)}，
 * 读的就是 {@code <table>/schema/schema-<n>}。取不到就抛异常并回滚本次提交——
 * 现象是引擎侧报一句 {@code Cannot get latest schema for table <表名>}，
 * 而数据文件（分区目录、parquet）其实已经落盘，看起来像文件系统坏了。
 *
 * <p><b>解析 schema 用的就是 Paimon 自己的 JSON 映射。</b>REST 报文里 {@code schema}
 * 字段的 wire 形状就是 {@code org.apache.paimon.schema.Schema} 的 Jackson 形态
 * （{@code CreateTableRequest.schema} 就是这个类型），因此这里用
 * {@link JsonSerdeUtil}（即 Paimon 自己那个 {@code ObjectMapper}，带 {@code DataType}
 * 模块、且关闭了 unknown-property 失败）反序列化，而不是自己再写一遍类型树映射。
 * 后者迟早会在复杂类型（{@code ARRAY} / {@code MAP} / {@code ROW} / {@code VECTOR}）
 * 的某个分支上与客户端产生分歧，而那种分歧的表现是「表建出来了但列类型不对」。
 *
 * <p><b>服务端自己访问仓库用的凭据，与下发给引擎的是同一份。</b>取
 * {@link StorageCredentialManager#vend} 的产物直接当 FileIO 选项：引擎认的键名族
 * （{@code s3.*} / {@code fs.obs.*} / {@code fs.oss.*} / {@code gcs.*}）本来就是
 * Paimon FileIO 的选项名。不重新翻译一次的理由是避免出现「引擎能写、服务端不能写」
 * 这种只在建表那一刻暴露的分歧；至于为什么连密钥来源的四级优先级也要一致，
 * 见 {@link DefaultStorageCredentialManager} 的类注释。
 *
 * <p><b>为什么每次操作都新建 FileIO。</b>本类不缓存 FileIO，也不缓存凭证：写路径是
 * 低频操作（建表 / 改表 / 回滚 / 删表），而为缓存付出的代价是「管理 API 换过 catalog
 * 凭据之后，服务端还在用旧钥匙写」——那种问题会在下次建表时才炸，且报错只说是
 * 认证失败。真要优化也该先量出来，而不是先猜。
 *
 * <p>三档行为（默认值及其代价）见 {@link RestServerProperties.TableMetadata}。
 */
@Service
@RequiredArgsConstructor
public class TableMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TableMetadataService.class);

    /** 本地文件系统的 scheme，与 {@code LocalFileIOLoader.SCHEME} 一致。 */
    private static final String SCHEME_FILE = "file";

    private final CatalogRepository catalogRepository;

    private final StorageRuntimePolicy storagePolicy;

    private final RestServerProperties properties;

    /**
     * scheme → FileIO 实现，来自 {@code META-INF/services/org.apache.paimon.fs.FileIOLoader}。
     *
     * <p>自己做这层发现而不用 {@code FileIO.get(path, context)}：后者对本部署会
     * 直接失败——它构造 {@code ResolvingFileIO}，而 {@code ResolvingFileIO.configure}
     * 要拿 Hadoop {@code Configuration}，服务端不带 Hadoop。逐条列举的写法同时
     * 让「这个 scheme 有没有实现」变成一个可以自己给出错误信息的问题。
     */
    private final Map<String, FileIOLoader> loaders = new ConcurrentHashMap<>();

    private volatile boolean loadersDiscovered;

    // ------------------------------------------------------------------ 对外动作

    /**
     * 建表：写出 {@code schema/schema-0}。
     *
     * <p>用 {@code createTable(schema, externalTable=true)} 而不是 {@code externalTable=false}：
     * 两者的差别只出现在「目录里已经有 schema」这一种情况上——
     * {@code false} 直接抛 {@code Schema in filesystem exists, creation is not allowed.}，
     * {@code true} 则比对结构，一致就复用、不一致才报错。重复创建在两种场景下都会发生：
     * 严格模式（{@code fail-on-error=true}）下客户端重试一次刚才回滚掉的建表请求；
     * 以及「删表时没有连目录一起删（{@code purge-on-drop=false}）→ 同名重建」。
     *
     * <p>复用而不是覆盖是有意的：目录里那份 schema 可能属于另一张表（同名旧表、
     * 或别人手工放在这个位置的表）。悄悄覆盖掉它的元数据，比报一句
     * {@code New schema is not equal to exists schema} 危险得多——后者至少把
     * 「换个位置，或者清掉旧目录」这个选择留给调用方。
     */
    public void materializeNewTable(TableEntity table) {
        if (!materializationEnabled(table)) {
            return;
        }
        attempt("write schema-0", table, () -> {
            TableSchema materialized =
                    new SchemaManager(fileIO(table), new Path(table.getPath()))
                            .createTable(parsedSchema(table), /* externalTable= */ true);
            log.info("materialized schema-{} for table {} at {}",
                    materialized.id(), table.getName(), table.getPath());
        });
    }

    /**
     * 改表 / 回滚 schema：写出 {@code schema/<schemaId>}。
     *
     * <p>{@code schemaId} 由调用方给出（{@code TableEntity.schemaId}），与数据库里的
     * 版本号严格对齐。这一点不能反着来——不能用 Paimon 的
     * {@code commitChanges} 让它自己算下一个号：那样两边的号只会在没有外力介入时
     * 偶然一致，而一旦有人直接改了仓库（或某个分支被写脏），
     * {@code rollback-schema} 之后「客户端看到的 schemaId」与「文件里的 schemaId」
     * 就会错位，且错位后没有任何地方会报错。
     *
     * <p>写入内容取自 {@code TableEntity.schemaDoc}，也就是本次变更**之后**的完整
     * schema（{@code SchemaChangeService.apply} 已经改完）。因此本方法不做
     * {@code SchemaChange} 的二次翻译：变更语义只有一处实现，服务端回给客户端的
     * schema 与落到仓库里的 schema 必然同一份。
     *
     * <p>{@code SchemaManager.commit} 在 Paimon 里标着 {@code @VisibleForTesting}，
     * 但它是唯一一个「按给定 id 写完整 TableSchema」的公开入口，
     * 且内部会走 {@code SchemaValidation.validateTableSchema} 做结构与选项校验。
     * 换来的是与 {@code commitChanges} 同等的校验强度，代价是这一处依赖了
     * 版本升级时可能变动的 API——paimon.version 钉在 2.0.0，升级时要看这里。
     */
    public void materializeSchemaVersion(TableEntity table, long schemaId) {
        if (!materializationEnabled(table)) {
            return;
        }
        attempt("write schema-" + schemaId, table, () -> {
            new SchemaManager(fileIO(table), new Path(table.getPath()))
                    .commit(TableSchema.create(schemaId, parsedSchema(table)));
            log.info("materialized schema-{} for table {} at {}", schemaId, table.getName(), table.getPath());
        });
    }

    /**
     * 提交快照：写出 {@code snapshot/snapshot-<id>} 与 {@code snapshot/LATEST}。
     *
     * <p><b>为什么快照也要服务端写。</b>这件事在 schema 上已经解释过一遍，快照是完全平行的一处：
     * {@code FileStoreCommitImpl} 把提交交给 {@code SnapshotCommit}，而实现的选择在
     * {@code CatalogEnvironment.snapshotCommit}——{@code catalogLoader != null &&
     * supportsVersionManagement} 时用 {@code CatalogSnapshotCommit}（把 {@code Snapshot}
     * 对象 POST 给服务端），否则才用 {@code RenamingSnapshotCommit}（自己写
     * {@code <table>/snapshot/snapshot-<n>}）。{@code RESTCatalog.supportsVersionManagement()}
     * 恒返回 {@code true}，所以走 REST catalog 的客户端**从不**写快照文件，
     * {@code RenamingSnapshotCommit} 在这条链路上根本不会被实例化。本方法就是把
     * {@code RenamingSnapshotCommit.commit} 那两步（原子写文件 + 更新 LATEST 提示）在服务端重做一遍。
     *
     * <p><b>不写会怎样：现象具有欺骗性。</b>REST 读路径照样能查出数据——
     * {@code SnapshotLoaderImpl} 走的是 {@code catalog.loadSnapshot(identifier)}，
     * 也就是查本服务端的数据库。于是「用 REST catalog 一切正常、绕开服务端看仓库却是一张
     * 没有快照的表」：Paimon CLI、直连文件系统的引擎、以及任何按「warehouse 自描述」
     * 假设写的运维脚本都会失败或读到旧数据。
     *
     * <p><b>写入内容用客户端发来的原始 JSON，而不是按数据库字段重新拼一份。</b>
     * 数据库只存了本服务端建模过的字段，报文里的 {@code properties}（序列号水位）、
     * {@code operation}、{@code nextRowId}、{@code *ManifestListSize} 都没落库。
     * 重新拼出来的快照文件会缺这些字段，而缺 {@code properties} 尤其阴——它不会解析失败，
     * 而是让读端重算序列号，表现为去重与变更日志语义悄悄改变。所以这里落的是**原文**。
     *
     * <p>与 {@code RenamingSnapshotCommit} 一样先试原子写、写不动再比对已有内容：
     * 客户端重试或同一快照被重复提交时，内容一致就算成功；不一致必须报出来，
     * 那说明仓库里那份属于别人，覆盖它比报错危险得多。
     */
    public void materializeSnapshot(TableEntity table, long snapshotId, String snapshotDoc) {
        if (!materializationEnabled(table) || snapshotDoc == null || snapshotDoc.isBlank()) {
            return;
        }
        attempt("write snapshot-" + snapshotId, table, () -> {
            FileIO fileIO = fileIO(table);
            SnapshotManager snapshotManager = snapshotManager(fileIO, table);
            Path snapshotPath = snapshotManager.snapshotPath(snapshotId);
            Snapshot incoming = Snapshot.fromJson(snapshotDoc);
            boolean committed = fileIO.tryToWriteAtomic(snapshotPath, snapshotDoc);
            if (!committed) {
                if (!fileIO.exists(snapshotPath)) {
                    throw new IOException("snapshot-" + snapshotId + " was not written and "
                            + snapshotPath + " does not exist");
                }
                committed = incoming.equals(Snapshot.fromJson(fileIO.readFileUtf8(snapshotPath)));
            }
            if (!committed) {
                throw new IOException("snapshot-" + snapshotId + " already exists at " + snapshotPath
                        + " with a different content");
            }
            snapshotManager.commitLatestHint(snapshotId);
            log.info("materialized snapshot-{} for table {} at {}",
                    snapshotId, table.getName(), table.getPath());
        });
    }

    /**
     * 回滚：删掉目标快照之后的快照文件，并把 {@code LATEST} 指回目标。
     *
     * <p>与 {@code TableService.rollback} 落库那一半配对。只改库不删文件，得到的是
     * 「数据库说只有快照 1、仓库里有 1 和 2，而 {@code LATEST} 还指着 2」——
     * 绕开服务端的读端会看到一张回滚根本没发生的表，而这类残留没有任何接口能清理。
     *
     * <p>删的是快照文件（元数据），不是数据文件，因此不受 {@code purge-on-drop} 约束；
     * 这一点与 Paimon 自己的 {@code rollbackTo} 一致。
     *
     * <p>不用 {@code SnapshotManager.snapshotIdStream()}：它返回懒流，底层可能挂着对象存储的
     * 目录句柄。这里一次 {@code listStatus} 列完，再按
     * {@link SnapshotManager#SNAPSHOT_PREFIX} 过滤——同目录下还有 {@code LATEST} 这类提示
     * 文件，按前缀过一遍才不会把它们当成快照号去解析；名字解析不出来的一律跳过，
     * 一个手工放进去的文件不该让回滚失败。
     */
    public void discardSnapshotsAfter(TableEntity table, long snapshotId) {
        if (!materializationEnabled(table)) {
            return;
        }
        attempt("discard snapshots after " + snapshotId, table, () -> {
            FileIO fileIO = fileIO(table);
            SnapshotManager snapshotManager = snapshotManager(fileIO, table);
            Path directory = snapshotManager.snapshotDirectory();
            if (!fileIO.exists(directory)) {
                return;
            }
            for (FileStatus status : fileIO.listStatus(directory)) {
                long id = snapshotFileId(status.getPath().getName());
                if (id > snapshotId) {
                    snapshotManager.deleteSnapshot(id);
                }
            }
            snapshotManager.commitLatestHint(snapshotId);
            log.info("discarded snapshots after {} for table {} at {}",
                    snapshotId, table.getName(), table.getPath());
        });
    }

    /**
     * 删表：按配置决定是否连表目录一起删除。
     *
     * <p>只有 {@code purge-on-drop=true} 且表不是 {@code register} 进来的外部表时才动手。
     * 外部表的位置不归本服务端所有，删它等于删别人的数据；这一点与 Paimon 的
     * {@code HiveCatalog.dropTable} 一致（它对 {@code externalTable=true} 的表不删目录）。
     */
    public void dropTableDirectory(TableEntity table) {
        if (!materializationEnabled(table) || !properties.getTableMetadata().isPurgeOnDrop()) {
            return;
        }
        attempt("delete table directory", table, () -> fileIO(table).deleteDirectoryQuietly(new Path(table.getPath())));
    }

    // ------------------------------------------------------------------ 策略与错误处理

    /**
     * 该不该为这张表动仓库。
     *
     * <p>外部表（{@code register} 进来的）恒不处理：它代表「仓库里已经有一张表，
     * 本服务端只登记位置」。反过来往它的目录里写 schema，等于用服务端数据库里的
     * 空 schema 去覆盖一张真实表的元数据。
     */
    private boolean materializationEnabled(TableEntity table) {
        if (table == null || table.isExternal()) {
            return false;
        }
        if (!properties.getTableMetadata().isEnabled()) {
            log.debug("paimon.rest.table-metadata.enabled=false; {} stays metadata-only", table.getName());
            return false;
        }
        return true;
    }

    /**
     * 执行一次仓库写入，并按 {@code fail-on-error} 决定失败是「记一笔」还是「拒绝请求」。
     *
     * <p>连 {@link LinkageError} 一起接住是有具体原因的：对象存储的 FileIO 以插件形式
     * 部署，缺插件时除了正常的 {@code UnsupportedSchemeException}，还可能以
     * {@code NoClassDefFoundError} 的形式出现（例如 Hadoop 系的类不在类路径里）。
     * 两者对运维是同一件事——「这个 scheme 在本部署里没有实现」——没有理由一个
     * 按配置降级、另一个把请求打崩。
     *
     * <p>{@link ServiceConfigurationError} 同理，它也是「插件不完整」的另一种说法：
     * 插件目录里的 {@code META-INF/services/*} 声明了一个类，而那个类加载不到。
     * 实测到的那条链是 Paimon 2.0.0 的 {@code paimon-s3} 插件包：它把 dnsjava 的
     * {@code InetAddressResolverProvider} 声明放在插件根、实现却放在
     * {@code META-INF/versions/18/}（多版本 jar 布局，解成目录后不再生效），
     * 于是插件 classloader 成为线程上下文类加载器时，JDK 18+ 的
     * {@code InetAddress.loadResolver} 找不到 provider，
     * {@code InetAddress.getLocalHost()} 直接抛错——而 Hadoop 的
     * {@code MetricsSystemImpl} 在建 {@code S3AFileSystem} 时会调它。
     * 症状因此与 S3 八竿子打不着，堆栈里全是 Hadoop 与 JDK 的类。
     * 它属于同一个判断：本部署写不了这个仓库，按配置降级即可，
     * 不该让建表整体失败。{@code Error} 的其余部分照旧上抛。
     *
     * <p><b>这里也接住 {@link ApiException}，是刻意的。</b>它看起来像「把业务错误
     * 一起吞了」，实际接住的都是同一类事实：<b>这份部署算不出访问仓库所需的凭据</b>。
     * 典型来源是 {@link #fileIOOptions} 里的凭据下发——
     * {@code credential-manager.type=noop}（部署不代发凭据）、
     * catalog 指向一个没配过的 {@code storageName}（具名存储缺失）、
     * 或 catalog 已在解析中途被删。
     * 这些都发生在「元数据已经落库、只是没法替引擎把 schema 文件写出去」这一步，
     * 而它们各自在**该报错的地方**照样报错：noop 在凭据下发端点上回 501、
     * 未配置的 {@code storageName} 在 token 端点上回 500。
     * 建表成功与否不受这些影响，是既有的对外约定——
     * {@code StoragePolicyApiTests} 与 {@code StorageCredentialApiTests} 钉着它。
     * 把这里改成放行，会让「用了 noop 凭据管理器」的部署连表都建不出来，
     * 而那是比「表暂时写不进去」严重得多的回归。
     */
    private void attempt(String description, TableEntity table, MetadataAction action) {
        try {
            action.run();
        } catch (Exception | LinkageError | ServiceConfigurationError e) {
            String message = description + " failed for table " + table.getName()
                    + " at " + table.getPath() + ": " + rootMessage(e);
            if (properties.getTableMetadata().isFailOnError()) {
                log.error("{} (paimon.rest.table-metadata.fail-on-error=true, refusing the request)",
                        message, e);
                throw new ApiException(500, ResourceType.TABLE, table.getName(), message);
            }
            log.warn("{}; the catalog keeps the metadata only, and engines will not be able to write"
                    + " to this table until the schema file exists. Set"
                    + " paimon.rest.table-metadata.fail-on-error=true to make this a hard failure.",
                    message, e);
        }
    }

    // ------------------------------------------------------------------ FileIO 构造

    /** 服务端自己用的 FileIO。每次调用都重新按当前配置构造，理由见类注释。 */
    private FileIO fileIO(TableEntity table) throws IOException {
        Path path = new Path(table.getPath());
        String scheme = schemeOf(path);
        CatalogContext context = CatalogContext.create(new Options(fileIOOptions(table)));
        if (scheme == null) {
            // 没有 scheme 的路径按本地文件处理，与 FileIO.get 的判定保持一致
            return new LocalFileIO();
        }
        FileIOLoader loader = loaders().get(scheme);
        if (loader == null) {
            throw new IOException("no FileIO implementation for scheme '" + scheme
                    + "' is available on the classpath; paimon.rest.file-io.type="
                    + storagePolicy.fileIo().wireName()
                    + " declares which storages this deployment accepts, but the actual"
                    + " implementation has to be deployed as a Paimon plugin"
                    + " (file:// is built in; s3://, obs:// and oss:// are not)");
        }
        FileIO fileIO = loader.load(path);
        fileIO.configure(context);
        return fileIO;
    }

    /**
     * 交给 FileIO 的选项：catalog 的存储种类与位置，加上本服务端为它算出的凭据。
     *
     * <p>密钥来源与引擎完全一致（含「catalog 自带静态凭据」这一级），因此
     * 「服务端能不能写」这个问题与「引擎能不能写」是同一个答案，不需要分别排查。
     *
     * <p>仓库位置（{@code warehouse}）也要给：对象存储的 FileIO 在缺少
     * 定位信息时会退回默认凭据链与默认区域，报出来的错与真正的原因隔着一层。
     */
    private Map<String, String> fileIOOptions(TableEntity table) {
        CatalogEntity catalog = catalogRepository.findById(table.getCatalogId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, table.getCatalogId()));
        Map<String, String> options = new LinkedHashMap<>();
        StorageConfigInfo storage = StorageConfigs.of(catalog);
        if (storage != null) {
            VendedStorageCredential vended =
                    storagePolicy.credentialManager().vend(storage, table.getId(), table.getPath());
            options.putAll(vended.token());
        }
        if (catalog.getWarehouse() != null && !catalog.getWarehouse().isBlank()) {
            options.put(CatalogOptions.WAREHOUSE.key(), catalog.getWarehouse());
        }
        return options;
    }

    /** 路径的 scheme；无 scheme（本地相对/绝对路径）时返回 {@code null}。 */
    private static String schemeOf(Path path) {
        String scheme = path.toUri().getScheme();
        return scheme == null || scheme.isBlank() ? null : scheme.toLowerCase(Locale.ROOT);
    }

    /**
     * 发现一次 FileIOLoader 并按 scheme 建索引。
     *
     * <p>逐个接住加载失败：插件 jar 损坏或依赖缺失时，{@code ServiceLoader} 会在
     * 「取下一个」这一步抛 {@link ServiceConfigurationError}。一个坏插件不该让
     * 整个 scheme 索引都建不起来——那会把问题放大成「连本地表也建不了」。
     */
    private Map<String, FileIOLoader> loaders() {
        if (!loadersDiscovered) {
            synchronized (this) {
                if (!loadersDiscovered) {
                    discoverLoaders();
                    loadersDiscovered = true;
                }
            }
        }
        return loaders;
    }

    private void discoverLoaders() {
        Iterator<FileIOLoader> iterator =
                ServiceLoader.load(FileIOLoader.class, FileIOLoader.class.getClassLoader()).iterator();
        while (iterator.hasNext()) {
            try {
                FileIOLoader loader = iterator.next();
                loaders.putIfAbsent(loader.getScheme().toLowerCase(Locale.ROOT), loader);
            } catch (ServiceConfigurationError | LinkageError e) {
                log.warn("skipping an unusable Paimon FileIOLoader on the classpath: {}", rootMessage(e));
            }
        }
        log.info("discovered Paimon FileIO implementations for schemes {}", loaders.keySet());
    }

    // ------------------------------------------------------------------ 辅助

    /** 把库里的 schema 文档反解成 Paimon 的 {@link Schema}，用 Paimon 自己的 JSON 映射。 */
    private static Schema parsedSchema(TableEntity table) {
        return JsonSerdeUtil.fromJson(table.getSchemaDoc(), Schema.class);
    }

    /**
     * 建一个绑定到该表目录的 {@link SnapshotManager}。
     *
     * <p>分支传 {@code null}：本服务端的表模型里没有分支维度，{@code null} 会被归一化成
     * main 分支，路径就是表目录本身（与客户端默认分支一致）。
     *
     * <p>另外两个可空参数（{@code snapshotLoader}、{@code cache}）都不给：
     * 前者是「让快照读取绕回 catalog」的钩子，而服务端自己就是那个 catalog；
     * 后者是给长驻读端省重复解析的，本类每次调用都新建 FileIO（理由见类注释），
     * 缓存只会把「换了凭据」这件事藏起来。
     */
    private static SnapshotManager snapshotManager(FileIO fileIO, TableEntity table) {
        return new SnapshotManager(fileIO, new Path(table.getPath()), null, null, null);
    }

    /**
     * 从 {@code snapshot-<n>} 文件名取快照号；不是快照文件（{@code LATEST} 提示、手工放的杂物）
     * 返回 {@code -1}。
     */
    private static long snapshotFileId(String fileName) {
        if (!fileName.startsWith(SnapshotManager.SNAPSHOT_PREFIX)) {
            return -1;
        }
        try {
            return Long.parseLong(fileName.substring(SnapshotManager.SNAPSHOT_PREFIX.length()));
        } catch (NumberFormatException e) {
            log.debug("ignoring a non-numeric file in the snapshot directory: {}", fileName);
            return -1;
        }
    }

    /** 取异常链最内层那一句：外层往往是 Paimon 的包装，真正的原因在最里面。 */
    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getName() + (message == null ? "" : ": " + message);
    }

    /** 一次仓库写入动作，允许抛受检异常（FileIO 全是受检的 {@code IOException}）。 */
    @FunctionalInterface
    private interface MetadataAction {
        void run() throws Exception;
    }
}
