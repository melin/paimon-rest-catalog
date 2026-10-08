package com.example.paimonrest.service;

import com.example.paimonrest.config.RequestContext;
import com.example.paimonrest.domain.entity.BranchEntity;
import com.example.paimonrest.domain.entity.TableEntity;
import com.example.paimonrest.domain.entity.TagEntity;
import com.example.paimonrest.domain.repo.BranchRepository;
import com.example.paimonrest.domain.repo.TableSnapshotRepository;
import com.example.paimonrest.domain.repo.TagRepository;
import com.example.paimonrest.dto.BranchDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Paging;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表分支管理。
 *
 * <p>分支是一条指向某个快照的命名历史线。创建时可从标签（{@code fromTag}）派生，
 * 未指定时取表的最新快照。
 */
@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final TagRepository tagRepository;
    private final TableSnapshotRepository snapshotRepository;
    private final TableLookup tableLookup;

    @Transactional(readOnly = true)
    public BranchDtos.ListBranchesResponse list(String prefix, String database, String table) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        List<String> branches = new ArrayList<>();
        for (BranchEntity branch : branchRepository.findAllByTableIdOrderByNameAsc(target.getId())) {
            branches.add(branch.getName());
        }
        return new BranchDtos.ListBranchesResponse(branches);
    }

    @Transactional
    public void create(String prefix, String database, String table, BranchDtos.CreateBranchRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request == null || request.branch() == null || request.branch().isBlank()) {
            throw ApiException.badRequest("Branch name must not be blank");
        }
        if (branchRepository.existsByTableIdAndName(target.getId(), request.branch())) {
            throw ApiException.branchAlreadyExist(request.branch());
        }
        Long snapshotId;
        if (request.fromTag() != null && !request.fromTag().isBlank()) {
            TagEntity tag = tagRepository.findByTableIdAndTagName(target.getId(), request.fromTag())
                    .orElseThrow(() -> ApiException.tagNotExist(request.fromTag()));
            snapshotId = tag.getSnapshotId();
        } else {
            snapshotId = snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(target.getId())
                    .map(snapshot -> snapshot.getSnapshotId())
                    .orElse(null);
        }

        BranchEntity branch = new BranchEntity();
        branch.setId(Paging.newId());
        branch.setTableId(target.getId());
        branch.setName(request.branch());
        branch.setSnapshotId(snapshotId);
        branch.setOptions(new LinkedHashMap<>());
        branch.markCreated(RequestContext.principal(), RequestContext.now());
        branchRepository.save(branch);
    }

    @Transactional
    public void drop(String prefix, String database, String table, String branchName) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        BranchEntity branch = require(target.getId(), branchName);
        branchRepository.delete(branch);
    }

    @Transactional
    public void rename(String prefix, String database, String table, String branchName,
                       BranchDtos.RenameBranchRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        BranchEntity branch = require(target.getId(), branchName);
        String toBranch = request == null ? null : request.toBranch();
        if (toBranch == null || toBranch.isBlank()) {
            throw ApiException.badRequest("toBranch must not be blank");
        }
        if (branchRepository.existsByTableIdAndName(target.getId(), toBranch)) {
            throw ApiException.branchAlreadyExist(toBranch);
        }
        branch.setName(toBranch);
        branch.touch(RequestContext.principal(), RequestContext.now());
        branchRepository.save(branch);
    }

    /**
     * 把分支推进到主分支的最新快照。
     */
    @Transactional
    public void forward(String prefix, String database, String table, String branchName,
                        BranchDtos.ForwardBranchRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        BranchEntity branch = require(target.getId(), branchName);
        if (request != null && request.branch() != null
                && !request.branch().isBlank()
                && !request.branch().equals(branchName)) {
            throw ApiException.badRequest("branch in the body does not match the branch in the path");
        }
        branch.setSnapshotId(snapshotRepository.findFirstByTableIdOrderBySnapshotIdDesc(target.getId())
                .map(snapshot -> snapshot.getSnapshotId())
                .orElseThrow(() -> ApiException.snapshotNotExist(0)));
        branch.touch(RequestContext.principal(), RequestContext.now());
        branchRepository.save(branch);
    }

    private BranchEntity require(String tableId, String branchName) {
        return branchRepository.findByTableIdAndName(tableId, branchName)
                .orElseThrow(() -> ApiException.branchNotExist(branchName));
    }
}
