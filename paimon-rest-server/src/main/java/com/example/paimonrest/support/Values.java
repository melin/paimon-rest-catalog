package com.example.paimonrest.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无类型 JSON 树上的取值辅助方法。
 *
 * <p>REST Catalog 规格中若干字段是自由 object（分区 spec、函数定义、Instant 等），
 * 直接声明为 {@code Object} / {@code Map<String,Object>} 由 Jackson 绑定时，
 * 数值可能落到 {@code Integer} 或 {@code Long}，因此统一在这里做收敛。
 */
public final class Values {

    private Values() {
    }

    public static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static String string(Object value, String fallback) {
        String s = string(value);
        return s == null ? fallback : s;
    }

    public static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.valueOf(s.trim());
        }
        return null;
    }

    public static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.valueOf(s.trim());
        }
        return null;
    }

    public static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.valueOf(s.trim());
        }
        return null;
    }

    public static boolean bool(Object value, boolean fallback) {
        Boolean b = bool(value);
        return b == null ? fallback : b;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        return new ArrayList<>();
    }

    /** 读取 {@code Map<String,String>} 形态的属性表；null 安全。 */
    public static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value).forEach((k, v) -> result.put(k, string(v)));
        return result;
    }

    /** 读取 {@code List<String>}；null 安全。 */
    public static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
