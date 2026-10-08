package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.DatabaseDtos;
import io.github.melin.paimonrest.service.DatabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * database（命名空间）端点。
 *
 * <p>注意 {@code POST} 的两种语义：作用于集合路径是创建，作用于单个 database 是修改属性。
 */
@RestController
@RequestMapping("/v1/{prefix}/databases")
@RequiredArgsConstructor
public class DatabaseController {

    private final DatabaseService databaseService;

    @GetMapping
    public DatabaseDtos.ListDatabasesResponse list(@PathVariable String prefix,
                                                   @RequestParam(required = false) Integer maxResults,
                                                   @RequestParam(required = false) String pageToken) {
        return databaseService.list(prefix, maxResults, pageToken);
    }

    @PostMapping
    public ResponseEntity<Void> create(@PathVariable String prefix,
                                       @RequestBody(required = false) DatabaseDtos.CreateDatabaseRequest request) {
        databaseService.create(prefix, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{database}")
    public DatabaseDtos.GetDatabaseResponse get(@PathVariable String prefix, @PathVariable String database) {
        return databaseService.get(prefix, database);
    }

    @DeleteMapping("/{database}")
    public ResponseEntity<Void> drop(@PathVariable String prefix, @PathVariable String database) {
        databaseService.drop(prefix, database);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{database}")
    public DatabaseDtos.AlterDatabaseResponse alter(@PathVariable String prefix,
                                                    @PathVariable String database,
                                                    @RequestBody(required = false) DatabaseDtos.AlterDatabaseRequest request) {
        return databaseService.alter(prefix, database, request);
    }
}
