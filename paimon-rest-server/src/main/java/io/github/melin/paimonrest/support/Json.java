package io.github.melin.paimonrest.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内部 JSON 编解码入口。
 *
 * <p>仅用于把结构化元数据（表 schema、分区 spec、函数定义等）序列化成列内文本，
 * 以及反向还原。HTTP 请求/响应的绑定交给 Spring MVC 自身的消息转换器处理，
 * 两者互不影响。
 *
 * <p>唯一一处跨界的是 {@code POST /v1/{prefix}/databases/{db}/tables/{table}/commit}：
 * MVC 已经把报文解析成 JSON 树，服务端还要把同一棵树还原成 DTO，因为同一段文本要存两份
 * （字段落库、快照原文写进仓库）。那是一次内部映射，不是 HTTP 绑定——报文的解析仍然是 MVC 做的。
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** 序列化为 JSON 文本。 */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * 反序列化为无类型对象树：JSON object 得到 {@code Map}，数组得到 {@code List}，
     * 其余为 {@code String} / {@code Integer} / {@code Long} / {@code Boolean} / {@code null}。
     */
    public static Object read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return MAPPER.readValue(json, Object.class);
    }

    /** 反序列化为指定类型。 */
    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
