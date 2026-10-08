package com.example.paimonrest.service;

import com.example.paimonrest.config.RequestContext;
import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.domain.entity.PartitionEntity;
import com.example.paimonrest.domain.entity.TableEntity;
import com.example.paimonrest.domain.repo.PartitionRepository;
import com.example.paimonrest.dto.PartitionDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Json;
import com.example.paimonrest.support.Paging;
import com.example.paimonrest.support.Patterns;
import com.example.paimonrest.support.ResourceType;
import com.example.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分区管理：注册、列举、注销、标记完成，以及分区统计的维护。
 *
 * <p>分区统计遵循规格中的语义：字段值为负数表示「未测量」，与 0 不同；
 * {@code replaceStatistics=true} 覆盖存储值，否则累加（时间戳取较晚者，
 * {@code totalBuckets} 永不合并）。
 */
@Service
@RequiredArgsConstructor
public class PartitionService {

    private final PartitionRepository partitionRepository;
    private final TableLookup tableLookup;
    private final RestServerProperties properties;

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public PartitionDtos.ListPartitionsResponse list(String prefix, String database, String table,
                                                     Integer maxResults, String pageToken,
                                                     String partitionNamePattern) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        List<PartitionEntity> filtered = partitionRepository
                .findAllByTableIdOrderBySpecKeyAsc(target.getId())
                .stream()
                .filter(partition -> Patterns.matches(partitionName(partition.getSpec()), partitionNamePattern))
                .toList();
        Paging.Slice<PartitionEntity> slice = Paging.slice(filtered,
                Paging.offset(pageToken),
                Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new PartitionDtos.ListPartitionsResponse(toResponses(slice.items()), slice.nextPageToken());
    }

    /**
     * 按名字批量查询。
     *
     * <p>返回 {@code specs} 中出现的分区，不分页。
     */
    @Transactional(readOnly = true)
    public PartitionDtos.ListPartitionsResponse listByNames(String prefix, String database, String table,
                                                            PartitionDtos.ListPartitionsByNamesRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        List<Map<String, String>> specs = request == null || request.specs() == null
                ? List.of()
                : request.specs();
        List<PartitionEntity> result = new ArrayList<>();
        for (Map<String, String> spec : specs) {
            String key = specKey(spec);
            partitionRepository.findByTableIdAndSpecKey(target.getId(), key).ifPresent(result::add);
        }
        return new PartitionDtos.ListPartitionsResponse(toResponses(result), null);
    }

