package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.CommonDtos;
import io.github.melin.paimonrest.support.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应。
 *
 * <p>所有失败都转换为规格定义的 {@code ErrorResponse}：
 * {@code {message, resourceType, resourceName, code}}，HTTP 状态码与 {@code code} 保持一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<CommonDtos.ErrorResponse> handleApiException(ApiException exception) {
        CommonDtos.ErrorResponse body = new CommonDtos.ErrorResponse(
                exception.getMessage(),
                exception.getResourceType() == null ? null : exception.getResourceType().name(),
                exception.getResourceName(),
                exception.getStatus());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonDtos.ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(400)
                .body(new CommonDtos.ErrorResponse("Malformed request", null, null, 400));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CommonDtos.ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(400)
                .body(new CommonDtos.ErrorResponse(
                        exception.getMessage() == null ? "Malformed request" : exception.getMessage(),
                        null, null, 400));
    }

    /**
     * 兜底处理。
     *
     * <p>Spring MVC 自身的错误（找不到路由、方法不支持等）已实现 {@link ErrorResponse} 并携带状态码，
     * 这里沿用其状态码而不是一律返回 500——例如访问不存在的路径应当是 404。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonDtos.ErrorResponse> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            int status = statusCode.value();
            var problem = errorResponse.getBody();
            String message = problem != null && problem.getDetail() != null
                    ? problem.getDetail()
                    : exception.getMessage();
            if (status >= 500) {
                log.error("Catalog request failed with status {}", status, exception);
            } else {
                log.debug("Catalog request rejected with status {}: {}", status, message);
            }
            return ResponseEntity.status(status).body(new CommonDtos.ErrorResponse(message, null, null, status));
        }
        log.error("Unhandled error while serving catalog request", exception);
        return ResponseEntity.status(500)
                .body(new CommonDtos.ErrorResponse("Internal Server Error", null, null, 500));
    }
}
