package com.example.paimonrest.web;

import com.example.paimonrest.dto.PartitionDtos;
import com.example.paimonrest.service.PartitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分区端点。
 */
@RestController
@RequestMapping("/v1/{prefix}/databases/{database}/tables/{table}/partitions")
@RequiredArgsConstructor
public class PartitionController {

    private final PartitionService partitionService;

    @GetMapping
    public PartitionDtos.ListPartitionsResponse listPartitions(@PathVariable String prefix,
                                                              @PathVariable String database,
                                                              @PathVariable String table,
                                                              @RequestParam(required = false) Integer maxResults,
                                                              @RequestParam(required = false) String pageToken,
                                                              @RequestParam(required = false) String partitionNamePattern) {
        return partitionService.list(prefix, database, table, maxResults, pageToken, partitionNamePattern);
    }

    @PostMapping
    public PartitionDtos.CreatePartitionsResponse createPartitions(@PathVariable String prefix,
                                                                  @PathVariable String database,
                                                                  @PathVariable String table,
                                                                  @RequestBody(required = false) PartitionDtos.CreatePartitionsRequest request) {
        return partitionService.create(prefix, database, table, request);
    }

    @PostMapping("/drop")
    public PartitionDtos.DropPartitionsResponse dropPartitions(@PathVariable String prefix,
                                                               @PathVariable String database,
                                                               @PathVariable String table,
                                                               @RequestBody(required = false) PartitionDtos.DropPartitionsRequest request) {
        return partitionService.drop(prefix, database, table, request);
    }

    @PostMapping("/mark")
    public ResponseEntity<Void> markDonePartitions(@PathVariable String prefix,
                                                   @PathVariable String database,
                                                   @PathVariable String table,
                                                   @RequestBody(required = false) PartitionDtos.MarkDonePartitionsRequest request) {
        partitionService.markDone(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/list-by-names")
    public PartitionDtos.ListPartitionsResponse listPartitionsByNames(@PathVariable String prefix,
                                                                      @PathVariable String database,
                                                                      @PathVariable String table,
                                                                      @RequestBody(required = false) PartitionDtos.ListPartitionsByNamesRequest request) {
        return partitionService.listByNames(prefix, database, table, request);
    }

    @PostMapping("/list-by-filter")
    public PartitionDtos.ListPartitionsResponse listPartitionsByFilter(@PathVariable String prefix,
                                                                       @PathVariable String database,
                                                                       @PathVariable String table,
                                                                       @RequestBody(required = false) PartitionDtos.ListPartitionsByFilterRequest request) {
        return partitionService.listByFilter(prefix, database, table, request);
    }
}
