package io.github.melin.paimonrest.support;

/**
 * 错误响应中的资源类型。
 *
 * <p>前一组取值与 catalog 规格 {@code ErrorResponse.resourceType} 一致；
 * 后一组是管理 API 的资源类型。管理规格的错误响应只给了描述、没有定义响应体，
 * 因此这组取值是本服务为统一错误体而设，不与规格冲突。
 */
public enum ResourceType {
    // catalog API
    DATABASE,
    TABLE,
    PARTITION,
    COLUMN,
    SNAPSHOT,
    BRANCH,
    TAG,
    VIEW,
    SEMANTIC_VIEW,
    DIALECT,
    FUNCTION,
    DEFINITION,
    // management API
    CATALOG,
    PRINCIPAL,
    PRINCIPAL_ROLE,
    CATALOG_ROLE,
    GRANT,
    UNKNOWN
}
