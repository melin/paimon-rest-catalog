package io.github.melin.paimonrest.support;

/**
 * 存储路径推导。
 *
 * <p>Paimon 的目录约定是 {@code warehouse/database.db/table}，
 * 表路径模板可通过 {@code paimon.rest.path-template} 覆盖。
 */
public final class Paths {

    private Paths() {
    }

    public static String databaseLocation(String warehouse, String database) {
        return trimTrailingSlash(warehouse) + "/" + database + ".db";
    }

    public static String tableLocation(String template, String warehouse, String database, String table) {
        String pattern = template == null || template.isBlank()
                ? "{warehouse}/{database}.db/{table}"
                : template;
        return pattern
                .replace("{warehouse}", trimTrailingSlash(warehouse))
                .replace("{database}", database)
                .replace("{table}", table);
    }

    public static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
