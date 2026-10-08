package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ConsumerDtos;
import io.github.melin.paimonrest.service.ConsumerService;
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
 * 流式消费者端点。
 */
@RestController
@RequestMapping("/v1/{prefix}/databases/{database}/tables/{table}/consumers")
@RequiredArgsConstructor
public class ConsumerController {

    private final ConsumerService consumerService;

    @GetMapping
    public ConsumerDtos.ListConsumersResponse listConsumers(@PathVariable String prefix,
                                                            @PathVariable String database,
                                                            @PathVariable String table,
                                                            @RequestParam(required = false) Integer maxResults,
                                                            @RequestParam(required = false) String pageToken) {
        return consumerService.list(prefix, database, table, maxResults, pageToken);
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> resetConsumer(@PathVariable String prefix,
                                              @PathVariable String database,
                                              @PathVariable String table,
                                              @RequestBody(required = false) ConsumerDtos.ResetConsumerRequest request) {
        consumerService.reset(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }
}
