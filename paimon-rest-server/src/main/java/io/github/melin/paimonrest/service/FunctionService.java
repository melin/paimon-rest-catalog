package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.entity.FunctionEntity;
import io.github.melin.paimonrest.domain.repo.DatabaseRepository;
import io.github.melin.paimonrest.domain.repo.FunctionRepository;
import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.dto.FunctionDtos;
import io.github.melin.paimonrest.dto.TypeDtos;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.Codecs;
import io.github.melin.paimonrest.support.Paging;
import io.github.melin.paimonrest.support.Patterns;
import io.github.melin.paimonrest.support.SchemaSupport;
import io.github.melin.paimonrest.support.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 函数管理。
 *
 * <p>{@code FunctionChange} 的 6 种变更在此分派：{@code setOption} / {@code removeOption} /
 * {@code updateComment} / {@code addDefinition} / {@code updateDefinition} / {@code dropDefinition}。
 * 定义体（{@code sql} / {@code file} / {@code lambda}）按定义名存放，不做解析。
 */
@Service
@RequiredArgsConstructor
public class FunctionService {

    private final FunctionRepository functionRepository;
    private final DatabaseRepository databaseRepository;
    private final TableLookup tableLookup;
    private final CatalogService catalogService;
    private final RestServerProperties properties;

    // ------------------------------------------------------------------ 列举

