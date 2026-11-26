package com.shoppinglive.catalog.search;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 부분 색인을 운영과 같은 PostgreSQL 로 확인한다. index_task 묶음 조회, 처리한 작업 삭제, ShedLock 이 DB 시각을 읽는 SQL 이 DB 마다
 * 다르다. 테스트마다 새 스키마를 만들고 끝나면 지운다.
 */
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class PartialIndexerPostgresTest extends PartialIndexerTest {
    private final List<String> schemas = new ArrayList<>();

    @Override
    DataSource catalogDatabase() {
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_USER", "postgres");
        String password =
                System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_PASSWORD", "postgres");
        String schema = "catalog_partial_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url, user, password))
                .execute("CREATE SCHEMA " + schema);
        schemas.add(schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        return source;
    }

    @AfterEach
    void dropSchemas() {
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                System.getenv("CATALOG_TEST_POSTGRES_URL"),
                                System.getenv()
                                        .getOrDefault("CATALOG_TEST_POSTGRES_USER", "postgres"),
                                System.getenv()
                                        .getOrDefault(
                                                "CATALOG_TEST_POSTGRES_PASSWORD", "postgres")));
        for (String schema : schemas) admin.execute("DROP SCHEMA " + schema + " CASCADE");
        schemas.clear();
    }
}
