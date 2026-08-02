package tech.starryapi.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.postgresql.api.Notification;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;

class SystemSettingNotificationListenerTest {

    @Test
    void listenerReconnectInvalidatesAllRuntimeState() {
        RuntimeStateInvalidator invalidator = mock(RuntimeStateInvalidator.class);
        SystemSettingNotificationListener listener = listener(invalidator);

        listener.onListenerConnected();

        verify(invalidator).invalidateAll();
    }

    @Test
    void forwardsSettingKeyToNarrowInvalidation() {
        RuntimeStateInvalidator invalidator = mock(RuntimeStateInvalidator.class);
        Notification notification = mock(Notification.class);
        when(notification.getParameter()).thenReturn("payment_enabled");

        listener(invalidator).invalidateCaches(notification);

        verify(invalidator).invalidateSetting("payment_enabled");
    }

    private SystemSettingNotificationListener listener(RuntimeStateInvalidator invalidator) {
        R2dbcProperties properties = new R2dbcProperties();
        properties.setUrl("r2dbc:postgresql://localhost:5432/starry_api");
        properties.setUsername("starry_api");
        properties.setPassword("test");
        return new SystemSettingNotificationListener(properties, invalidator);
    }
}
