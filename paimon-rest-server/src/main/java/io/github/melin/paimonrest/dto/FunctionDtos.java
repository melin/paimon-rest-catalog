package io.github.melin.paimonrest.dto;

import java.util.List;
import java.util.Map;

/**
 * 函数相关 DTO。变更项按 {@code action} 分派：
 * {@code setOption} / {@code removeOption} / {@code updateComment} /
 * {@code addDefinition} / {@code updateDefinition} / {@code dropDefinition}。
 *
 * <p>{@code definitions} 的值是 {@code file} / {@code sql} / {@code lambda} 三种定义的联合，
 * 用 {@code type} 判别，此处按 {@code Map<String,Object>} 原样承载。
 */
public final class FunctionDtos {

    private FunctionDtos() {
    }

    public record CreateFunctionRequest(
            String name,
            List<TypeDtos.DataField> inputParams,
            List<TypeDtos.DataField> returnParams,
            Boolean deterministic,
            Map<String, Object> definitions,
            String comment,
            Map<String, String> options) {
    }

    public record AlterFunctionRequest(List<Map<String, Object>> changes) {
    }

    public record GetFunctionResponse(
            String uuid,
            String name,
            List<TypeDtos.DataField> inputParams,
            List<TypeDtos.DataField> returnParams,
            Boolean deterministic,
            Map<String, Object> definitions,
            String comment,
            Map<String, String> options,
            String owner,
            Long createdAt,
            String createdBy,
            Long updatedAt,
            String updatedBy) {
    }

    public record ListFunctionsResponse(List<String> functions, String nextPageToken) {
    }

    public record ListFunctionDetailsResponse(List<GetFunctionResponse> functionDetails, String nextPageToken) {
    }

    public record ListFunctionsGloballyResponse(List<CommonDtos.Identifier> functions, String nextPageToken) {
    }
}
