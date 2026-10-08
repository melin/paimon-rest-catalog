package com.example.paimonrest.support;

/**
 * 名称匹配。
 *
 * <p>规格中的 {@code tableNamePattern} / {@code databaseNamePattern} 等字段是 SQL LIKE 模式，
 * 但明确说明「当前只支持前缀匹配」。这里的处理方式是：
 * 截取第一个 {@code %} 之前的部分作为前缀；模式中没有 {@code %} 时按全等匹配。
 */
public final class Patterns {

    private Patterns() {
    }

    public static boolean matches(String name, String pattern) {
        if (name == null) {
            return false;
        }
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        int wildcard = pattern.indexOf('%');
        if (wildcard < 0) {
            return name.equals(pattern);
        }
        String prefix = pattern.substring(0, wildcard);
        return prefix.isEmpty() || name.startsWith(prefix);
    }
}
