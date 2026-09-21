package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Query;

class AdminUserRepositoryTest {

    @Test
    void applicationRootQueryUsesThePersistedRootIdentity() throws Exception {
        Query query = AdminUserRepository.class
            .getMethod("findApplicationRoot")
            .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
            .contains("settings.setting_key = 'application_root_user_id'")
            .contains("users.user_id = settings.setting_value")
            .contains("LIMIT 1")
            .doesNotContainIgnoringCase("role")
            .doesNotContainIgnoringCase("order by");
    }

    @Test
    void applicationRootEmailQueryDoesNotLoadPasswordMaterial() throws Exception {
        Query query = AdminUserRepository.class
            .getMethod("findApplicationRootEmail")
            .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
            .contains("SELECT COALESCE(users.email, '') AS email")
            .contains("settings.setting_key = 'application_root_user_id'")
            .contains("users.user_id = settings.setting_value")
            .doesNotContain("SELECT *")
            .doesNotContainIgnoringCase("password");
    }
}
