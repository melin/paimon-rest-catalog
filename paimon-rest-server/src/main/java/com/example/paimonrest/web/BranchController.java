package com.example.paimonrest.web;

import com.example.paimonrest.dto.BranchDtos;
import com.example.paimonrest.service.BranchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分支端点。
 */
@RestController
@RequestMapping("/v1/{prefix}/databases/{database}/tables/{table}/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    @GetMapping
    public BranchDtos.ListBranchesResponse listBranches(@PathVariable String prefix,
                                                        @PathVariable String database,
                                                        @PathVariable String table) {
        return branchService.list(prefix, database, table);
    }

    @PostMapping
    public ResponseEntity<Void> createBranch(@PathVariable String prefix,
                                             @PathVariable String database,
                                             @PathVariable String table,
                                             @RequestBody(required = false) BranchDtos.CreateBranchRequest request) {
        branchService.create(prefix, database, table, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{branch}")
    public ResponseEntity<Void> dropBranch(@PathVariable String prefix,
                                           @PathVariable String database,
                                           @PathVariable String table,
                                           @PathVariable String branch) {
        branchService.drop(prefix, database, table, branch);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{branch}/rename")
    public ResponseEntity<Void> renameBranch(@PathVariable String prefix,
                                             @PathVariable String database,
                                             @PathVariable String table,
                                             @PathVariable String branch,
                                             @RequestBody(required = false) BranchDtos.RenameBranchRequest request) {
        branchService.rename(prefix, database, table, branch, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{branch}/forward")
    public ResponseEntity<Void> forwardBranch(@PathVariable String prefix,
                                              @PathVariable String database,
                                              @PathVariable String table,
                                              @PathVariable String branch,
                                              @RequestBody(required = false) BranchDtos.ForwardBranchRequest request) {
        branchService.forward(prefix, database, table, branch, request);
        return ResponseEntity.ok().build();
    }
}