    /**
     * 按谓词查询。
     *
     * <p>规格允许返回结果是匹配集合的超集，客户端需自行复核谓词并继续翻页。
     * 这里不解析 {@code filter} 表达式，直接返回全部（可选按名称前缀收窄）分区，
     * 属于合法的超集实现。
     */
    @Transactional(readOnly = true)
    public PartitionDtos.ListPartitionsResponse listByFilter(String prefix, String database, String table,
                                                             PartitionDtos.ListPartitionsByFilterRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        List<PartitionEntity> filtered = partitionRepository
                .findAllByTableIdOrderBySpecKeyAsc(target.getId())
                .stream()
                .filter(partition -> request == null
                        || Patterns.matches(partitionName(partition.getSpec()), request.partitionNamePattern()))
                .toList();
        Paging.Slice<PartitionEntity> slice = Paging.slice(filtered,
                Paging.offset(request == null ? null : request.pageToken()),
                Paging.pageSize(request == null ? null : request.maxResults(),
                        properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new PartitionDtos.ListPartitionsResponse(toResponses(slice.items()), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ 变更

    @Transactional
    public PartitionDtos.CreatePartitionsResponse create(String prefix, String database, String table,
                                                         PartitionDtos.CreatePartitionsRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request == null || request.partitionSpecs() == null || request.partitionSpecs().isEmpty()) {
            throw ApiException.badRequest("partitionSpecs must not be empty");
        }
        List<Map<String, String>> specs = request.partitionSpecs();
        List<Map<String, String>> options = request.partitionOptions();
        if (options != null && options.size() != specs.size()) {
            throw ApiException.badRequest("partitionOptions must be position-aligned with partitionSpecs");
        }
        if (request.partitionStatistics() != null && request.replaceStatistics() == null) {
            throw ApiException.badRequest("replaceStatistics is required when partitionStatistics is present");
        }
        boolean ignoreIfExists = Values.bool(request.ignoreIfExists(), true);
        boolean replace = Values.bool(request.replaceStatistics(), false);

        List<Map<String, String>> created = new ArrayList<>();
        List<Map<String, String>> existed = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Map<String, String> spec = specs.get(i) == null ? Map.of() : specs.get(i);
            String key = specKey(spec);
            Optional<PartitionEntity> existing = partitionRepository.findByTableIdAndSpecKey(target.getId(), key);
            if (existing.isPresent()) {
                if (!ignoreIfExists) {
                    throw ApiException.partitionAlreadyExist(spec);
                }
                // 已存在的分区同样接收本次请求的选项与统计，
                // 否则客户端无法通过 create 端点更新统计值
                PartitionEntity entity = existing.get();
                if (options != null && options.get(i) != null) {
                    entity.setOptions(new LinkedHashMap<>(options.get(i)));
                }
                applyStatistics(entity, findStatistics(request.partitionStatistics(), spec), replace);
                entity.touch(RequestContext.principal(), RequestContext.now());
                partitionRepository.save(entity);
                existed.add(spec);
                continue;
            }
            PartitionEntity entity = new PartitionEntity();
            entity.setId(Paging.newId());
            entity.setTableId(target.getId());
            entity.setSpecKey(key);
            entity.setSpec(new LinkedHashMap<>(spec));
            entity.setOptions(options == null || options.get(i) == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(options.get(i)));
            entity.markCreated(RequestContext.principal(), RequestContext.now());
            applyStatistics(entity, findStatistics(request.partitionStatistics(), spec), replace);
            partitionRepository.save(entity);
            created.add(spec);
        }
        return new PartitionDtos.CreatePartitionsResponse(created, existed);
    }

    /**
     * 注销分区。
     *
     * <p>只解绑目录记录，不删除数据文件——规格明确要求服务端不删数据。
     * 不存在的分区记入 {@code missing} 而不报错，与响应结构一致。
     */
    @Transactional
    public PartitionDtos.DropPartitionsResponse drop(String prefix, String database, String table,
                                                     PartitionDtos.DropPartitionsRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request == null || request.partitionSpecs() == null) {
            throw ApiException.badRequest("partitionSpecs must not be empty");
        }
        List<Map<String, String>> dropped = new ArrayList<>();
        List<Map<String, String>> missing = new ArrayList<>();
        for (Map<String, String> spec : request.partitionSpecs()) {
            Map<String, String> normalized = spec == null ? Map.of() : spec;
            Optional<PartitionEntity> existing =
                    partitionRepository.findByTableIdAndSpecKey(target.getId(), specKey(normalized));
            if (existing.isPresent()) {
                partitionRepository.delete(existing.get());
                dropped.add(normalized);
            } else {
                missing.add(normalized);
            }
        }
        return new PartitionDtos.DropPartitionsResponse(dropped, missing);
    }

    /**
     * 标记分区为已完成。
     *
     * <p>与 Paimon 服务端一致，未注册的分区视为错误（404 PARTITION），
     * 避免把名称写错当成成功。
     */
    @Transactional
    public void markDone(String prefix, String database, String table,
                         PartitionDtos.MarkDonePartitionsRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request == null || request.specs() == null) {
            return;
        }
        for (Map<String, Object> spec : request.specs()) {
            String key = specKey(Values.stringMap(spec));
            PartitionEntity entity = partitionRepository.findByTableIdAndSpecKey(target.getId(), key)
                    .orElseThrow(() -> new ApiException(404, ResourceType.PARTITION, key,
                            "The given partition does not exist"));
            entity.setDone(true);
            entity.touch(RequestContext.principal(), RequestContext.now());
            partitionRepository.save(entity);
        }
    }

    // ------------------------------------------------------------------ 统计

