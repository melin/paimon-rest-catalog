package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.StorageConfigs;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 注册表与配置发现。
 *
 * <p>REST 路径中的 {@code {prefix}} 即 catalog 的对外标识。客户端拿到
 * {@code GET /v1/config} 返回的 prefix 之后，后续所有资源路径都以它开头，
 * 这与 Iceberg / Paimon 客户端的约定一致。
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogRepository catalogRepository;
    private final RestServerProperties properties;

    /**
     * 解析 prefix 对应的 catalog。
     *
     * @throws ApiException 404 未登记且未开启自动创建时
     */
    @Transactional
    public CatalogEntity resolve(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw ApiException.badRequest("prefix must not be blank");
        }
        return catalogRepository.findByPrefix(prefix).orElseGet(() -> {
            if (!properties.isAutoCreateCatalog()) {
                throw new ApiException(404, null, prefix, "Unknown catalog prefix");
            }
            return create(prefix, properties.getDefaultWarehouse(), "自动登记");
        });
    }

    @Transactional
    public CatalogEntity create(String prefix, String warehouse, String source) {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setId(Paging.newId());
        catalog.setPrefix(prefix);
        String resolved = warehouse == null || warehouse.isBlank()
                ? properties.getDefaultWarehouse()
                : warehouse;
        // 存储配置必须与仓库一起写：只设 warehouse 而漏掉 storageConfig，
        // 读回来就只能靠投影列重建，类型专属字段会丢失
        StorageConfigs.apply(catalog, StorageConfigs.fileStorage(resolved));
        catalog.setDefaults(new LinkedHashMap<>());
        catalog.setOverrides(new LinkedHashMap<>());
        catalog.setCatalogType("INTERNAL");
        catalog.markCreated(RequestContext.principal(), RequestContext.now());
        return catalogRepository.save(catalog);
    }

    /**
     * {@code GET /v1/config}。
     *
     * <p>返回的 {@code defaults} 参与客户端的配置合并（defaults → 客户端属性 → overrides）。
     * 其中必须包含 {@code prefix}，客户端据此拼出后续资源路径。
     */
    @Transactional
    public CommonDtos.ConfigResponse config(String warehouse) {
        String requested = warehouse == null || warehouse.isBlank()
                ? properties.getDefaultPrefix()
                : warehouse;
        CatalogEntity catalog = resolve(requested);

        Map<String, String> defaults = new LinkedHashMap<>();
        if (catalog.getWarehouse() != null && !catalog.getWarehouse().isBlank()) {
            defaults.put("warehouse", catalog.getWarehouse());
        }
        defaults.put("prefix", catalog.getPrefix());
        defaults.putAll(catalog.getDefaults());

        return new CommonDtos.ConfigResponse(defaults, new LinkedHashMap<>(catalog.getOverrides()));
    }
}
