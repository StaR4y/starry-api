package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class AdminSessionServiceTest {

    @Test
    void invalidatesOnlySessionsOwnedByTargetUser() {
        AdminSessionService service = new AdminSessionService(new ProxyProperties());
        String targetToken = service.create(user("root-1", "admin")).token();
        String targetSecondToken = service.create(user("root-1", "admin")).token();
        String otherToken = service.create(user("admin-2", "manager")).token();

        service.invalidateUserSessions("root-1");

        assertThatThrownBy(() -> service.verify(targetToken)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.verify(targetSecondToken)).isInstanceOf(ApiException.class);
        assertThat(service.verify(otherToken).userId()).isEqualTo("admin-2");
    }

    private AdminUserEntity user(String userId, String username) {
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId(userId);
        user.setUsername(username);
        user.setRole("ADMIN");
        user.setFirstLogin(false);
        return user;
    }
}
