package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.TagDtos;
import io.github.melin.paimonrest.service.TagService;
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
 * 标签端点。
 */
@RestController
@RequestMapping("/v1/{prefix}/databases/{database}/tables/{table}/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public TagDtos.ListTagsResponse listTags(@PathVariable String prefix,
                                             @PathVariable String database,
                                             @PathVariable String table,
                                             @RequestParam(required = false) Integer maxResults,
                                             @RequestParam(required = false) String pageToken,
                                             @RequestParam(required = false) String tagNamePrefix) {
        return tagService.list(prefix, database, table, maxResults, pageToken, tagNamePrefix);
    }

    @PostMapping
    public ResponseEntity<Void> createTag(@PathVariable String prefix,
                                          @PathVariable String database,
                                          @PathVariable String table,
                                          @RequestBody(required = false) TagDtos.CreateTagRequest request) {
        tagService.create(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{tag}")
    public TagDtos.GetTagResponse getTag(@PathVariable String prefix,
                                         @PathVariable String database,
                                         @PathVariable String table,
                                         @PathVariable String tag) {
        return tagService.get(prefix, database, table, tag);
    }

    @DeleteMapping("/{tag}")
    public ResponseEntity<Void> deleteTag(@PathVariable String prefix,
                                          @PathVariable String database,
                                          @PathVariable String table,
                                          @PathVariable String tag) {
        tagService.delete(prefix, database, table, tag);
        return ResponseEntity.ok().build();
    }
}
