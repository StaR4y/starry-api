package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import tech.starryapi.config.ProxyProperties;

class StartupGuideLoggerTest {

    @Test
    void bootstrapsApplicationRootExactlyOnceBeforeRunnerCompletes() throws Exception {
        AdminUserService adminUserService = mock(AdminUserService.class);
        AdminUserService.BootstrapAdmin bootstrap = new AdminUserService.BootstrapAdmin(
            false,
            "admin",
            null,
            false
        );
        when(adminUserService.ensureDefaultAdmin()).thenReturn(Mono.just(bootstrap));
        StartupGuideLogger runner = runner(adminUserService);

        runner.run(new DefaultApplicationArguments());

        verify(adminUserService).ensureDefaultAdmin();
    }

    @Test
    void rootInvariantFailurePropagatesAndStopsApplicationStartup() {
        AdminUserService adminUserService = mock(AdminUserService.class);
        IllegalStateException failure = new IllegalStateException("Root account violates its required identity state");
        when(adminUserService.ensureDefaultAdmin()).thenReturn(Mono.error(failure));
        StartupGuideLogger runner = runner(adminUserService);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
            .isSameAs(failure);
    }

    @Test
    void emptyRootBootstrapResultFailsClosed() {
        AdminUserService adminUserService = mock(AdminUserService.class);
        when(adminUserService.ensureDefaultAdmin()).thenReturn(Mono.empty());
        StartupGuideLogger runner = runner(adminUserService);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a Root account");
    }

    private StartupGuideLogger runner(AdminUserService adminUserService) {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8080");
        return new StartupGuideLogger(adminUserService, new ProxyProperties(), environment);
    }
}