    /**
     * 提交快照时随带的分区统计，按 spec 匹配而非按位置匹配。
     */
    @Transactional
    public void applyCommitStatistics(String tableId, List<PartitionDtos.PartitionStatistics> statistics) {
        if (statistics == null || statistics.isEmpty()) {
            return;
        }
        for (PartitionDtos.PartitionStatistics stats : statistics) {
            if (stats == null) {
                continue;
            }
            Map<String, String> spec = Values.stringMap(stats.spec());
            PartitionEntity entity = partitionRepository
                    .findByTableIdAndSpecKey(tableId, specKey(spec))
                    .orElseGet(() -> {
                        PartitionEntity created = new PartitionEntity();
                        created.setId(Paging.newId());
                        created.setTableId(tableId);
                        created.setSpecKey(specKey(spec));
                        created.setSpec(new LinkedHashMap<>(spec));
                        created.markCreated(RequestContext.principal(), RequestContext.now());
                        return created;
                    });
            applyStatistics(entity, stats, false);
            partitionRepository.save(entity);
        }
    }

    private void applyStatistics(PartitionEntity entity, PartitionDtos.PartitionStatistics stats, boolean replace) {
        if (stats == null) {
            return;
        }
        if (replace) {
            if (measured(stats.recordCount())) {
                entity.setRecordCount(stats.recordCount());
            }
            if (measured(stats.fileSizeInBytes())) {
                entity.setFileSizeInBytes(stats.fileSizeInBytes());
            }
            if (measured(stats.fileCount())) {
                entity.setFileCount(stats.fileCount());
            }
            if (measured(stats.lastFileCreationTime())) {
                entity.setLastFileCreationTime(stats.lastFileCreationTime());
            }
        } else {
            entity.setRecordCount(sum(entity.getRecordCount(), stats.recordCount()));
            entity.setFileSizeInBytes(sum(entity.getFileSizeInBytes(), stats.fileSizeInBytes()));
            entity.setFileCount(sum(entity.getFileCount(), stats.fileCount()));
            entity.setLastFileCreationTime(later(entity.getLastFileCreationTime(), stats.lastFileCreationTime()));
        }
        if (stats.totalBuckets() != null) {
            entity.setTotalBuckets(stats.totalBuckets());
        }
    }

    private PartitionDtos.PartitionStatistics findStatistics(List<PartitionDtos.PartitionStatistics> statistics,
                                                             Map<String, String> spec) {
        if (statistics == null) {
            return null;
        }
        String key = specKey(spec);
        for (PartitionDtos.PartitionStatistics stats : statistics) {
            if (stats != null && key.equals(specKey(Values.stringMap(stats.spec())))) {
                return stats;
            }
        }
        return null;
    }

    private static boolean measured(Long value) {
        return value != null && value >= 0;
    }

    private static Long sum(Long stored, Long delta) {
        if (!measured(delta)) {
            return stored;
        }
        return stored == null ? delta : stored + delta;
    }

    private static Long later(Long stored, Long candidate) {
        if (!measured(candidate)) {
            return stored;
        }
        return stored == null ? candidate : Math.max(stored, candidate);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 分区的规范化键：按键排序后序列化，保证同一组分区字段得到同一键。
     */
    public static String specKey(Map<String, ?> spec) {
        Map<String, Object> sorted = new TreeMap<>();
        if (spec != null) {
            spec.forEach((key, value) -> sorted.put(key, value));
        }
        return Json.write(sorted);
    }

    /** 分区展示名，形如 {@code dt=2024-01-01/hour=12}，用于名称模式匹配。 */
    public static String partitionName(Map<String, Object> spec) {
        if (spec == null || spec.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(spec).forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(key).append('=').append(value == null ? "" : value);
        });
        return builder.toString();
    }

    public PartitionDtos.Partition toResponse(PartitionEntity entity) {
        return new PartitionDtos.Partition(
                new LinkedHashMap<>(entity.getSpec()),
                entity.getRecordCount(),
                entity.getFileSizeInBytes(),
                entity.getFileCount(),
                entity.getLastFileCreationTime(),
                entity.getTotalBuckets(),
                entity.isDone(),
                entity.getCreatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy(),
                new LinkedHashMap<>(entity.getOptions()));
    }

    private List<PartitionDtos.Partition> toResponses(List<PartitionEntity> entities) {
        List<PartitionDtos.Partition> result = new ArrayList<>();
        for (PartitionEntity entity : entities) {
            result.add(toResponse(entity));
        }
        return result;
    }
}
