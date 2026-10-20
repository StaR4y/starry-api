package tech.starryapi.postgresql;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CodingPlanSubscriptionPostgreSqlIntegrationTest extends CodingPlanBillingContract {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSqlIntegrationTestSupport.POSTGRES;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.postgresProperties(registry);
    }
}
