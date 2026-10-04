package com.example.cacheaside;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class PostgresFixture implements AutoCloseable {
    static final String IMAGE = "postgres:16.15-bookworm@sha256:efedf3595f1d6f415c08568ba171029bf54052e754cc9f030e3f2412b21f3d67";
    final String schema = "it_" + UUID.randomUUID().toString().replace("-", "");
    final String url;
    final String username;
    final String password;
    private final PostgreSQLContainer container;

    PostgresFixture() {
        String external = System.getenv("FLASHSALE_TEST_JDBC_URL");
        if (external == null) {
            container = new PostgreSQLContainer(IMAGE).withDatabaseName("flashsale_test");
            container.start(); // No disabledWithoutDocker or assumption-based skipped pass.
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        } else {
            if (!external.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/flashsale_test")) {
                throw new IllegalArgumentException(
                        "External acceptance DB must be jdbc:postgresql://127.0.0.1:<port>/flashsale_test");
            }
            container = null;
            url = external;
            username = System.getenv().getOrDefault("FLASHSALE_TEST_DB_USER", "flashsale");
            password = System.getenv().getOrDefault("FLASHSALE_TEST_DB_PASSWORD", "local-lab-only");
            System.out.println("ACCEPTANCE BACKEND: explicit native PostgreSQL, NOT a Testcontainers run.");
        }
    }

    String scopedUrl() {
        return url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&connectTimeout=3&socketTimeout=15";
    }

    String[] arguments() {
        return new String[] {
                "--server.port=0",
                "--spring.datasource.url=" + scopedUrl(),
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.flyway.default-schema=" + schema,
                "--spring.flyway.schemas=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--lab.demo-enabled=true", "--lab.read-delay-ms=0", "--lab.purchase-delay-ms=0"
        };
    }

    @Override
    public void close() throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            if (container != null) {
                container.stop();
            }
        }
    }
}
