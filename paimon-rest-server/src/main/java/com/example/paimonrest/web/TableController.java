package com.example.paimonrest.web;

import com.example.paimonrest.dto.TableDtos;
import com.example.paimonrest.service.CredentialService;
import com.example.paimonrest.service.TableService;
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
 * 表端点：列举、创建、注册、变更、重命名、删除，以及提交、回滚、快照与数据访问授权。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class TableController {

    private final TableService tableService;
    private final CredentialService credentialService;

    // ------------------------------------------------------------------ 列举

    @GetMapping("/databases/{database}/tables")
    public TableDtos.ListTablesResponse listTables(@PathVariable String prefix,
                                                   @PathVariable String database,
                                                   @RequestParam(required = false) Integer maxResults,
                                                   @RequestParam(required = false) String pageToken,
                                                   @RequestParam(required = false) String tableNamePattern) {
        return tableService.list(prefix, database, maxResults, pageToken, tableNamePattern);
    }

    @GetMapping("/databases/{database}/table-details")
    public TableDtos.ListTableDetailsResponse listTableDetails(@PathVariable String prefix,
                                                               @PathVariable String database,
                                                               @RequestParam(required = false) Integer maxResults,
                                                               @RequestParam(required = false) String pageToken,
                                                               @RequestParam(required = false) String tableNamePattern,
                                                               @RequestParam(required = false) String tableType) {
        return tableService.listDetails(prefix, database, maxResults, pageToken, tableNamePattern, tableType);
    }

    @GetMapping("/tables")
    public TableDtos.ListTablesGloballyResponse listTablesGlobally(@PathVariable String prefix,
                                                                   @RequestParam(required = false) String databaseNamePattern,
                                                                   @RequestParam(required = false) String tableNamePattern,
                                                                   @RequestParam(required = false) Integer maxResults,
                                                                   @RequestParam(required = false) String pageToken) {
        return tableService.listGlobally(prefix, databaseNamePattern, tableNamePattern, maxResults, pageToken);
    }

    // ------------------------------------------------------------------ 单表

    @PostMapping("/databases/{database}/tables")
    public ResponseEntity<Void> createTable(@PathVariable String prefix,
                                            @PathVariable String database,
                                            @RequestBody(required = false) TableDtos.CreateTableRequest request) {
        tableService.create(prefix, database, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/databases/{database}/register")
    public ResponseEntity<Void> registerTable(@PathVariable String prefix,
                                              @PathVariable String database,
                                              @RequestBody(required = false) TableDtos.RegisterTableRequest request) {
        tableService.register(prefix, database, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/tables/id/{tableId}")
    public TableDtos.GetTableResponse getTableById(@PathVariable String prefix, @PathVariable String tableId) {
        return tableService.getById(prefix, tableId);
    }

    @GetMapping("/databases/{database}/tables/{table}")
    public TableDtos.GetTableResponse getTable(@PathVariable String prefix,
                                               @PathVariable String database,
                                               @PathVariable String table) {
        return tableService.get(prefix, database, table);
    }

    @PostMapping("/databases/{database}/tables/{table}")
    public ResponseEntity<Void> alterTable(@PathVariable String prefix,
                                           @PathVariable String database,
                                           @PathVariable String table,
                                           @RequestBody(required = false) TableDtos.AlterTableRequest request) {
        tableService.alter(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/databases/{database}/tables/{table}")
    public ResponseEntity<Void> dropTable(@PathVariable String prefix,
                                          @PathVariable String database,
                                          @PathVariable String table) {
        tableService.drop(prefix, database, table);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tables/rename")
    public ResponseEntity<Void> renameTable(@PathVariable String prefix,
                                            @RequestBody(required = false) TableDtos.RenameTableRequest request) {
        tableService.rename(prefix, request);
        return ResponseEntity.ok().build();
    }

    // ------------------------------------------------------------------ 提交与回滚

    @PostMapping("/databases/{database}/tables/{table}/commit")
    public TableDtos.CommitTableResponse commitTable(@PathVariable String prefix,
                                                     @PathVariable String database,
                                                     @PathVariable String table,
                                                     @RequestBody(required = false) TableDtos.CommitTableRequest request) {
        return tableService.commit(prefix, database, table, request);
    }

    @PostMapping("/databases/{database}/tables/{table}/rollback")
    public ResponseEntity<Void> rollbackTable(@PathVariable String prefix,
                                              @PathVariable String database,
                                              @PathVariable String table,
                                              @RequestBody(required = false) TableDtos.RollbackTableRequest request) {
        tableService.rollback(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/databases/{database}/tables/{table}/rollback-schema")
    public ResponseEntity<Void> rollbackSchema(@PathVariable String prefix,
                                               @PathVariable String database,
                                               @PathVariable String table,
                                               @RequestBody(required = false) TableDtos.RollbackSchemaRequest request) {
        tableService.rollbackSchema(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }

    // ------------------------------------------------------------------ 快照

    @GetMapping("/databases/{database}/tables/{table}/snapshot")
    public TableDtos.GetTableSnapshotResponse getTableSnapshot(@PathVariable String prefix,
                                                               @PathVariable String database,
                                                               @PathVariable String table) {
        return tableService.getTableSnapshot(prefix, database, table);
    }

    @GetMapping("/databases/{database}/tables/{table}/snapshots")
    public TableDtos.ListSnapshotsResponse listSnapshots(@PathVariable String prefix,
                                                         @PathVariable String database,
                                                         @PathVariable String table,
                                                         @RequestParam(required = false) Integer maxResults,
                                                         @RequestParam(required = false) String pageToken) {
        return tableService.listSnapshots(prefix, database, table, maxResults, pageToken);
    }

    @GetMapping("/databases/{database}/tables/{table}/snapshots/{version}")
    public TableDtos.GetVersionSnapshotResponse getVersionSnapshot(@PathVariable String prefix,
                                                                   @PathVariable String database,
                                                                   @PathVariable String table,
                                                                   @PathVariable String version) {
        return tableService.getVersionSnapshot(prefix, database, table, version);
    }

    // ------------------------------------------------------------------ 数据访问

    @GetMapping("/databases/{database}/tables/{table}/token")
    public TableDtos.GetTableDataTokenResponse getTableToken(@PathVariable String prefix,
                                                             @PathVariable String database,
                                                             @PathVariable String table) {
        return credentialService.token(prefix, database, table);
    }

    @PostMapping("/databases/{database}/tables/{table}/auth")
    public TableDtos.AuthTableQueryResponse authTableQuery(@PathVariable String prefix,
                                                           @PathVariable String database,
                                                           @PathVariable String table,
                                                           @RequestBody(required = false) TableDtos.AuthTableQueryRequest request) {
        return credentialService.auth(prefix, database, table, request);
    }
}
