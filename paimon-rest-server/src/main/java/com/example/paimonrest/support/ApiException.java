package com.example.paimonrest.support;

import lombok.Getter;

/**
 * 目录服务抛出的业务异常，携带 HTTP 状态码与错误响应所需的资源信息。
 *
 * <p>由 {@code GlobalExceptionHandler} 转换成规格定义的 {@code ErrorResponse}。
 */
@Getter
public class ApiException extends RuntimeException {

    private final int status;
    private final ResourceType resourceType;
    private final String resourceName;

    public ApiException(int status, ResourceType resourceType, String resourceName, String message) {
        super(message);
        this.status = status;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
    }

    public static ApiException databaseNotExist(String database) {
        return new ApiException(404, ResourceType.DATABASE, database, "The given database does not exist");
    }

    public static ApiException tableNotExist(String table) {
        return new ApiException(404, ResourceType.TABLE, table, "The given table does not exist");
    }

    public static ApiException snapshotNotExist(long snapshotId) {
        return new ApiException(404, ResourceType.SNAPSHOT, Long.toString(snapshotId),
                "The given snapshot does not exist");
    }

    public static ApiException branchNotExist(String branch) {
        return new ApiException(404, ResourceType.BRANCH, branch, "The given branch does not exist");
    }

    public static ApiException tagNotExist(String tag) {
        return new ApiException(404, ResourceType.TAG, tag, "The given tag does not exist");
    }

    public static ApiException viewNotExist(String view) {
        return new ApiException(404, ResourceType.VIEW, view, "The given view does not exist");
    }

    public static ApiException functionNotExist(String function) {
        return new ApiException(404, ResourceType.FUNCTION, function, "The given function does not exist");
    }

    public static ApiException semanticViewNotExist(String name) {
        return new ApiException(404, ResourceType.SEMANTIC_VIEW, name,
                "Database or semantic view does not exist.");
    }

    public static ApiException databaseAlreadyExist(String database) {
        return new ApiException(409, ResourceType.DATABASE, database, "The given database already exists");
    }

    public static ApiException tableAlreadyExist(String table) {
        return new ApiException(409, ResourceType.TABLE, table, "The given table already exists");
    }

    public static ApiException branchAlreadyExist(String branch) {
        return new ApiException(409, ResourceType.BRANCH, branch, "The given branch already exists");
    }

    public static ApiException tagAlreadyExist(String tag) {
        return new ApiException(409, ResourceType.TAG, tag, "The given tag already exists");
    }

    public static ApiException viewAlreadyExist(String view) {
        return new ApiException(409, ResourceType.VIEW, view, "The given view already exists");
    }

    public static ApiException functionAlreadyExist(String function) {
        return new ApiException(409, ResourceType.FUNCTION, function, "The given function already exists");
    }

    public static ApiException partitionAlreadyExist(Object spec) {
        return new ApiException(409, ResourceType.PARTITION, String.valueOf(spec),
                "The given partition already exists");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, null, null, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, null, null, message);
    }

    public static ApiException payloadTooLarge(ResourceType type, String name, String message) {
        return new ApiException(413, type, name, message);
    }

    public static ApiException notImplemented(String message) {
        return new ApiException(501, null, null, message);
    }

    // ---------------------------------------------------------------- 管理 API

    /** 管理 API：实体不存在。规格 404 描述形如「The principal does not exist」。 */
    public static ApiException managementNotExist(ResourceType type, String name) {
        return new ApiException(404, type, name, "The given " + type.name().toLowerCase()
                .replace('_', ' ') + " does not exist");
    }

    /** 管理 API：同名实体已存在。规格 409 描述形如「A catalog with the specified name already exists」。 */
    public static ApiException managementAlreadyExist(ResourceType type, String name) {
        return new ApiException(409, type, name, "A " + type.name().toLowerCase().replace('_', ' ')
                + " with the specified name already exists");
    }

    /** 管理 API：{@code currentEntityVersion} 与当前版本不符。规格 409 原文见下。 */
    public static ApiException entityVersionMismatch(ResourceType type, String name,
                                                     int expected, int actual) {
        return new ApiException(409, type, name,
                "The entity version doesn't match the currentEntityVersion; retry after fetching latest version"
                        + " (expected " + expected + ", actual " + actual + ")");
    }

    /**
     * 管理 API：要撤销的授权或角色分配并不存在。
     *
     * <p>单独一个工厂而不是复用 {@link #managementNotExist}：那种情况下被授予的实体本身存在，
     * 不存在的是「实体之间的这条关联」，用「实体不存在」描述会误导调用方去检查实体名。
     */
    public static ApiException assignmentNotExist(ResourceType type, String name, String detail) {
        return new ApiException(404, type, name, detail);
    }
}
