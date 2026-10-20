package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;

class R2dbcDatabaseInitializationDependencyDetectorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ClientConfiguration.class);

    @Test
    void initializesFlywayBeforeCreatingALazyR2dbcClient() {
        contextRunner.withUserConfiguration(MigrationConfiguration.class).run(context -> {
            List<String> events = context.getBean(Events.class).values;
            assertThat(events).isEmpty();

            context.getBean(DatabaseClient.class);

            assertThat(events).containsExactly("migration", "client");
            verify(context.getBean(Flyway.class)).migrate();
        });
    }

    @Test
    void failedMigrationPreventsR2dbcClientCreation() {
        contextRunner.withUserConfiguration(MigrationConfiguration.class).run(context -> {
            when(context.getBean(Flyway.class).migrate()).thenThrow(new IllegalStateException("migration failed"));

            assertThatThrownBy(() -> context.getBean(DatabaseClient.class))
                .hasRootCauseMessage("migration failed");
            assertThat(context.getBean(Events.class).values).containsExactly("migration");
        });
    }

    @Test
    void permitsAClientWhenNoDatabaseInitializerIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context.getBean(DatabaseClient.class)).isNotNull();
            assertThat(context.getBean(Events.class).values).containsExactly("client");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DatabaseInitializationDependencyConfigurer.class)
    static class ClientConfiguration {

        @Bean
        Events events() {
            return new Events();
        }

        @Bean
        @Lazy
        DatabaseClient databaseClient(Events events) {
            events.values.add("client");
            return mock(DatabaseClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MigrationConfiguration {

        @Bean
        Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Lazy
        FlywayMigrationInitializer flywayInitializer(Flyway flyway, Events events) {
            return new FlywayMigrationInitializer(flyway, value -> {
                events.values.add("migration");
                value.migrate();
            });
        }
    }

    static class Events {
        final List<String> values = new ArrayList<>();
    }
}
