package com.example.paimonrest.service;

import com.example.paimonrest.config.RequestContext;
import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.domain.entity.CatalogEntity;
import com.example.paimonrest.domain.entity.DatabaseEntity;
import com.example.paimonrest.domain.entity.ViewEntity;
import com.example.paimonrest.domain.repo.DatabaseRepository;
import com.example.paimonrest.domain.repo.ViewRepository;
import com.example.paimonrest.dto.CommonDtos;
import com.example.paimonrest.dto.TableDtos;
import com.example.paimonrest.dto.TypeDtos;
import com.example.paimonrest.dto.ViewDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Codecs;
import com.example.paimonrest.support.Paging;
import com.example.paimonrest.support.Patterns;
import com.example.paimonrest.support.SchemaSupport;
import com.example.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL 视图管理。
 *
 * <p>{@code ViewChange} 的 6 种变更在此分派：{@code setOption} / {@code removeOption} /
 * {@code updateComment} / {@code addDialect} / {@code updateDialect} / {@code dropDialect}。
 * 方言查询语句存放在 {@code dialects} 映射中，默认语句为 {@code query}。
 */
@Service
@RequiredArgsConstructor
public class ViewService {

    private final ViewRepository viewRepository;
    private final DatabaseRepository databaseRepository;
    private final TableLookup tableLookup;
    private final CatalogService catalogService;
    private final RestServerProperties properties;

    // ------------------------------------------------------------------ 列举

    @Transactional(readOnly = true)
    public ViewDtos.ListViewsResponse list(String prefix, String databaseName, Integer maxResults,
                                           String pageToken, String viewNamePattern) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<String> names = new ArrayList<>();
        for (ViewEntity view : viewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (Patterns.matches(view.getName(), viewNamePattern)) {
                names.add(view.getName());
            }
        }
        Paging.Slice<String> slice = Paging.slice(names, Paging.offset(pageToken), pageSize(maxResults));
        return new ViewDtos.ListViewsResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public ViewDtos.ListViewDetailsResponse listDetails(String prefix, String databaseName, Integer maxResults,
                                                        String pageToken, String viewNamePattern) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<ViewDtos.GetViewResponse> details = new ArrayList<>();
        for (ViewEntity view : viewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (Patterns.matches(view.getName(), viewNamePattern)) {
                details.add(toResponse(view));
            }
        }
        Paging.Slice<ViewDtos.GetViewResponse> slice =
                Paging.slice(details, Paging.offset(pageToken), pageSize(maxResults));
        return new ViewDtos.ListViewDetailsResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public ViewDtos.ListViewsGloballyResponse listGlobally(String prefix, String databaseNamePattern,
                                                           String viewNamePattern, Integer maxResults,
                                                           String pageToken) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        List<CommonDtos.Identifier> identifiers = new ArrayList<>();
        for (DatabaseEntity database : databaseRepository.findAllByCatalogIdOrderByNameAsc(catalog.getId())) {
            if (!Patterns.matches(database.getName(), databaseNamePattern)) {
                continue;
            }
            for (ViewEntity view : viewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
                if (Patterns.matches(view.getName(), viewNamePattern)) {
                    identifiers.add(new CommonDtos.Identifier(database.getName(), view.getName()));
                }
            }
        }
        Paging.Slice<CommonDtos.Identifier> slice =
                Paging.slice(identifiers, Paging.offset(pageToken), pageSize(maxResults));
        return new ViewDtos.ListViewsGloballyResponse(slice.items(), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ 单视图读写

    @Transactional
    public void create(String prefix, String databaseName, ViewDtos.CreateViewRequest request) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        if (request == null || request.identifier() == null) {
            throw ApiException.badRequest("identifier is required");
        }
        if (request.identifier().database() != null
                && !request.identifier().database().equals(databaseName)) {
            throw ApiException.badRequest("identifier.database does not match the database in the path");
        }
        String name = request.identifier().object();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("identifier.object must not be blank");
        }
        if (viewRepository.existsByDatabaseIdAndName(database.getId(), name)) {
            throw ApiException.viewAlreadyExist(name);
        }
        TypeDtos.ViewSchema schema = request.schema() == null ? new TypeDtos.ViewSchema() : request.schema();
        SchemaSupport.normalize(schema);

