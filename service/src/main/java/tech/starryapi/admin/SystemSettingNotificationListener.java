package tech.starryapi.admin;

import io.r2dbc.postgresql.api.Notification;
import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Wrapped;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class SystemSettingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingNotificationListener.class);
    private static final String CHANNEL = "starry_api_system_settings";

    private final ConnectionFactory listenerConnectionFactory;
    private final RuntimeStateInvalidator runtimeStateInvalidator;
    private volatile Disposable subscription;

    public SystemSettingNotificationListener(
        R2dbcProperties properties,
        RuntimeStateInvalidator runtimeStateInvalidator
    ) {
        this.listenerConnectionFactory = listenerConnectionFactory(properties);
        this.runtimeStateInvalidator = runtimeStateInvalidator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }
        subscription = notifications()
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))
                .doBeforeRetry(signal -> log.warn(
                    "PostgreSQL setting notification listener disconnected; reconnecting",
                    signal.failure()
                )))
            .subscribe(this::invalidateCaches, error -> log.error(
                "PostgreSQL setting notification listener stopped",
                error
            ));
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    private Flux<Notification> notifications() {
        return Flux.usingWhen(
            Mono.from(listenerConnectionFactory.create()).map(this::listenerConnection),
            listener -> listen(listener.postgresqlConnection())
                .doOnSuccess(ignored -> onListenerConnected())
                .thenMany(listener.postgresqlConnection().getNotifications()),
            listener -> listener.connection().close(),
            (listener, error) -> listener.connection().close(),
            listener -> listener.connection().close()
        );
    }

    private Mono<Void> listen(PostgresqlConnection connection) {
        return Flux.from(connection.createStatement("LISTEN " + CHANNEL).execute())
            .flatMap(Result::getRowsUpdated)
            .then();
    }

    void onListenerConnected() {
        runtimeStateInvalidator.invalidateAll();
    }

    void invalidateCaches(Notification notification) {
        String settingKey = notification.getParameter();
        if (!StringUtils.hasText(settingKey)) {
            runtimeStateInvalidator.invalidateAll();
            return;
        }
        runtimeStateInvalidator.invalidateSetting(settingKey);
    }

    private ListenerConnection listenerConnection(Connection connection) {
        Connection candidate = connection;
        while (candidate instanceof Wrapped<?> wrapped) {
            Object unwrapped = wrapped.unwrap();
            if (!(unwrapped instanceof Connection next) || next == candidate) {
                break;
            }
            candidate = next;
        }
        if (!(candidate instanceof PostgresqlConnection postgresqlConnection)) {
            return closeUnsupportedConnection(connection);
        }
        return new ListenerConnection(connection, postgresqlConnection);
    }

    private ListenerConnection closeUnsupportedConnection(Connection connection) {
        Mono.from(connection.close()).subscribe();
        throw new IllegalStateException("System setting notifications require the PostgreSQL R2DBC driver");
    }

    private ConnectionFactory listenerConnectionFactory(R2dbcProperties properties) {
        ConnectionFactoryOptions.Builder options = ConnectionFactoryOptions.parse(properties.getUrl()).mutate();
        if (StringUtils.hasText(properties.getUsername())) {
            options.option(ConnectionFactoryOptions.USER, properties.getUsername());
        }
        if (properties.getPassword() != null) {
            options.option(ConnectionFactoryOptions.PASSWORD, properties.getPassword());
        }
        return ConnectionFactories.get(options.build());
    }

    private record ListenerConnection(
        Connection connection,
        PostgresqlConnection postgresqlConnection
    ) {
    }
}
