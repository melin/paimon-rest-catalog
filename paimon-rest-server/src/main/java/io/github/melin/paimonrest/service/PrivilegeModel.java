package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.dto.Privilege;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 权限蕴含关系。
 *
 * <p>管理规格只列出权限取值，不描述蕴含（哪项权限包含哪几项）。这里的关系来自
 * Polaris 访问控制文档的明文表述，每条标注出处；文档没有明说的不做推断。
 *
 * <p>出处：{@code https://polaris.apache.org/in-dev/unreleased/managing-security/access-control/}
 *
 * <table>
 *   <caption>已编码的蕴含规则</caption>
 *   <tr><th>权限</th><th>蕴含</th><th>文档原文</th></tr>
 *   <tr><td>{@code CATALOG_MANAGE_CONTENT}</td>
 *       <td>文档明列的 8 项（见 {@link #CATALOG_MANAGE_CONTENT_EXPANSION}）</td>
 *       <td>This privilege encompasses the following privileges: …</td></tr>
 *   <tr><td>{@code TABLE_FULL_METADATA}</td>
 *       <td>全部 {@code TABLE_*} 权限，排除 {@code TABLE_READ_DATA}、{@code TABLE_WRITE_DATA}</td>
 *       <td>Grants all table privileges, except TABLE_READ_DATA and TABLE_WRITE_DATA,
 *           which need to be granted individually.</td></tr>
 *   <tr><td>{@code VIEW_FULL_METADATA}</td><td>全部 {@code VIEW_*} 权限</td>
 *       <td>Grants all view privileges.</td></tr>
 *   <tr><td>{@code NAMESPACE_FULL_METADATA}</td><td>全部 {@code NAMESPACE_*} 权限</td>
 *       <td>Grants all namespace privileges.</td></tr>
 *   <tr><td>{@code POLICY_FULL_METADATA}</td><td>全部 {@code POLICY_*} 权限</td>
 *       <td>Grants all policy privileges.</td></tr>
 *   <tr><td>{@code SEMANTIC_MODEL_FULL_METADATA}</td>
 *       <td>{@code LIST}、{@code CREATE}、{@code READ}、{@code WRITE}、{@code DROP}</td>
 *       <td>Grants list, create, read, update, and drop privileges.</td></tr>
 *   <tr><td>{@code SEMANTIC_MODEL_CREATE} / {@code READ} / {@code WRITE}</td>
 *       <td>{@code SEMANTIC_MODEL_LIST}</td>
 *       <td>At these scopes, SEMANTIC_MODEL_CREATE, SEMANTIC_MODEL_READ, and
 *           SEMANTIC_MODEL_WRITE also permit listing models.</td></tr>
 * </table>
 *
 * <p><b>为什么 {@code *_FULL_METADATA} 按权限名前缀推导，而不是直接用
 * {@link Privilege#allowedFor(String)}。</b>
 * 规格的 6 个权限 enum 表达的是「该层级**可以授予**哪些权限」，
 * 而不是「该层级的 FULL_METADATA **蕴含**哪些权限」。两者并不等价：
 * 规格的每个层级枚举里都列有 {@code CATALOG_MANAGE_ACCESS}，
 * Namespace 级还列有 {@code CATALOG_MANAGE_CONTENT} 与 {@code CATALOG_MANAGE_METADATA}
 * ——它们之所以出现在低层级枚举中，是因为这些权限也可以在低层级资源上授予。
 * 若把 {@code TABLE_FULL_METADATA} 直接展开成 Table 级枚举全集，
 * 一个只应写表的角色就会获得授权管理能力，属于权限提升。因此这里按
 * 「{@code TABLE_FULL_METADATA} ⇒ 名称以 {@code TABLE_} 开头的权限」推导，
 * 从而自然排除全部 {@code CATALOG_*} 权限。
 *
 * <p><b>刻意未编码的部分</b>（文档未给出可判定的清单，不做猜测）：
 *
 * <ul>
 *   <li>{@code CATALOG_MANAGE_METADATA} 自身包含哪些权限。文档只说它
 *       「Enables full management of the catalog, catalog roles, namespaces, and tables」，
 *       没有像 {@code CATALOG_MANAGE_CONTENT} 那样逐项列举。因此它只作为
 *       {@code CATALOG_MANAGE_CONTENT} 的被蕴含项存在，不再向下展开。
 *   <li>文档提到的 {@code CATALOG_FULL_METADATA} 在规格的权限 enum 中不存在，
 *       故不实现。若规格后续补上该取值，在此处补规则即可。
 *   <li>文档明确标注为 deferred 的语义模型授权项（源表/视图权限校验、读时传播检查），
 *       不在此层表达。
 * </ul>
 */
public final class PrivilegeModel {

    /** 文档为 {@code CATALOG_MANAGE_CONTENT} 明列的被蕴含权限。 */
    public static final Set<Privilege> CATALOG_MANAGE_CONTENT_EXPANSION = EnumSet.of(
            Privilege.CATALOG_MANAGE_METADATA,
            Privilege.TABLE_FULL_METADATA,
            Privilege.NAMESPACE_FULL_METADATA,
            Privilege.VIEW_FULL_METADATA,
            Privilege.TABLE_WRITE_DATA,
            Privilege.TABLE_READ_DATA,
            Privilege.CATALOG_READ_PROPERTIES,
            Privilege.CATALOG_WRITE_PROPERTIES);

    /** {@code TABLE_FULL_METADATA} 明确排除、须单独授予的两项。 */
    private static final Set<Privilege> TABLE_DATA_PRIVILEGES =
            EnumSet.of(Privilege.TABLE_READ_DATA, Privilege.TABLE_WRITE_DATA);

    /**
     * {@code *_FULL_METADATA} 的展开基集：取该资源前缀下的全部权限。
     *
     * <p>这样得到的集合天然不含 {@code CATALOG_*} 权限，理由见类注释。
     *
     * @param prefix 权限名前缀，如 {@code TABLE_}
     */
    private static Set<Privilege> byNamePrefix(String prefix) {
        Set<Privilege> result = EnumSet.noneOf(Privilege.class);
        for (Privilege privilege : Privilege.values()) {
            if (privilege.name().startsWith(prefix)) {
                result.add(privilege);
            }
        }
        return result;
    }

    /** 权限 → 直接蕴含的其他权限（不含自身）。构建后不可变。 */
    private static final Map<Privilege, Set<Privilege>> DIRECT;

    static {
        Map<Privilege, Set<Privilege>> direct = new EnumMap<>(Privilege.class);

        direct.put(Privilege.CATALOG_MANAGE_CONTENT,
                Collections.unmodifiableSet(EnumSet.copyOf(CATALOG_MANAGE_CONTENT_EXPANSION)));

        Set<Privilege> tableFull = byNamePrefix("TABLE_");
        TABLE_DATA_PRIVILEGES.forEach(tableFull::remove);
        direct.put(Privilege.TABLE_FULL_METADATA, Collections.unmodifiableSet(tableFull));

        direct.put(Privilege.VIEW_FULL_METADATA,
                Collections.unmodifiableSet(byNamePrefix("VIEW_")));
        direct.put(Privilege.NAMESPACE_FULL_METADATA,
                Collections.unmodifiableSet(byNamePrefix("NAMESPACE_")));
        direct.put(Privilege.POLICY_FULL_METADATA,
                Collections.unmodifiableSet(byNamePrefix("POLICY_")));

        direct.put(Privilege.SEMANTIC_MODEL_FULL_METADATA, EnumSet.of(
                Privilege.SEMANTIC_MODEL_LIST,
                Privilege.SEMANTIC_MODEL_CREATE,
                Privilege.SEMANTIC_MODEL_READ,
                Privilege.SEMANTIC_MODEL_WRITE,
                Privilege.SEMANTIC_MODEL_DROP));

        Set<Privilege> listExpansion = Collections.unmodifiableSet(
                EnumSet.of(Privilege.SEMANTIC_MODEL_LIST));
        direct.put(Privilege.SEMANTIC_MODEL_CREATE, listExpansion);
        direct.put(Privilege.SEMANTIC_MODEL_READ, listExpansion);
        direct.put(Privilege.SEMANTIC_MODEL_WRITE, listExpansion);

        DIRECT = Collections.unmodifiableMap(direct);
    }

    private PrivilegeModel() {
    }

    /**
     * 展开某项权限的传递闭包，含自身。
     *
     * <p>例如 {@code CATALOG_MANAGE_CONTENT} 展开后包含 {@code TABLE_FULL_METADATA}，
     * 后者继续展开成 Table 级元数据权限，因此「授予 MANAGE_CONTENT」能通过
     * 「要求 TABLE_LIST」这类判定。
     *
     * @param privilege 已授予的权限
     * @return 不可变集合，至少含 {@code privilege} 本身
     */
    public static Set<Privilege> expand(Privilege privilege) {
        Set<Privilege> result = EnumSet.of(privilege);
        Set<Privilege> frontier = new LinkedHashSet<>(result);
        while (!frontier.isEmpty()) {
            Set<Privilege> next = new LinkedHashSet<>();
            for (Privilege current : frontier) {
                for (Privilege implied : DIRECT.getOrDefault(current, Set.of())) {
                    if (result.add(implied)) {
                        next.add(implied);
                    }
                }
            }
            frontier = next;
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 判断已授予的权限能否满足要求。
     *
     * @param granted  主体在某资源上实际持有的权限
     * @param required 操作要求的权限
     * @return 任一已授予权限的闭包覆盖 {@code required} 即为真
     */
    public static boolean satisfies(Set<Privilege> granted, Privilege required) {
        for (Privilege candidate : granted) {
            if (expand(candidate).contains(required)) {
                return true;
            }
        }
        return false;
    }

    /** 供测试与诊断读取直接蕴含关系。 */
    public static Set<Privilege> directlyImpliedBy(Privilege privilege) {
        return DIRECT.getOrDefault(privilege, Set.of());
    }
}
