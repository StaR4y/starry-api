package tech.starryapi.postgresql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgreSqlIntegrationTestSupport {

    protected static final String MIGRATION_USERNAME = "starry_api_migration";
    protected static final String MIGRATION_PASSWORD = "starry_api_migration_test";
    protected static final String APP_USERNAME = "starry_api";
    protected static final String APP_PASSWORD = "starry_api_app_test";

    private static final String POSTGRES_IMAGE = System.getProperty(
        "test.postgres.image",
        System.getenv().getOrDefault("TEST_POSTGRES_IMAGE", "postgres:17-alpine")
    );

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
        .withDatabaseName("starry_api")
        .withUsername("starry_api_bootstrap")
        .withPassword("starry_api_bootstrap_test")
        .withInitScript("postgresql/init-test-roles.sql");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://"
            + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/" + POSTGRES.getDatabaseName()
            + "?schema=starry_api&tcpKeepAlive=true&applicationName=starry-api-integration-test");
        registry.add("spring.r2dbc.username", () -> APP_USERNAME);
        registry.add("spring.r2dbc.password", () -> APP_PASSWORD);
        registry.add("spring.r2dbc.pool.initial-size", () -> "0");
        registry.add("spring.r2dbc.pool.max-size", () -> "32");
        registry.add("spring.flyway.url", () -> POSTGRES.getJdbcUrl()
            + "&currentSchema=starry_api&ApplicationName=starry-api-flyway-integration-test");
        registry.add("spring.flyway.user", () -> MIGRATION_USERNAME);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("spring.flyway.default-schema", () -> "starry_api");
        registry.add("spring.flyway.schemas", () -> "starry_api");
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.init-sqls", () -> "SET ROLE starry_api_owner");
    }

    protected static int expectedPostgresMajorVersion() {
        String tag = POSTGRES_IMAGE.substring(POSTGRES_IMAGE.lastIndexOf(':') + 1);
        int majorEnd = 0;
        while (majorEnd < tag.length() && Character.isDigit(tag.charAt(majorEnd))) {
            majorEnd++;
        }
        if (majorEnd == 0) {
            throw new IllegalArgumentException("PostgreSQL image tag must begin with a major version: " + POSTGRES_IMAGE);
        }
        return Integer.parseInt(tag.substring(0, majorEnd));
    }

    protected static void executeAsDatabaseOwner(String... statements) {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            MIGRATION_USERNAME,
            MIGRATION_PASSWORD
        )) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE starry_api_owner");
                for (String sql : statements) {
                    statement.executeUpdate(sql);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not execute PostgreSQL integration-test owner cleanup", exception);
        }
    }
}
