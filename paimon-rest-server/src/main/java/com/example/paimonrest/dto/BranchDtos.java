package com.example.paimonrest.dto;

import java.util.List;

/**
 * 分支相关 DTO。
 */
public final class BranchDtos {

    private BranchDtos() {
    }

    /** fromTag 为空时从主分支最新快照创建。 */
    public record CreateBranchRequest(String branch, String fromTag) {
    }

    public record RenameBranchRequest(String toBranch) {
    }

    public record ForwardBranchRequest(String branch) {
    }

    public record ListBranchesResponse(List<String> branches) {
    }
}
