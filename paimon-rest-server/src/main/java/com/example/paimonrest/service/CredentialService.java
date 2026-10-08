package com.example.paimonrest.service;

import com.example.paimonrest.domain.entity.CatalogEntity;
import com.example.paimonrest.domain.entity.TableEntity;
import com.example.paimonrest.domain.repo.CatalogRepository;
import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import com.example.paimonrest.dto.TableDtos;
import com.example.paimonrest.dto.TypeDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Codecs;
import com.example.paimonrest.support.ResourceType;
import com.example.paimonrest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表级数据访问授权，对应 Polaris 的凭证下发模型。
 *
 * <p>引擎不直接持有对象存储的长期密钥：先向目录服务申请一份范围限定到单表、
 * 有明确过期时间的凭据，服务端确认调用方有权访问该表后才签发。
 *
 * <p><b>下发什么由 catalog 的存储类型决定。</b>本类只负责定位 catalog、
 * 走缓存、把结果的过期时刻算出来；具体产出哪些键、密钥从哪一级配置取，
 * 都交给 {@link StorageRuntimePolicy} 选定的 {@link StorageCredentialManager}。
 * 这样各存储类型（S3 / Azure / GCS / OBS / OSS / FILE）的键名与取值规则集中在一个类里，
 * 不会因为类型变多而把本类写成一张大分支表。
 *
 * <p><b>过期时刻来自缓存。</b>同一份凭据在有效期内被反复请求时，返回的是同一个
 * 过期时刻，而不是「当前时间 + TTL」。差异在这里很实际：后者会让调用方以为
 * 每次拿到的都是一份全新的、完整时长的凭据，而实际上密钥没变。
 */
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final TableLookup tableLookup;
    private final CatalogRepository catalogRepository;
    private final StorageRuntimePolicy storagePolicy;
    private final StorageCredentialCache cache;

    /** {@code GET .../tables/{table}/token}：签发数据访问凭据。 */
    @Transactional(readOnly = true)
    public TableDtos.GetTableDataTokenResponse token(String prefix, String database, String table) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        CatalogEntity catalog = catalogRepository.findById(target.getCatalogId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, prefix));
        StorageConfigInfo storage = StorageConfigs.of(catalog);

        String key = StorageCredentialCache.key(catalog.getId(), storage, target.getPath());
        long now = System.currentTimeMillis();
        StorageCredentialCache.Cached cached = cache.get(key).orElseGet(() -> cache.put(key,
                storagePolicy.credentialManager().vend(storage, target.getId(), target.getPath()), now));

        return new TableDtos.GetTableDataTokenResponse(cached.credential().token(), cached.expiresAtMillis());
    }

    /**
     * {@code POST .../tables/{table}/auth}：查询鉴权。
     *
     * <p>返回该查询需要附加的行过滤表达式与列脱敏规则；请求的列若不在表 schema 中，
     * 直接以 403 拒绝。当前实现不配置策略，因此返回空的过滤与脱敏规则。
     */
    @Transactional(readOnly = true)
    public TableDtos.AuthTableQueryResponse auth(String prefix, String database, String table,
                                                 TableDtos.AuthTableQueryRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request != null && request.select() != null && !request.select().isEmpty()) {
            Set<String> known = new HashSet<>(columnNames(Codecs.readSchema(target.getSchemaDoc())));
            for (String column : request.select()) {
                if (column != null && !column.isBlank() && !known.contains(column)) {
                    throw ApiException.forbidden("Table has no permission for column: " + column);
                }
            }
        }
        return new TableDtos.AuthTableQueryResponse(new ArrayList<>(), new LinkedHashMap<>());
    }

    private static List<String> columnNames(TypeDtos.Schema schema) {
        List<String> names = new ArrayList<>();
        for (TypeDtos.DataField field : schema.getFields()) {
            if (field != null && field.getName() != null) {
                names.add(field.getName());
            }
        }
        return names;
    }
}
