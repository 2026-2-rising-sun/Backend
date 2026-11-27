package com.shoppinglive.catalog.ranking;

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
 * 조회수와 랭킹을 운영과 같은 PostgreSQL 로 확인한다. 동시에 같은 칸을 만들 때의 키 충돌과 시각 비교가 DB 마다 다르다. 테스트마다 새 스키마를 만들고 끝나면
 * 지운다.
 */
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class ViewRankingPostgresTest extends ViewRankingTest {
    private final List<String> schemas = new ArrayList<>();

    private static DriverManagerDataSource admin(String suffix) {
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL");
        return new DriverManagerDataSource(
                url + suffix,
                System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_PASSWORD", "postgres"));
    }

    @Override
    DataSource catalogDatabase() {
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL");
        String schema = "catalog_ranking_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin("")).execute("CREATE SCHEMA " + schema);
        schemas.add(schema);
        var source = admin((url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
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
        var admin = new JdbcTemplate(admin(""));
        for (String schema : schemas) admin.execute("DROP SCHEMA " + schema + " CASCADE");
        schemas.clear();
    }
}
