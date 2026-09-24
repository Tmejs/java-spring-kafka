package io.github.tmejs.reservation.inventory.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class InventoryPostgresIntegrationTest {

    private static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer("postgres:18.1")
                    .withDatabaseName("inventory")
                    .withUsername("inventory")
                    .withPassword("inventory");

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }
}
