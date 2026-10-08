package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.repo.ConsumerRepository;
import io.github.melin.paimonrest.domain.repo.DatabaseRepository;
import io.github.melin.paimonrest.domain.repo.FunctionRepository;
import io.github.melin.paimonrest.domain.repo.PartitionRepository;
import io.github.melin.paimonrest.domain.repo.SemanticViewRepository;
import io.github.melin.paimonrest.domain.repo.TableRepository;
import io.github.melin.paimonrest.domain.repo.ViewRepository;
import io.github.melin.paimonrest.dto.DatabaseDtos;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * database（命名空间）的增删改查。
 */
@Service
@RequiredArgsConstructor
public class DatabaseService {

    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final ViewRepository viewRepository;
    private final FunctionRepository functionRepository;
    private final SemanticViewRepository semanticViewRepository;
    private final PartitionRepository partitionRepository;
    private final ConsumerRepository consumerRepository;
    private final CatalogService catalogService;
    private final RestServerProperties properties;

    @Transactional(readOnly = true)
    public DatabaseDtos.ListDatabasesResponse list(String prefix, Integer maxResults, String pageToken) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        List<DatabaseEntity> databases = databaseRepository.findAllByCatalogIdOrderByNameAsc(catalog.getId());
        List<String> names = new ArrayList<>();
        for (DatabaseEntity database : databases) {
            names.add(database.getName());
        }
        Paging.Slice<String> slice = Paging.slice(names,
                Paging.offset(pageToken),
                Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new DatabaseDtos.ListDatabasesResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional
    public void create(String prefix, DatabaseDtos.CreateDatabaseRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("Database name must not be blank");
        }
        CatalogEntity catalog = catalogService.resolve(prefix);
        if (databaseRepository.existsByCatalogIdAndName(catalog.getId(), request.name())) {
            throw ApiException.databaseAlreadyExist(request.name());
        }
        DatabaseEntity database = new DatabaseEntity();
        database.setId(Paging.newId());
        database.setCatalogId(catalog.getId());
        database.setName(request.name());
        database.setOptions(request.options() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.options()));
        database.setLocation(Paths.databaseLocation(catalog.getWarehouse(), request.name()));
        database.markCreated(RequestContext.principal(), RequestContext.now());
        databaseRepository.save(database);
    }

    @Transactional(readOnly = true)
    public DatabaseDtos.GetDatabaseResponse get(String prefix, String name) {
        return toResponse(require(prefix, name));
    }

    /**
     * 删除 database。
     *
     * <p>规格中的删除接口没有 cascade 参数，这里按级联删除实现：先清理库内的表、视图、
     * 函数、语义视图及其附属对象，再删除 database 本身。
     */
    @Transactional
    public void drop(String prefix, String name) {
        cascadeDelete(require(prefix, name));
    }

    /**
     * 删除 catalog 下的全部 database。
     *
     * <p>供管理 API 删除 catalog 时级联使用，与 {@link #drop(String, String)} 走同一段清理逻辑，
     * 保证两条删除路径不会产生不一致的残留。
     *
     * @return 被删除的 database 数量
     */
    @Transactional
    public int dropAll(String prefix) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        List<DatabaseEntity> databases =
                databaseRepository.findAllByCatalogIdOrderByNameAsc(catalog.getId());
        for (DatabaseEntity database : databases) {
            cascadeDelete(database);
        }
        return databases.size();
    }

    /** 清理 database 及其内部的全部从属对象。 */
    private void cascadeDelete(DatabaseEntity database) {
        tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).forEach(table -> {
            partitionRepository.deleteByTableId(table.getId());
            consumerRepository.deleteByTableId(table.getId());
            tableRepository.delete(table);
        });
        viewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).forEach(viewRepository::delete);
        functionRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).forEach(functionRepository::delete);
        semanticViewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())
                .forEach(semanticViewRepository::delete);
        databaseRepository.delete(database);
    }

    @Transactional
    public DatabaseDtos.AlterDatabaseResponse alter(String prefix, String name, DatabaseDtos.AlterDatabaseRequest request) {
        DatabaseEntity database = require(prefix, name);
        Map<String, String> options = new LinkedHashMap<>(database.getOptions());
        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        if (request != null) {
            if (request.removals() != null) {
                for (String key : request.removals()) {
                    if (key == null) {
                        continue;
                    }
                    if (options.remove(key) != null) {
                        removed.add(key);
                    } else {
                        missing.add(key);
                    }
                }
            }
            if (request.updates() != null) {
                request.updates().forEach((key, value) -> {
                    if (key != null) {
                        options.put(key, value);
                        updated.add(key);
                    }
                });
            }
        }

        database.setOptions(options);
        database.touch(RequestContext.principal(), RequestContext.now());
        databaseRepository.save(database);
        return new DatabaseDtos.AlterDatabaseResponse(removed, updated, missing);
    }

    /** 按名称取得 database，不存在时抛出 404。 */
    @Transactional(readOnly = true)
    public DatabaseEntity require(String prefix, String name) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        return databaseRepository.findByCatalogIdAndName(catalog.getId(), name)
                .orElseThrow(() -> ApiException.databaseNotExist(name));
    }

    public DatabaseDtos.GetDatabaseResponse toResponse(DatabaseEntity database) {
        return new DatabaseDtos.GetDatabaseResponse(
                database.getId(),
                database.getName(),
                database.getLocation(),
                new LinkedHashMap<>(database.getOptions()),
                database.getOwner(),
                database.getCreatedAt(),
                database.getCreatedBy(),
                database.getUpdatedAt(),
                database.getUpdatedBy());
    }
}
