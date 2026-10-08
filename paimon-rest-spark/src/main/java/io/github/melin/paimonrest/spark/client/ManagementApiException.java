package io.github.melin.paimonrest.spark.client;

/**
 * 管理 API 调用失败。
 *
 * <p>携带 HTTP 状态码与被调用资源的标识，让上层 SQL 语句可以区分「资源不存在（404）」、
 * 「资源已存在（409）」与其它错误，从而正确实现 {@code IF EXISTS} / {@code IF NOT EXISTS}，
 * 而不是把所有失败都当成同一种异常。
 */
public class ManagementApiException extends RuntimeException {

    private final int status;
    private final String method;
    private final String path;
    private final String resourceType;
    private final String resourceName;

    public ManagementApiException(int status, String method, String path, String message,
                                  String resourceType, String resourceName) {
        super(message);
        this.status = status;
        this.method = method;
        this.path = path;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
    }

    /** HTTP 状态码；网络层失败时为 {@code 0}。 */
    public int status() {
        return status;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    /** 服务端 {@code ErrorResponse} 中的 {@code type}，可能为 {@code null}。 */
    public String resourceType() {
        return resourceType;
    }

    /** 服务端 {@code ErrorResponse} 中的 {@code name}，可能为 {@code null}。 */
    public String resourceName() {
        return resourceName;
    }

    /** 资源不存在。用于实现 {@code IF EXISTS} / {@code IF NOT EXISTS}。 */
    public boolean notFound() {
        return status == 404;
    }

    /** 资源已存在。 */
    public boolean alreadyExists() {
        return status == 409;
    }

    /** 权限不足。 */
    public boolean forbidden() {
        return status == 403 || status == 401;
    }

    /** 面向 SQL 用户的错误描述：把方法、路径与服务端消息拼成一条可读信息。 */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        if (status > 0) {
            builder.append("management API returned ").append(status);
        } else {
            builder.append("management API call failed");
        }
        if (method != null && path != null) {
            builder.append(" for ").append(method).append(' ').append(path);
        }
        String message = getMessage();
        if (message != null && !message.isBlank()) {
            builder.append(": ").append(message);
        }
        return builder.toString();
    }
}
