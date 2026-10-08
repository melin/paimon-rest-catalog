package com.example.paimonrest.web;

import com.example.paimonrest.dto.FunctionDtos;
import com.example.paimonrest.service.FunctionService;
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
 * 函数端点。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class FunctionController {

    private final FunctionService functionService;

    @GetMapping("/databases/{database}/functions")
    public FunctionDtos.ListFunctionsResponse listFunctions(@PathVariable String prefix,
                                                            @PathVariable String database,
                                                            @RequestParam(required = false) Integer maxResults,
                                                            @RequestParam(required = false) String pageToken,
                                                            @RequestParam(required = false) String functionNamePattern) {
        return functionService.list(prefix, database, maxResults, pageToken, functionNamePattern);
    }

    @PostMapping("/databases/{database}/functions")
    public ResponseEntity<Void> createFunction(@PathVariable String prefix,
                                               @PathVariable String database,
                                               @RequestBody(required = false) FunctionDtos.CreateFunctionRequest request) {
        functionService.create(prefix, database, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/databases/{database}/function-details")
    public FunctionDtos.ListFunctionDetailsResponse listFunctionDetails(@PathVariable String prefix,
                                                                        @PathVariable String database,
                                                                        @RequestParam(required = false) Integer maxResults,
                                                                        @RequestParam(required = false) String pageToken,
                                                                        @RequestParam(required = false) String functionNamePattern) {
        return functionService.listDetails(prefix, database, maxResults, pageToken, functionNamePattern);
    }

    @GetMapping("/functions")
    public FunctionDtos.ListFunctionsGloballyResponse listFunctionsGlobally(@PathVariable String prefix,
                                                                            @RequestParam(required = false) String databaseNamePattern,
                                                                            @RequestParam(required = false) String functionNamePattern,
                                                                            @RequestParam(required = false) Integer maxResults,
                                                                            @RequestParam(required = false) String pageToken) {
        return functionService.listGlobally(prefix, databaseNamePattern, functionNamePattern, maxResults, pageToken);
    }

    @GetMapping("/databases/{database}/functions/{function}")
    public FunctionDtos.GetFunctionResponse getFunction(@PathVariable String prefix,
                                                        @PathVariable String database,
                                                        @PathVariable String function) {
        return functionService.get(prefix, database, function);
    }

    @PostMapping("/databases/{database}/functions/{function}")
    public ResponseEntity<Void> alterFunction(@PathVariable String prefix,
                                              @PathVariable String database,
                                              @PathVariable String function,
                                              @RequestBody(required = false) FunctionDtos.AlterFunctionRequest request) {
        functionService.alter(prefix, database, function, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/databases/{database}/functions/{function}")
    public ResponseEntity<Void> dropFunction(@PathVariable String prefix,
                                             @PathVariable String database,
                                             @PathVariable String function) {
        functionService.drop(prefix, database, function);
        return ResponseEntity.ok().build();
    }
}
