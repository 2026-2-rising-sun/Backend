package com.shoppinglive.shopping.product;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSellerMigrationTest {
    @Test
    void migrationRetainsExistingProductsWithoutAssigningSeller() throws Exception {
        verify("jdbc:h2:mem:seller_migration_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa", "");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_POSTGRES_URL", matches = ".+")
    void postgresMigrationRetainsExistingProductsWithoutAssigningSeller() throws Exception {
        verify(System.getenv("SHOPPING_TEST_POSTGRES_URL"),
            System.getenv().getOrDefault("SHOPPING_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("SHOPPING_TEST_POSTGRES_PASSWORD", "postgres"));
    }

    private void verify(String url, String user, String password) throws Exception {
        String schema = "seller_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway baseline = Flyway.configure().dataSource(url, user, password)
            .schemas(schema).defaultSchema(schema).target("1").load();
        baseline.migrate();
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    INSERT INTO product_image(storage_key,content_type,size_bytes,width,height,created_at,updated_at)
                    VALUES ('migration.png','image/png',100,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
                statement.executeUpdate("""
                    INSERT INTO product(name,description,main_image_id,idempotency_key,created_at,updated_at)
                    VALUES ('existing','existing',1,'legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            }
        }
        Flyway latest = Flyway.configure().dataSource(url, user, password).schemas(schema)
            .defaultSchema(schema).cleanDisabled(false).load();
        try {
            latest.migrate();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setSchema(schema);
                try (var statement = connection.createStatement();
                        var result = statement.executeQuery("SELECT name, seller_id FROM product WHERE id=1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("name")).isEqualTo("existing");
                    assertThat(result.getString("seller_id")).isNull();
                    assertThat(result.next()).isFalse();
                }
            }
        } finally {
            latest.clean();
        }
    }
}
