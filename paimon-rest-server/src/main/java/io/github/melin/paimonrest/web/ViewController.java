package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.TableDtos;
import io.github.melin.paimonrest.dto.ViewDtos;
import io.github.melin.paimonrest.service.ViewService;
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
 * SQL 视图端点。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class ViewController {

    private final ViewService viewService;

    @GetMapping("/databases/{database}/views")
    public ViewDtos.ListViewsResponse listViews(@PathVariable String prefix,
                                                @PathVariable String database,
                                                @RequestParam(required = false) Integer maxResults,
                                                @RequestParam(required = false) String pageToken,
                                                @RequestParam(required = false) String viewNamePattern) {
        return viewService.list(prefix, database, maxResults, pageToken, viewNamePattern);
    }

    @PostMapping("/databases/{database}/views")
    public ResponseEntity<Void> createView(@PathVariable String prefix,
                                           @PathVariable String database,
                                           @RequestBody(required = false) ViewDtos.CreateViewRequest request) {
        viewService.create(prefix, database, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/databases/{database}/view-details")
    public ViewDtos.ListViewDetailsResponse listViewDetails(@PathVariable String prefix,
                                                            @PathVariable String database,
                                                            @RequestParam(required = false) Integer maxResults,
                                                            @RequestParam(required = false) String pageToken,
                                                            @RequestParam(required = false) String viewNamePattern) {
        return viewService.listDetails(prefix, database, maxResults, pageToken, viewNamePattern);
    }

    @GetMapping("/views")
    public ViewDtos.ListViewsGloballyResponse listViewsGlobally(@PathVariable String prefix,
                                                                @RequestParam(required = false) String databaseNamePattern,
                                                                @RequestParam(required = false) String viewNamePattern,
                                                                @RequestParam(required = false) Integer maxResults,
                                                                @RequestParam(required = false) String pageToken) {
        return viewService.listGlobally(prefix, databaseNamePattern, viewNamePattern, maxResults, pageToken);
    }

    @GetMapping("/databases/{database}/views/{view}")
    public ViewDtos.GetViewResponse getView(@PathVariable String prefix,
                                            @PathVariable String database,
                                            @PathVariable String view) {
        return viewService.get(prefix, database, view);
    }

    @PostMapping("/databases/{database}/views/{view}")
    public ResponseEntity<Void> alterView(@PathVariable String prefix,
                                          @PathVariable String database,
                                          @PathVariable String view,
                                          @RequestBody(required = false) ViewDtos.AlterViewRequest request) {
        viewService.alter(prefix, database, view, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/databases/{database}/views/{view}")
    public ResponseEntity<Void> dropView(@PathVariable String prefix,
                                         @PathVariable String database,
                                         @PathVariable String view) {
        viewService.drop(prefix, database, view);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/views/rename")
    public ResponseEntity<Void> renameView(@PathVariable String prefix,
                                           @RequestBody(required = false) TableDtos.RenameTableRequest request) {
        viewService.rename(prefix, request);
        return ResponseEntity.ok().build();
    }
}