    @Transactional(readOnly = true)
    public FunctionDtos.ListFunctionsResponse list(String prefix, String databaseName, Integer maxResults,
                                                   String pageToken, String functionNamePattern) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<String> names = new ArrayList<>();
        for (FunctionEntity function : functionRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (Patterns.matches(function.getName(), functionNamePattern)) {
                names.add(function.getName());
            }
        }
        Paging.Slice<String> slice = Paging.slice(names, Paging.offset(pageToken), pageSize(maxResults));
        return new FunctionDtos.ListFunctionsResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public FunctionDtos.ListFunctionDetailsResponse listDetails(String prefix, String databaseName,
                                                                Integer maxResults, String pageToken,
                                                                String functionNamePattern) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        List<FunctionDtos.GetFunctionResponse> details = new ArrayList<>();
        for (FunctionEntity function : functionRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (Patterns.matches(function.getName(), functionNamePattern)) {
                details.add(toResponse(function));
            }
        }
        Paging.Slice<FunctionDtos.GetFunctionResponse> slice =
                Paging.slice(details, Paging.offset(pageToken), pageSize(maxResults));
        return new FunctionDtos.ListFunctionDetailsResponse(slice.items(), slice.nextPageToken());
    }

    @Transactional(readOnly = true)
    public FunctionDtos.ListFunctionsGloballyResponse listGlobally(String prefix, String databaseNamePattern,
                                                                  String functionNamePattern, Integer maxResults,
                                                                  String pageToken) {
        CatalogEntity catalog = catalogService.resolve(prefix);
        List<CommonDtos.Identifier> identifiers = new ArrayList<>();
        for (DatabaseEntity database : databaseRepository.findAllByCatalogIdOrderByNameAsc(catalog.getId())) {
            if (!Patterns.matches(database.getName(), databaseNamePattern)) {
                continue;
            }
            for (FunctionEntity function : functionRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
                if (Patterns.matches(function.getName(), functionNamePattern)) {
                    identifiers.add(new CommonDtos.Identifier(database.getName(), function.getName()));
                }
            }
        }
        Paging.Slice<CommonDtos.Identifier> slice =
                Paging.slice(identifiers, Paging.offset(pageToken), pageSize(maxResults));
        return new FunctionDtos.ListFunctionsGloballyResponse(slice.items(), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ 单函数读写

    @Transactional
    public void create(String prefix, String databaseName, FunctionDtos.CreateFunctionRequest request) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("Function name must not be blank");
        }
        if (functionRepository.existsByDatabaseIdAndName(database.getId(), request.name())) {
            throw ApiException.functionAlreadyExist(request.name());
        }
        FunctionEntity function = new FunctionEntity();
        function.setId(Paging.newId());
        function.setCatalogId(database.getCatalogId());
        function.setDatabaseId(database.getId());
        function.setName(request.name());
        function.setInputParamsDoc(Codecs.writeFields(normalizeFields(request.inputParams())));
        function.setReturnParamsDoc(Codecs.writeFields(normalizeFields(request.returnParams())));
        function.setDefinitionsDoc(Codecs.writeObjectMap(request.definitions()));
        function.setDeterministic(request.deterministic());
        function.setComment(request.comment());
        function.setOptions(request.options() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.options()));
        function.markCreated(RequestContext.principal(), RequestContext.now());
        functionRepository.save(function);
    }

    @Transactional(readOnly = true)
    public FunctionDtos.GetFunctionResponse get(String prefix, String databaseName, String functionName) {
        return toResponse(require(prefix, databaseName, functionName));
    }

    @Transactional
    public void alter(String prefix, String databaseName, String functionName,
                      FunctionDtos.AlterFunctionRequest request) {
        FunctionEntity function = require(prefix, databaseName, functionName);
        List<Map<String, Object>> changes = request == null ? null : request.changes();
        if (changes == null || changes.isEmpty()) {
            return;
        }
        Map<String, Object> definitions = Codecs.readObjectMap(function.getDefinitionsDoc());
        for (Map<String, Object> raw : changes) {
            Map<String, Object> change = Values.map(raw);
            String action = Values.string(change.get("action"));
            if (action == null || action.isBlank()) {
                throw ApiException.badRequest("Function change requires an action");
            }
            switch (action) {
                case "setOption" -> function.getOptions().put(requireKey(change), Values.string(change.get("value")));
                case "removeOption" -> function.getOptions().remove(requireKey(change));
                case "updateComment" -> function.setComment(Values.string(change.get("comment")));
                case "addDefinition" -> {
                    String name = requireDefinitionName(change);
                    if (definitions.containsKey(name)) {
                        throw ApiException.badRequest("Definition already exists: " + name);
                    }
                    definitions.put(name, requireDefinition(change));
                }
                case "updateDefinition" -> {
                    String name = requireDefinitionName(change);
                    if (!definitions.containsKey(name)) {
                        throw ApiException.badRequest("Definition does not exist: " + name);
                    }
                    definitions.put(name, requireDefinition(change));
                }
                case "dropDefinition" -> {
                    String name = requireDefinitionName(change);
                    if (definitions.remove(name) == null) {
                        throw ApiException.badRequest("Definition does not exist: " + name);
                    }
                }
                default -> throw ApiException.badRequest("Unsupported function change action: " + action);
            }
        }
        function.setDefinitionsDoc(Codecs.writeObjectMap(definitions));
        function.touch(RequestContext.principal(), RequestContext.now());
        functionRepository.save(function);
    }

    @Transactional
    public void drop(String prefix, String databaseName, String functionName) {
        functionRepository.delete(require(prefix, databaseName, functionName));
    }

    // ------------------------------------------------------------------ 辅助

    private FunctionEntity require(String prefix, String databaseName, String functionName) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        return functionRepository
                .findByCatalogIdAndDatabaseIdAndName(database.getCatalogId(), database.getId(), functionName)
                .orElseThrow(() -> ApiException.functionNotExist(functionName));
    }

    private List<TypeDtos.DataField> normalizeFields(List<TypeDtos.DataField> fields) {
        List<TypeDtos.DataField> normalized = fields == null ? new ArrayList<>() : fields;
        SchemaSupport.normalizeFields(normalized);
        return normalized;
    }

    private String requireKey(Map<String, Object> change) {
        String key = Values.string(change.get("key"));
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("Function change requires a non-blank key");
        }
        return key;
    }

    private String requireDefinitionName(Map<String, Object> change) {
        String name = Values.string(change.get("name"));
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("Definition change requires a non-blank name");
        }
        return name;
    }

    private Object requireDefinition(Map<String, Object> change) {
        Object definition = change.get("definition");
        if (definition == null) {
            throw ApiException.badRequest("Definition change requires a definition");
        }
        return definition;
    }

    private int pageSize(Integer maxResults) {
        return Paging.pageSize(maxResults, properties.getDefaultPageSize(), properties.getMaxPageSize());
    }

    private FunctionDtos.GetFunctionResponse toResponse(FunctionEntity function) {
        return new FunctionDtos.GetFunctionResponse(
                function.getId(),
                function.getName(),
                Codecs.readFields(function.getInputParamsDoc()),
                Codecs.readFields(function.getReturnParamsDoc()),
                function.getDeterministic(),
                Codecs.readObjectMap(function.getDefinitionsDoc()),
                function.getComment(),
                new LinkedHashMap<>(function.getOptions()),
                function.getOwner(),
                function.getCreatedAt(),
                function.getCreatedBy(),
                function.getUpdatedAt(),
                function.getUpdatedBy());
    }
}
