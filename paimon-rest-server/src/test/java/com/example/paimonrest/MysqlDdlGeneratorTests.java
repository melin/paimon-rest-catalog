package com.example.paimonrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 从实体元数据生成 MySQL DDL，产出仓库内的 {@code sql/schema-mysql.sql}。
 *
 * <p><b>为什么用测试来生成。</b>Hibernate 7 已移除 {@code org.hibernate.tool.hbm2ddl.SchemaExport}，
 * 官方路径只剩 {@code SchemaManagementToolCoordinator} 配合 JPA 的
 * {@code jakarta.persistence.schema-generation.*} 属性。而实体清单由 Spring Boot 的
 * {@code @SpringBootApplication} 包扫描给出，因此走一次真实的上下文启动，
 * 新增实体会被自动纳入，不会出现「加了实体却忘了加进生成器」的漏项。
 *
 * <p><b>连接用 H2、方言用 MySQL。</b>脚本生成只读元数据，不需要连到 MySQL，
 * 这样生成过程不依赖数据库可用性。但方言必须显式钉住 MySQL 8.0，见
 * {@link MySql8DialectResolver}——否则 Hibernate 会把 H2 上报的版本号当成 MySQL 版本。
 * 生成的 SQL 是否真能被 MySQL 接受，由 {@code scripts/gen-mysql-ddl.sh} 之外的实机步骤验证。
 *
 * <p>数据源相关属性在这里全部显式给出，不使用 {@code application.yml} 的默认值——
 * 默认值已经指向 MySQL，若沿用会让本测试要求一个真实数据库才能跑。
 */
@SpringBootTest(properties = {
        // 生成脚本只需要元数据，不连真实库；用 H2 保证任何环境都能执行
        "spring.datasource.url=jdbc:h2:mem:ddlgen;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // 不设 spring.jpa.database-platform：改由下面的 DialectResolver 决定方言，
        // 这样方言版本可控，也不会触发 HHH90000025（不该显式指定 hibernate.dialect）
        "spring.jpa.properties.hibernate.dialect_resolvers="
                + "com.example.paimonrest.MysqlDdlGeneratorTests$MySql8DialectResolver",
        // 关掉自动建表，只写脚本
        "spring.jpa.hibernate.ddl-auto=none",
        // hibernate.format_sql 除了美化日志里的 SQL，也决定 DDL 脚本是否换行缩进。
        // 不打开的话每条 create table 会挤成一整行（最长近 3000 字符），无法审阅与 diff。
        "spring.jpa.properties.hibernate.format_sql=true",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.create-source=metadata",
        // 每次覆盖而不是追加，保证脚本可重复生成
        "spring.jpa.properties.hibernate.hbm2ddl.schema-generation.script.append=false",
        // 关掉启动期预置数据：此时库里没有表，预置会失败
        "paimon.rest.initial-catalog.prefix=",
        "paimon.rest.authorization.enabled=false",
        "paimon.rest.auth.enabled=false"
})
class MysqlDdlGeneratorTests {

    /**
     * 把方言钉到 MySQL 8.0。
     *
     * <p><b>为什么需要它。</b>若只写 {@code hibernate.dialect=MySQLDialect}，
     * Hibernate 会用 {@code MySQLDialect(DialectResolutionInfo)} 构造方言，
     * 而这里的连接是 H2，于是它把 H2 上报的 {@code 2.4.240} 当成 MySQL 版本，
     * 输出 {@code HHH000511: The 2.4.240 version for MySQLDialect is no longer supported}，
     * 并可能按 MySQL 2.x/5.x 的旧路径生成 DDL。DDL 的目标版本必须是确定的，
     * 所以这里显式返回 8.0。
     *
     * <p>必须是 public static 且有无参构造函数，{@code hibernate.dialect_resolvers}
     * 按类名实例化它。
     */
    public static class MySql8DialectResolver implements DialectResolver {

        @Override
        public Dialect resolveDialect(DialectResolutionInfo info) {
            return new MySQLDialect(DatabaseVersion.make(8, 0));
        }
    }


    /**
     * 脚本输出位置。
     *
     * <p>默认写到 {@code target/}（构建产物，不入仓库）；
     * {@code scripts/gen-mysql-ddl.sh} 通过 {@code -Dmysql.ddl.output=...} 指到交付路径。
     */
    private static final Path OUTPUT = Path.of(
            System.getProperty("mysql.ddl.output", "target/mysql-schema.sql"))
            .toAbsolutePath();

    /** 实体的表数量；新增实体时应同步上调，否则本测试会失败提醒。 */
    private static final int EXPECTED_TABLES = 18;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void schemaGenerationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target",
                () -> OUTPUT.toString());
    }

    @Test
    void generatesMysqlDdlForEveryEntity() throws IOException {
        assertTrue(Files.exists(OUTPUT), "未生成 DDL 脚本：" + OUTPUT);
        String ddl = Files.readString(OUTPUT, StandardCharsets.UTF_8);

        Matcher tables = Pattern.compile("(?im)^\\s*create table\\s+", Pattern.MULTILINE).matcher(ddl);
        int count = 0;
        while (tables.find()) {
            count++;
        }
        assertEquals(EXPECTED_TABLES, count,
                "DDL 中的建表语句数量与实体数量不符，说明有实体未被扫描到或已被删除：" + OUTPUT);

        // 方言必须真的生效：H2 会写成 character varying，MySQL 是 varchar / mediumtext
        assertTrue(ddl.toLowerCase().contains("engine=innodb") || ddl.toLowerCase().contains("engine = innodb"),
                "生成的 DDL 不是 MySQL 方言（缺少 ENGINE=InnoDB）：" + OUTPUT);
        assertTrue(ddl.toLowerCase().contains("mediumtext"),
                "生成的 DDL 不是 MySQL 方言（超长文本列应为 mediumtext）：" + OUTPUT);
    }

    /**
     * 锁住方言版本。
     *
     * <p>这条断言防的是「方言退化成按 H2 上报的版本号解释」：一旦解除
     * {@link MySql8DialectResolver}，Hibernate 会把 H2 的 {@code 2.4.240} 当成 MySQL 版本，
     * 生成的 DDL 就按旧版本路径走。建表语句数量之类的断言不会发现这种偏移，必须直接查版本。
     */
    @Test
    void pinsTheDialectToMysql8() {
        Dialect dialect = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect();
        assertInstanceOf(MySQLDialect.class, dialect, "方言不是 MySQLDialect");
        assertEquals(8, dialect.getVersion().getMajor(), "方言主版本不是 8（可能被 H2 的版本号污染）");
        assertEquals(0, dialect.getVersion().getMinor(), "方言次版本不是 0");
    }
}
