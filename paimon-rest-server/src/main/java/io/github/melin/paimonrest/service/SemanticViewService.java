package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.entity.SemanticViewEntity;
import io.github.melin.paimonrest.domain.repo.SemanticViewRepository;
import io.github.melin.paimonrest.dto.SemanticViewDtos;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.ResourceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 语义视图管理（实验性控制面）。
 *
 * <p>与 SQL 视图是两类对象：只列出名称、不与其他视图混排；
 * 定义整篇保存不做解析，超过 1 MiB UTF-8 字节时返回 413 且不落库。
 */
@Service
@RequiredArgsConstructor
public class SemanticViewService {

    private static final int MAX_CONTENT_BYTES = 1048576;

    private final SemanticViewRepository semanticViewRepository;
    private final TableLookup tableLookup;
    private final RestServerProperties properties;

    @Transactional(readOnly = true)
    public SemanticViewDtos.ListSemanticViewsResponse list(String prefix, String databaseName,
                                                           Integer maxResults, String pageToken) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<String> names = new ArrayList<>();
        for (SemanticViewEntity view : semanticViewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            names.add(view.getName());
        }
        Paging.Slice<String> slice = Paging.slice(names,
                Paging.offset(pageToken),
                Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new SemanticViewDtos.ListSemanticViewsResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public SemanticViewDtos.GetSemanticViewResponse get(String prefix, String databaseName, String name) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        SemanticViewEntity view = semanticViewRepository
                .findByCatalogIdAndDatabaseIdAndName(database.getCatalogId(), database.getId(), name)
                .orElseThrow(() -> ApiException.semanticViewNotExist(name));
        return toResponse(view);
    }

    /**
     * 整篇 upsert：同一名称重复提交即覆盖，未提交过的名称则创建。
     */
    @Transactional
    public void upsert(String prefix, String databaseName, String name,
                       SemanticViewDtos.UpsertSemanticViewRequest request) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("Semantic view name must not be blank");
        }
        if (request == null || request.definition() == null) {
            throw ApiException.badRequest("definition is required");
        }
        String format = request.definition().format();
        String content = request.definition().content();
        if (format == null || format.isBlank()) {
            throw ApiException.badRequest("definition.format must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("definition.content must not be blank");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw ApiException.payloadTooLarge(ResourceType.SEMANTIC_VIEW, name,
                    "Definition content exceeds 1 MiB in UTF-8 bytes; no content is stored or truncated.");
        }

        SemanticViewEntity view = semanticViewRepository
                .findByCatalogIdAndDatabaseIdAndName(database.getCatalogId(), database.getId(), name)
                .orElseGet(() -> {
                    SemanticViewEntity created = new SemanticViewEntity();
                    created.setId(Paging.newId());
                    created.setCatalogId(database.getCatalogId());
                    created.setDatabaseId(database.getId());
                    created.setName(name);
                    created.markCreated(RequestContext.principal(), RequestContext.now());
                    return created;
                });
        view.setFormat(format);
        view.setContent(content);
        view.touch(RequestContext.principal(), RequestContext.now());
        semanticViewRepository.save(view);
    }

    @Transactional
    public void drop(String prefix, String databaseName, String name) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        SemanticViewEntity view = semanticViewRepository
                .findByCatalogIdAndDatabaseIdAndName(database.getCatalogId(), database.getId(), name)
                .orElseThrow(() -> ApiException.semanticViewNotExist(name));
        semanticViewRepository.delete(view);
    }

    private SemanticViewDtos.GetSemanticViewResponse toResponse(SemanticViewEntity view) {
        return new SemanticViewDtos.GetSemanticViewResponse(
                view.getName(),
                new SemanticViewDtos.SemanticViewDefinition(view.getFormat(), view.getContent()));
    }
}