        ViewEntity view = new ViewEntity();
        view.setId(Paging.newId());
        view.setCatalogId(database.getCatalogId());
        view.setDatabaseId(database.getId());
        view.setName(name);
        view.setViewSchemaDoc(Codecs.write(schema));
        view.markCreated(RequestContext.principal(), RequestContext.now());
        viewRepository.save(view);
    }

    @Transactional(readOnly = true)
    public ViewDtos.GetViewResponse get(String prefix, String databaseName, String viewName) {
        return toResponse(require(prefix, databaseName, viewName));
    }

    @Transactional
    public void alter(String prefix, String databaseName, String viewName, ViewDtos.AlterViewRequest request) {
        ViewEntity view = require(prefix, databaseName, viewName);
        List<Map<String, Object>> changes = request == null ? null : request.changes();
        if (changes == null || changes.isEmpty()) {
            return;
        }
        TypeDtos.ViewSchema schema = Codecs.readViewSchema(view.getViewSchemaDoc());
        applyChanges(schema, changes);
        SchemaSupport.normalize(schema);
        view.setViewSchemaDoc(Codecs.write(schema));
        view.touch(RequestContext.principal(), RequestContext.now());
        viewRepository.save(view);
    }

    @Transactional
    public void drop(String prefix, String databaseName, String viewName) {
        viewRepository.delete(require(prefix, databaseName, viewName));
    }

    @Transactional
    public void rename(String prefix, TableDtos.RenameTableRequest request) {
        if (request == null || request.source() == null || request.destination() == null) {
            throw ApiException.badRequest("rename requires source and destination identifiers");
        }
        String sourceDatabase = requirePart(request.source().database(), "source.database");
        String destinationDatabase = requirePart(request.destination().database(), "destination.database");
        String sourceView = requirePart(request.source().object(), "source.object");
        String destinationView = requirePart(request.destination().object(), "destination.object");

        DatabaseEntity targetDatabase = tableLookup.requireDatabase(prefix, destinationDatabase);
        ViewEntity view = require(prefix, sourceDatabase, sourceView);
        if (viewRepository.existsByDatabaseIdAndName(targetDatabase.getId(), destinationView)) {
            throw ApiException.viewAlreadyExist(destinationView);
        }
        view.setDatabaseId(targetDatabase.getId());
        view.setName(destinationView);
        view.touch(RequestContext.principal(), RequestContext.now());
        viewRepository.save(view);
    }

    // ------------------------------------------------------------------ 变更

    private void applyChanges(TypeDtos.ViewSchema schema, List<Map<String, Object>> changes) {
        for (Map<String, Object> raw : changes) {
            Map<String, Object> change = Values.map(raw);
            String action = Values.string(change.get("action"));
            if (action == null || action.isBlank()) {
                throw ApiException.badRequest("View change requires an action");
            }
            switch (action) {
                case "setOption" -> schema.getOptions().put(requireKey(change), Values.string(change.get("value")));
                case "removeOption" -> schema.getOptions().remove(requireKey(change));
                case "updateComment" -> schema.setComment(Values.string(change.get("comment")));
                case "addDialect" -> schema.getDialects().put(requireDialect(change), requireQuery(change));
                case "updateDialect" -> {
                    String dialect = requireDialect(change);
                    if (!schema.getDialects().containsKey(dialect)) {
                        throw ApiException.badRequest("Dialect does not exist: " + dialect);
                    }
                    schema.getDialects().put(dialect, requireQuery(change));
                }
                case "dropDialect" -> schema.getDialects().remove(requireDialect(change));
                default -> throw ApiException.badRequest("Unsupported view change action: " + action);
            }
        }
    }

    private String requireKey(Map<String, Object> change) {
        String key = Values.string(change.get("key"));
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("View change requires a non-blank key");
        }
        return key;
    }

    private String requireDialect(Map<String, Object> change) {
        String dialect = Values.string(change.get("dialect"));
        if (dialect == null || dialect.isBlank()) {
            throw ApiException.badRequest("Dialect change requires a non-blank dialect");
        }
        return dialect;
    }

    private String requireQuery(Map<String, Object> change) {
        String query = Values.string(change.get("query"));
        if (query == null) {
            throw ApiException.badRequest("Dialect change requires a query");
        }
        return query;
    }

    // ------------------------------------------------------------------ 辅助

    private ViewEntity require(String prefix, String databaseName, String viewName) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        return viewRepository.findByCatalogIdAndDatabaseIdAndName(database.getCatalogId(), database.getId(), viewName)
                .orElseThrow(() -> ApiException.viewNotExist(viewName));
    }

    private String requirePart(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " must not be blank");
        }
        return value;
    }

    private int pageSize(Integer maxResults) {
        return Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize());
    }

    private ViewDtos.GetViewResponse toResponse(ViewEntity view) {
        return new ViewDtos.GetViewResponse(
                view.getId(),
                view.getName(),
                Codecs.readViewSchema(view.getViewSchemaDoc()),
                view.getOwner(),
                view.getCreatedAt(),
                view.getCreatedBy(),
                view.getUpdatedAt(),
                view.getUpdatedBy());
    }
}
