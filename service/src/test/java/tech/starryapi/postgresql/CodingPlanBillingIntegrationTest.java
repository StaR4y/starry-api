package tech.starryapi.postgresql;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CODING_PLAN_DATABASE_TESTS", matches = "true")
class CodingPlanBillingIntegrationTest extends CodingPlanBillingContract {
}
