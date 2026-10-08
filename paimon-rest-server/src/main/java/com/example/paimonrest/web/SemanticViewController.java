package com.example.paimonrest.web;

import com.example.paimonrest.dto.SemanticViewDtos;
import com.example.paimonrest.service.SemanticViewService;
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
 * 语义视图端点（实验性）。
 *
 * <p>{@code POST} 是整篇 upsert：同名重复提交即覆盖，不区分创建与修改。
 */
@RestController
@RequestMapping("/v1/{prefix}/databases/{database}/semantic-views")
@RequiredArgsConstructor
public class SemanticViewController {

    private final SemanticViewService semanticViewService;

    @GetMapping
    public SemanticViewDtos.ListSemanticViewsResponse listSemanticViews(@PathVariable String prefix,
                                                                        @PathVariable String database,
                                                                        @RequestParam(required = false) Integer maxResults,
                                                                        @RequestParam(required = false) String pageToken) {
        return semanticViewService.list(prefix, database, maxResults, pageToken);
    }

    @GetMapping("/{semanticView}")
    public SemanticViewDtos.GetSemanticViewResponse getSemanticView(@PathVariable String prefix,
                                                                    @PathVariable String database,
                                                                    @PathVariable String semanticView) {
        return semanticViewService.get(prefix, database, semanticView);
    }

    @PostMapping("/{semanticView}")
    public ResponseEntity<Void> upsertSemanticView(@PathVariable String prefix,
                                                   @PathVariable String database,
                                                   @PathVariable String semanticView,
                                                   @RequestBody(required = false) SemanticViewDtos.UpsertSemanticViewRequest request) {
        semanticViewService.upsert(prefix, database, semanticView, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{semanticView}")
    public ResponseEntity<Void> dropSemanticView(@PathVariable String prefix,
                                                 @PathVariable String database,
                                                 @PathVariable String semanticView) {
        semanticViewService.drop(prefix, database, semanticView);
        return ResponseEntity.ok().build();
    }
}
