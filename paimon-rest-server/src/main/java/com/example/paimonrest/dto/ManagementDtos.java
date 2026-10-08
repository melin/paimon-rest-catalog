package com.example.paimonrest.dto;

import com.example.paimonrest.dto.ManagementEnums.CatalogType;
import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import java.util.List;
import java.util.Map;

/**
 * 管理 API 的请求与响应模型，字段名与
 * {@code spec/polaris-management-service.yml} 的 schema 一一对应。
 *
 * <p>用 record 而非可变类：这些对象只在控制器与服务层之间传递，没有原地修改的需求，
 * record 自动获得 {@code equals} / {@code hashCode}，便于测试断言。
 *
 * <p>可变长度与自由结构的部分（{@code properties}、{@code credentials}）保留为 Map，
 * 与规格的 {@code object} 类型一致。
 */
public final class ManagementDtos {

    private ManagementDtos() {
    }

    // ------------------------------------------------------------------ catalog

    /** {@code Catalogs}。 */
    public record Catalogs(List<Catalog> catalogs) {
    }

    /**
     * {@code Catalog}。
     *
     * <p>{@code name} 即 catalog API 中的 prefix：管理面用它标识 catalog，
     * catalog 面用它拼路径，两侧指向同一份数据。
     */
    public record Catalog(String type,
                          String name,
                          Map<String, String> properties,
                          Long createTimestamp,
                          Long lastUpdateTimestamp,
                          Integer entityVersion,
                          StorageConfigInfo storageConfigInfo) {
    }

    /** {@code CreateCatalogRequest}。 */
    public record CreateCatalogRequest(Catalog catalog) {
    }

    /** {@code UpdateCatalogRequest}。 */
    public record UpdateCatalogRequest(Integer currentEntityVersion,
                                       Map<String, String> properties,
                                       StorageConfigInfo storageConfigInfo) {
    }

    // ------------------------------------------------------------------ principal

    /** {@code Principals}。 */
    public record Principals(List<Principal> principals) {
    }

    /** {@code Principal}。{@code clientId} 可读，密钥永不出现在该模型里。 */
    public record Principal(String name,
                            String clientId,
                            Map<String, String> properties,
                            Long createTimestamp,
                            Long lastUpdateTimestamp,
                            Integer entityVersion) {
    }

    /** {@code PrincipalWithCredentials}：创建、轮换、重置主体时返回，明文密钥仅此一次。 */
    public record PrincipalWithCredentials(Principal principal, Map<String, Object> credentials) {
    }

    /** {@code CreatePrincipalRequest}。 */
    public record CreatePrincipalRequest(Principal principal, Boolean credentialRotationRequired) {
    }

    /** {@code UpdatePrincipalRequest}。 */
    public record UpdatePrincipalRequest(Integer currentEntityVersion, Map<String, String> properties) {
    }

    /** {@code ResetPrincipalRequest}：两个字段都为空时由服务端生成新凭据。 */
    public record ResetPrincipalRequest(String clientId, String clientSecret) {
    }

    // ------------------------------------------------------------------ principal role

    /** {@code PrincipalRoles}。 */
    public record PrincipalRoles(List<PrincipalRole> roles) {
    }

    /** {@code PrincipalRole}。 */
    public record PrincipalRole(String name,
                                Boolean federated,
                                Map<String, String> properties,
                                Long createTimestamp,
                                Long lastUpdateTimestamp,
                                Integer entityVersion) {
    }

    /** {@code CreatePrincipalRoleRequest}。 */
    public record CreatePrincipalRoleRequest(PrincipalRole principalRole) {
    }

    /** {@code UpdatePrincipalRoleRequest}。 */
    public record UpdatePrincipalRoleRequest(Integer currentEntityVersion, Map<String, String> properties) {
    }

    /** {@code GrantPrincipalRoleRequest}：把 principal role 授予某个主体。 */
    public record GrantPrincipalRoleRequest(PrincipalRole principalRole) {
    }

    // ------------------------------------------------------------------ catalog role

    /** {@code CatalogRoles}。 */
    public record CatalogRoles(List<CatalogRole> roles) {
    }

    /** {@code CatalogRole}。 */
    public record CatalogRole(String name,
                              Map<String, String> properties,
                              Long createTimestamp,
                              Long lastUpdateTimestamp,
                              Integer entityVersion) {
    }

    /** {@code CreateCatalogRoleRequest}。 */
    public record CreateCatalogRoleRequest(CatalogRole catalogRole) {
    }

    /** {@code UpdateCatalogRoleRequest}。 */
    public record UpdateCatalogRoleRequest(Integer currentEntityVersion, Map<String, String> properties) {
    }

    /** {@code GrantCatalogRoleRequest}：把 catalog role 授予某个 principal role。 */
    public record GrantCatalogRoleRequest(CatalogRole catalogRole) {
    }

    // ------------------------------------------------------------------ grants

    /**
     * {@code AddGrantRequest} / {@code RevokeGrantRequest}。
     *
     * <p>{@code grant} 保持为 {@code Map}：规格中 {@code GrantResource} 是以
     * {@code type} 判别的多态联合（discriminator mapping 指向 6 个 schema），
     * 服务端按 {@code type} 分派到 {@link GrantDtos.GrantSpec}，
     * 不把多态解析绑死在具体 JSON 库上——与 catalog 侧对
     * {@code SchemaChange} 等联合的处理方式一致。
     */
    public record AddGrantRequest(Map<String, Object> grant) {
    }

    /** {@code RevokeGrantRequest}。 */
    public record RevokeGrantRequest(Map<String, Object> grant) {
    }

    /** {@code GrantResources}：响应中的授权列表，元素形状随 {@code type} 变化。 */
    public record GrantResources(List<Map<String, Object>> grants) {
    }

    /** catalog 类型的判别值，供校验错误信息复用。 */
    public static CatalogType parseCatalogType(String value) {
        return CatalogType.parse(value).orElse(CatalogType.INTERNAL);
    }
}
