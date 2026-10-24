package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.admin.AdminDtos;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.admin.AdminUserRepository;
import tech.starryapi.admin.RegistrationEmailTokenRepository;
import tech.starryapi.admin.RegistrationPersistenceService;
import tech.starryapi.admin.RegistrationSettingsService;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingNotificationListener;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyDtos;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.model.ModelMappingEntity;
import tech.starryapi.model.ModelMappingRepository;
import tech.starryapi.payment.EasyPayClient;
import tech.starryapi.payment.EasyPayClient.Notification;
import tech.starryapi.payment.InviteRebateService;
import tech.starryapi.payment.InviteRebatePersistenceService;
import tech.starryapi.payment.PaymentOrderEntity;
import tech.starryapi.payment.PaymentOrderRepository;
import tech.starryapi.payment.PaymentDtos.RedeemCodeRequest;
import tech.starryapi.payment.PaymentDtos.UpdatePaymentSettingsRequest;
import tech.starryapi.payment.PaymentService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.payment.RedemptionCodeRepository;
import tech.starryapi.payment.RedemptionCodeService;
import tech.starryapi.stats.StatsAnalyticsService;
import tech.starryapi.stats.StatsDtos;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "starry.stats.aggregation-enabled=false"
    }
)
@Timeout(value = 3, unit = java.util.concurrent.TimeUnit.MINUTES)
class PostgreSqlBaselineIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final int CONCURRENT_REQUESTS = 100;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ModelMappingRepository modelMappingRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private StatsAnalyticsService statsAnalyticsService;

    @Autowired
    private R2dbcEntityTemplate entityTemplate;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private RedemptionCodeRepository redemptionCodeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private AccountPoolService accountPoolService;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private AccountSettingsService accountSettingsService;

    @Autowired
    private RegistrationSettingsService registrationSettingsService;

    @Autowired
    private PaymentSettingsService paymentSettingsService;

    @Autowired
    private RegistrationPersistenceService registrationPersistenceService;

    @Autowired
    private RegistrationEmailTokenRepository registrationEmailTokenRepository;

    @Autowired
    private InviteRebatePersistenceService inviteRebatePersistenceService;

    @Autowired
    private SystemSettingNotificationListener systemSettingNotificationListener;

    @Test
    void startsApplicationWithFlywayInStarryApiDatabaseAndSchema() {
        DatabaseIdentity identity = databaseClient.sql("""
                SELECT current_database() AS database_name,
                       current_schema() AS schema_name,
                       current_setting('server_version_num')::integer AS server_version_num,
                       EXISTS (
                           SELECT 1
                           FROM starry_api.flyway_schema_history
                           WHERE version = '1' AND success
                       ) AS baseline_applied,
                       (
                           SELECT COUNT(*)::integer
                           FROM pg_catalog.pg_inherits
                           WHERE inhparent = 'starry_api.api_usage_events'::regclass
                       ) AS usage_event_partitions,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_constraint constraint_definition
                           INNER JOIN pg_catalog.pg_attribute attribute_definition
                               ON attribute_definition.attrelid = constraint_definition.conrelid
                              AND attribute_definition.attnum = ANY (constraint_definition.conkey)
                           WHERE constraint_definition.conrelid = 'starry_api.api_usage_events'::regclass
                             AND constraint_definition.contype = 'p'
                             AND attribute_definition.attname = 'request_hash'
                       ) AS request_hash_primary_key
                """)
            .map((row, metadata) -> new DatabaseIdentity(
                row.get("database_name", String.class),
                row.get("schema_name", String.class),
                row.get("server_version_num", Integer.class),
                Boolean.TRUE.equals(row.get("baseline_applied", Boolean.class)),
                row.get("usage_event_partitions", Integer.class),
                Boolean.TRUE.equals(row.get("request_hash_primary_key", Boolean.class))
            ))
            .one()
            .block();

        assertThat(identity).isNotNull();
        assertThat(identity.databaseName()).isEqualTo("starry_api");
        assertThat(identity.schemaName()).isEqualTo("starry_api");
        assertThat(identity.serverVersionNumber() / 10_000).isEqualTo(expectedPostgresMajorVersion());
        assertThat(identity.baselineApplied()).isTrue();
        assertThat(identity.usageEventPartitions()).isEqualTo(16);
        assertThat(identity.requestHashPrimaryKey()).isTrue();
    }

    @Test
    void persistsAndProtectsTheApplicationRootIdentity() {
        ApplicationRootIdentity root = databaseClient.sql("""
                SELECT settings.setting_value AS marker_user_id,
                       users.user_id,
                       users.username,
                       users.role,
                       users.enabled,
                       EXISTS (
                           SELECT 1
                           FROM starry_api.flyway_schema_history
                           WHERE version = '6' AND success
                       ) AS migration_applied,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_trigger
                           WHERE tgrelid = 'starry_api.system_settings'::pg_catalog.regclass
                             AND tgname = 'trg_application_root_setting'
                             AND NOT tgisinternal
                       ) AS setting_trigger_enabled,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_trigger
                           WHERE tgrelid = 'starry_api.admin_users'::pg_catalog.regclass
                             AND tgname = 'trg_application_root_user'
                             AND NOT tgisinternal
                       ) AS user_trigger_enabled
                FROM starry_api.system_settings AS settings
                JOIN starry_api.admin_users AS users
                  ON users.user_id = settings.setting_value
                WHERE settings.setting_key = 'application_root_user_id'
                """)
            .map((row, metadata) -> new ApplicationRootIdentity(
                row.get("marker_user_id", String.class),
                row.get("user_id", String.class),
                row.get("username", String.class),
                row.get("role", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                Boolean.TRUE.equals(row.get("migration_applied", Boolean.class)),
                Boolean.TRUE.equals(row.get("setting_trigger_enabled", Boolean.class)),
                Boolean.TRUE.equals(row.get("user_trigger_enabled", Boolean.class))
            ))
            .one()
            .block();

        assertThat(root).isNotNull();
        assertThat(root.markerUserId()).isEqualTo(root.userId());
        assertThat(root.username()).isEqualTo("admin");
        assertThat(root.role()).isEqualTo("ADMIN");
        assertThat(root.enabled()).isTrue();
        assertThat(root.migrationApplied()).isTrue();
        assertThat(root.settingTriggerEnabled()).isTrue();
        assertThat(root.userTriggerEnabled()).isTrue();
        assertThat(adminUserRepository.findApplicationRoot().block())
            .extracting(AdminUserEntity::getUserId)
            .isEqualTo(root.userId());

        assertThatThrownBy(() -> executeAsDatabaseOwner("""
            UPDATE starry_api.admin_users
            SET role = 'USER'
            WHERE user_id = '%s'
            """.formatted(root.userId())))
            .hasRootCauseInstanceOf(SQLException.class)
            .hasStackTraceContaining("the application Root identity, role, and enabled state are immutable");
        assertThatThrownBy(() -> executeAsDatabaseOwner("""
            DELETE FROM starry_api.admin_users
            WHERE user_id = '%s'
            """.formatted(root.userId())))
            .hasRootCauseInstanceOf(SQLException.class)
            .hasStackTraceContaining("the application Root account cannot be deleted");
        assertThatThrownBy(() -> executeAsDatabaseOwner("""
            UPDATE starry_api.system_settings
            SET setting_value = 'replacement-root'
            WHERE setting_key = 'application_root_user_id'
            """))
            .hasRootCauseInstanceOf(SQLException.class)
            .hasStackTraceContaining("the application Root identity marker is immutable once assigned");
    }

    @Test
    void enforcesLeastPrivilegeRuntimeRoleAndOwnerBoundaries() {
        RoleSecurityState state = databaseClient.sql("""
                SELECT current_user AS current_user_name,
                       app_role.rolcanlogin
                           AND app_role.rolinherit
                           AND NOT app_role.rolsuper
                           AND NOT app_role.rolcreatedb
                           AND NOT app_role.rolcreaterole
                           AND NOT app_role.rolreplication
                           AND NOT app_role.rolbypassrls AS runtime_is_restricted,
                       NOT runtime_role.rolcanlogin
                           AND NOT runtime_role.rolsuper
                           AND NOT runtime_role.rolcreatedb
                           AND NOT runtime_role.rolcreaterole
                           AND NOT runtime_role.rolreplication
                           AND NOT runtime_role.rolbypassrls AS runtime_group_is_restricted,
                       NOT owner_role.rolcanlogin
                           AND NOT owner_role.rolsuper
                           AND NOT owner_role.rolcreatedb
                           AND NOT owner_role.rolcreaterole
                           AND NOT owner_role.rolreplication
                           AND NOT owner_role.rolbypassrls AS owner_is_restricted,
                       migration_role.rolcanlogin
                           AND NOT migration_role.rolinherit
                           AND NOT migration_role.rolsuper
                           AND NOT migration_role.rolcreatedb
                           AND NOT migration_role.rolcreaterole
                           AND NOT migration_role.rolreplication
                           AND NOT migration_role.rolbypassrls AS migration_requires_set_role,
                       pg_catalog.pg_has_role(
                           'starry_api_migration',
                           'starry_api_owner',
                           'SET'
                       ) AS migration_can_set_owner,
                       pg_catalog.pg_has_role(
                           'starry_api_owner',
                           'starry_api',
                           'USAGE'
                       )
                           AND NOT pg_catalog.pg_has_role(
                               'starry_api_owner',
                               'starry_api',
                               'SET'
                           ) AS owner_can_manage_runtime_sessions,
                       pg_catalog.pg_has_role(current_user, 'starry_api_runtime', 'MEMBER') AS runtime_member,
                       NOT pg_catalog.pg_has_role(current_user, 'starry_api_owner', 'MEMBER') AS not_owner_member,
                       pg_catalog.has_schema_privilege(current_user, 'starry_api', 'USAGE') AS schema_usage,
                       NOT pg_catalog.has_schema_privilege(current_user, 'starry_api', 'CREATE') AS no_schema_create,
                       NOT pg_catalog.has_schema_privilege(current_user, 'public', 'USAGE')
                           AND NOT pg_catalog.has_schema_privilege(current_user, 'public', 'CREATE')
                           AS no_public_schema_access,
                       NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.aclexplode(
                               COALESCE(
                                   database_definition.datacl,
                                   pg_catalog.acldefault('d', database_definition.datdba)
                               )
                           ) database_acl
                           WHERE database_acl.grantee = 0
                       ) AS no_public_database_access,
                       pg_catalog.has_table_privilege(current_user, 'starry_api.admin_users', 'SELECT')
                           AND pg_catalog.has_table_privilege(current_user, 'starry_api.admin_users', 'INSERT')
                           AND pg_catalog.has_table_privilege(current_user, 'starry_api.admin_users', 'UPDATE')
                           AS business_dml,
                       pg_catalog.has_table_privilege(current_user, 'starry_api.balance_ledger', 'SELECT')
                           AND pg_catalog.has_table_privilege(current_user, 'starry_api.balance_ledger', 'INSERT')
                           AS ledger_append,
                       NOT pg_catalog.has_table_privilege(
                           current_user,
                           'starry_api.balance_ledger',
                           'UPDATE,DELETE'
                       ) AS ledger_immutable,
                       NOT pg_catalog.has_table_privilege(
                           current_user,
                           'starry_api.request_logs',
                           'UPDATE,DELETE'
                       )
                           AND NOT pg_catalog.has_table_privilege(
                               current_user,
                               'starry_api.api_usage_events',
                               'UPDATE,DELETE'
                           )
                           AND NOT EXISTS (
                               SELECT 1
                               FROM pg_catalog.pg_inherits inheritance
                               WHERE inheritance.inhparent IN (
                                   'starry_api.request_logs'::regclass,
                                   'starry_api.api_usage_events'::regclass
                               )
                                 AND (
                                     pg_catalog.has_table_privilege(
                                         current_user,
                                         inheritance.inhrelid,
                                         'UPDATE'
                                     )
                                     OR pg_catalog.has_table_privilege(
                                         current_user,
                                         inheritance.inhrelid,
                                         'DELETE'
                                     )
                                 )
                           ) AS audit_tables_append_only,
                       NOT pg_catalog.has_table_privilege(
                           current_user,
                           'starry_api.request_usage_hourly',
                           'INSERT'
                       )
                           AND NOT pg_catalog.has_table_privilege(
                               current_user,
                               'starry_api.request_usage_daily',
                               'INSERT'
                           )
                           AND NOT pg_catalog.has_table_privilege(
                               current_user,
                               'starry_api.aggregation_watermarks',
                               'INSERT'
                           )
                           AND NOT pg_catalog.has_table_privilege(
                               current_user,
                               'starry_api.request_usage_hourly',
                               'DELETE'
                           ) AS derived_aggregate_insert_protected,
                       pg_catalog.has_table_privilege(
                           current_user,
                           'starry_api.flyway_schema_history',
                           'SELECT'
                       )
                           AND NOT pg_catalog.has_table_privilege(
                               current_user,
                               'starry_api.flyway_schema_history',
                               'INSERT,UPDATE,DELETE'
                           ) AS flyway_history_read_only,
                       pg_catalog.has_sequence_privilege(
                           current_user,
                           'starry_api.admin_users_id_seq',
                           'USAGE'
                       ) AS identity_usage,
                       pg_catalog.has_function_privilege(
                           current_user,
                           'starry_api.ensure_request_log_partitions(integer,integer)',
                           'EXECUTE'
                       )
                           AND pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.drop_expired_request_log_partitions(integer)',
                               'EXECUTE'
                           )
                           AND pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.refresh_request_usage_aggregates()',
                               'EXECUTE'
                           )
                           AND pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.refresh_request_usage_aggregates(integer,integer)',
                               'EXECUTE'
                           )
                           AND pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.prune_api_usage_events(integer,integer)',
                               'EXECUTE'
                           )
                           AND pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.prune_expired_request_logs_default(integer,integer)',
                               'EXECUTE'
                           )
                           AND pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.prune_request_usage_hourly(integer,integer)',
                               'EXECUTE'
                           ) AS maintenance_execute,
                       NOT pg_catalog.has_function_privilege(
                           current_user,
                           'starry_api.set_updated_at()',
                           'EXECUTE'
                       )
                           AND NOT pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.reject_balance_ledger_mutation()',
                               'EXECUTE'
                           )
                           AND NOT pg_catalog.has_function_privilege(
                               current_user,
                               'starry_api.notify_system_setting_change()',
                               'EXECUTE'
                           )
                           AND NOT pg_catalog.has_function_privilege(
                               'starry_api_runtime',
                               'starry_api.notify_system_setting_change()',
                               'EXECUTE'
                           ) AS no_direct_trigger_execute,
                       schema_owner.rolname = 'starry_api_owner' AS schema_owned_by_owner,
                       NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_class owned_object
                           WHERE owned_object.relnamespace = schema_definition.oid
                             AND owned_object.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
                             AND owned_object.relowner <> owner_role.oid
                       )
                           AND NOT EXISTS (
                               SELECT 1
                               FROM pg_catalog.pg_proc owned_routine
                               WHERE owned_routine.pronamespace = schema_definition.oid
                                 AND owned_routine.proowner <> owner_role.oid
                           ) AS objects_owned_by_owner,
                       NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_proc routine
                           CROSS JOIN LATERAL pg_catalog.aclexplode(
                               COALESCE(routine.proacl, pg_catalog.acldefault('f', routine.proowner))
                           ) acl
                           INNER JOIN pg_catalog.pg_namespace routine_schema
                               ON routine_schema.oid = routine.pronamespace
                           WHERE routine_schema.nspname = 'starry_api'
                             AND acl.grantee = 0
                             AND acl.privilege_type = 'EXECUTE'
                       ) AS no_public_function_execute
                FROM pg_catalog.pg_roles app_role
                CROSS JOIN pg_catalog.pg_roles runtime_role
                CROSS JOIN pg_catalog.pg_roles owner_role
                CROSS JOIN pg_catalog.pg_roles migration_role
                INNER JOIN pg_catalog.pg_database database_definition
                    ON database_definition.datname = current_database()
                INNER JOIN pg_catalog.pg_namespace schema_definition ON schema_definition.nspname = 'starry_api'
                INNER JOIN pg_catalog.pg_roles schema_owner ON schema_owner.oid = schema_definition.nspowner
                WHERE app_role.rolname = 'starry_api'
                  AND runtime_role.rolname = 'starry_api_runtime'
                  AND owner_role.rolname = 'starry_api_owner'
                  AND migration_role.rolname = 'starry_api_migration'
                """)
            .map((row, metadata) -> new RoleSecurityState(
                row.get("current_user_name", String.class),
                Boolean.TRUE.equals(row.get("runtime_is_restricted", Boolean.class)),
                Boolean.TRUE.equals(row.get("runtime_group_is_restricted", Boolean.class)),
                Boolean.TRUE.equals(row.get("owner_is_restricted", Boolean.class)),
                Boolean.TRUE.equals(row.get("migration_requires_set_role", Boolean.class)),
                Boolean.TRUE.equals(row.get("migration_can_set_owner", Boolean.class)),
                Boolean.TRUE.equals(row.get("owner_can_manage_runtime_sessions", Boolean.class)),
                Boolean.TRUE.equals(row.get("runtime_member", Boolean.class)),
                Boolean.TRUE.equals(row.get("not_owner_member", Boolean.class)),
                Boolean.TRUE.equals(row.get("schema_usage", Boolean.class)),
                Boolean.TRUE.equals(row.get("no_schema_create", Boolean.class)),
                Boolean.TRUE.equals(row.get("no_public_schema_access", Boolean.class)),
                Boolean.TRUE.equals(row.get("no_public_database_access", Boolean.class)),
                Boolean.TRUE.equals(row.get("business_dml", Boolean.class)),
                Boolean.TRUE.equals(row.get("ledger_append", Boolean.class)),
                Boolean.TRUE.equals(row.get("ledger_immutable", Boolean.class)),
                Boolean.TRUE.equals(row.get("audit_tables_append_only", Boolean.class)),
                Boolean.TRUE.equals(row.get("derived_aggregate_insert_protected", Boolean.class)),
                Boolean.TRUE.equals(row.get("flyway_history_read_only", Boolean.class)),
                Boolean.TRUE.equals(row.get("identity_usage", Boolean.class)),
                Boolean.TRUE.equals(row.get("maintenance_execute", Boolean.class)),
                Boolean.TRUE.equals(row.get("no_direct_trigger_execute", Boolean.class)),
                Boolean.TRUE.equals(row.get("schema_owned_by_owner", Boolean.class)),
                Boolean.TRUE.equals(row.get("objects_owned_by_owner", Boolean.class)),
                Boolean.TRUE.equals(row.get("no_public_function_execute", Boolean.class))
            ))
            .one()
            .block();

        assertThat(state).isNotNull();
        assertThat(state.currentUser()).isEqualTo("starry_api");
        assertThat(state.allBoundariesEnforced()).as("Runtime role boundaries: %s", state).isTrue();
    }

    @Test
    void newOwnerFunctionsDoNotInheritPublicExecute() {
        String functionName = "starry_api.test_private_function_" + randomSuffix();
        executeAsDatabaseOwner("CREATE FUNCTION " + functionName
            + "() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        try {
            Boolean publicExecute = databaseClient.sql("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_proc routine
                        CROSS JOIN LATERAL pg_catalog.aclexplode(
                            COALESCE(routine.proacl, pg_catalog.acldefault('f', routine.proowner))
                        ) acl
                        WHERE routine.oid = CAST(:functionName AS regprocedure)
                          AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'
                    ) AS public_execute
                    """)
                .bind("functionName", functionName + "()")
                .map((row, metadata) -> row.get("public_execute", Boolean.class))
                .one()
                .block();
            assertThat(publicExecute).isFalse();
            assertThatThrownBy(() -> databaseClient.sql("SELECT " + functionName + "()")
                .fetch().one().block())
                .isInstanceOf(org.springframework.dao.PermissionDeniedDataAccessException.class);
        } finally {
            executeAsDatabaseOwner("DROP FUNCTION " + functionName + "()");
        }
    }

    @Test
    void ownerCanTerminateRuntimeBackendWithoutClusterWideSignalRole() throws Exception {
        try (
            Connection runtime = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USERNAME, APP_PASSWORD);
            Connection bootstrap = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
            );
            Connection maintenance = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                MIGRATION_USERNAME,
                MIGRATION_PASSWORD
            )
        ) {
            int runtimePid;
            try (Statement statement = runtime.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT pg_catalog.pg_backend_pid()"
            )) {
                assertThat(result.next()).isTrue();
                runtimePid = result.getInt(1);
            }
            int bootstrapPid;
            try (Statement statement = bootstrap.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT pg_catalog.pg_backend_pid()"
            )) {
                assertThat(result.next()).isTrue();
                bootstrapPid = result.getInt(1);
            }

            try (Statement statement = maintenance.createStatement()) {
                statement.execute("SET ROLE starry_api_owner");
            }
            try (Statement statement = maintenance.createStatement(); ResultSet result = statement.executeQuery("""
                SELECT pg_catalog.pg_has_role(current_user, 'starry_api', 'USAGE')
                       AND NOT pg_catalog.pg_has_role(current_user, 'starry_api', 'SET')
                       AND NOT pg_catalog.pg_has_role(current_user, 'pg_signal_backend', 'MEMBER')
                """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
            try (PreparedStatement statement = maintenance.prepareStatement(
                "SELECT pg_catalog.pg_terminate_backend(?, 10000)"
            )) {
                statement.setInt(1, bootstrapPid);
                assertThatThrownBy(statement::executeQuery)
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
            }
            try (Statement statement = bootstrap.createStatement(); ResultSet result = statement.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
            }
            try (PreparedStatement statement = maintenance.prepareStatement(
                "SELECT pg_catalog.pg_terminate_backend(?, 10000)"
            )) {
                statement.setInt(1, runtimePid);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            }

            assertThatThrownBy(() -> runtime.createStatement().execute("SELECT 1"))
                .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void firesOwnerCreatedUpdateTriggerWithoutGrantingDirectFunctionExecution() {
        String settingKey = "trigger-security-" + randomSuffix();
        Instant oldTimestamp = Instant.parse("2000-01-01T00:00:00Z");

        databaseClient.sql("""
                INSERT INTO system_settings (setting_key, setting_value, created_at, updated_at)
                VALUES (:settingKey, 'before', :oldTimestamp, :oldTimestamp)
                """)
            .bind("settingKey", settingKey)
            .bind("oldTimestamp", oldTimestamp)
            .fetch()
            .rowsUpdated()
            .then(databaseClient.sql("""
                    UPDATE system_settings
                    SET setting_value = 'after'
                    WHERE setting_key = :settingKey
                    """)
                .bind("settingKey", settingKey)
                .fetch()
                .rowsUpdated())
            .block();

        Instant updatedAt = databaseClient.sql("""
                SELECT updated_at
                FROM system_settings
                WHERE setting_key = :settingKey
                """)
            .bind("settingKey", settingKey)
            .map((row, metadata) -> row.get("updated_at", Instant.class))
            .one()
            .block();

        assertThat(updatedAt).isAfter(oldTimestamp);
    }

    @Test
    void publishesSystemSettingInsertUpdateAndDeleteNotifications() {
        String suffix = randomSuffix();
        String insertKey = "notify-insert-" + suffix;
        String updateKey = "notify-update-" + suffix;
        String deleteKey = "notify-delete-" + suffix;
        List<String> expectedKeys = List.of(insertKey, updateKey, deleteKey);

        databaseClient.sql("""
                INSERT INTO system_settings (setting_key, setting_value)
                VALUES (:updateKey, 'before-update'), (:deleteKey, 'before-delete')
                """)
            .bind("updateKey", updateKey)
            .bind("deleteKey", deleteKey)
            .fetch()
            .rowsUpdated()
            .block();

        PostgresqlConnection listener = createNotificationConnection();
        try {
            Flux.from(listener.createStatement("LISTEN starry_api_system_settings").execute())
                .flatMap(result -> result.getRowsUpdated())
                .then()
                .block();

            StepVerifier.create(listener.getNotifications()
                    .filter(notification -> expectedKeys.contains(notification.getParameter()))
                    .map(io.r2dbc.postgresql.api.Notification::getParameter)
                    .take(expectedKeys.size()))
                .then(() -> {
                    Long inserted = databaseClient.sql("""
                            INSERT INTO system_settings (setting_key, setting_value)
                            VALUES (:settingKey, 'inserted')
                            """)
                        .bind("settingKey", insertKey)
                        .fetch()
                        .rowsUpdated()
                        .block();
                    Long updated = databaseClient.sql("""
                            UPDATE system_settings
                            SET setting_value = 'updated'
                            WHERE setting_key = :settingKey
                            """)
                        .bind("settingKey", updateKey)
                        .fetch()
                        .rowsUpdated()
                        .block();
                    executeAsDatabaseOwner("""
                        DELETE FROM starry_api.system_settings
                        WHERE setting_key = '%s'
                        """.formatted(deleteKey));

                    assertThat(inserted).isEqualTo(1L);
                    assertThat(updated).isEqualTo(1L);
                })
                .expectNext(insertKey, updateKey, deleteKey)
                .expectComplete()
                .verify(Duration.ofSeconds(10));

            Boolean coversAllEvents = databaseClient.sql("""
                    SELECT (trigger_definition.tgtype & 1) = 1
                           AND (trigger_definition.tgtype & 2) = 0
                           AND (trigger_definition.tgtype & 4) = 4
                           AND (trigger_definition.tgtype & 8) = 8
                           AND (trigger_definition.tgtype & 16) = 16
                           AND (trigger_definition.tgtype & 64) = 0
                    FROM pg_catalog.pg_trigger trigger_definition
                    WHERE trigger_definition.tgrelid = 'starry_api.system_settings'::regclass
                      AND trigger_definition.tgname = 'trg_system_settings_notify'
                      AND NOT trigger_definition.tgisinternal
                    """)
                .map((row, metadata) -> row.get(0, Boolean.class))
                .one()
                .block();
            assertThat(coversAllEvents).isTrue();
        } finally {
            Mono.from(listener.close()).block();
            executeAsDatabaseOwner("""
                DELETE FROM starry_api.system_settings
                WHERE setting_key IN ('%s', '%s', '%s')
                """.formatted(insertKey, updateKey, deleteKey));
        }
    }

    @Test
    void bulkUpsertInsertsAndUpdatesSettingsInOneStatement() {
        String suffix = randomSuffix();
        String firstKey = "bulk-setting-a-" + suffix;
        String secondKey = "bulk-setting-b-" + suffix;

        try {
            Integer inserted = systemSettingRepository.upsertSettings(
                new String[]{firstKey, secondKey},
                new String[]{"first", "second"}
            ).block();
            Integer updated = systemSettingRepository.upsertSettings(
                new String[]{firstKey, secondKey},
                new String[]{"updated", "second-updated"}
            ).block();

            Map<String, String> values = systemSettingRepository.findAllById(List.of(firstKey, secondKey))
                .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
                .block();
            assertThat(inserted).isEqualTo(2);
            assertThat(updated).isEqualTo(2);
            assertThat(values).containsEntry(firstKey, "updated");
            assertThat(values).containsEntry(secondKey, "second-updated");
        } finally {
            executeAsDatabaseOwner("""
                DELETE FROM starry_api.system_settings
                WHERE setting_key IN ('%s', '%s')
                """.formatted(firstKey, secondKey));
        }
    }

    @Test
    void atomicallyReturnsOnePersistedValueWhenSettingIsInitializedConcurrently() {
        String settingKey = "atomic-setting-" + randomSuffix();
        try {
            List<String> values = Flux.range(0, CONCURRENT_REQUESTS)
                .flatMap(index -> systemSettingRepository.getOrCreateSetting(
                    settingKey,
                    "candidate-" + index
                ), CONCURRENT_REQUESTS)
                .collectList()
                .block();

            assertThat(values).hasSize(CONCURRENT_REQUESTS);
            assertThat(values.stream().distinct()).hasSize(1);
            assertThat(settingValue(settingKey)).isEqualTo(values.getFirst());
        } finally {
            executeAsDatabaseOwner("""
                DELETE FROM starry_api.system_settings
                WHERE setting_key = '%s'
                """.formatted(settingKey));
        }
    }

    @Test
    void serializesConcurrentPartialUpdatesForAllSettingGroupsWithoutLosingSecrets() {
        Map<String, String> snapshot = systemSettingsSnapshot();
        String suffix = randomSuffix();

        try {
            accountSettingsService.update(new AccountDtos.UpdateAccountSettingsRequest(false, null, null)).block();
            runConcurrently(
                accountSettingsService.update(new AccountDtos.UpdateAccountSettingsRequest(true, null, null)),
                accountSettingsService.update(new AccountDtos.UpdateAccountSettingsRequest(
                    null,
                    null,
                    new AccountDtos.PoolPolicySettings(null, 321, null, null, null, null, null, null, null)
                ))
            );

            AccountDtos.AccountSettingsResponse accountSettings = accountSettingsService.settings().block();
            assertThat(accountSettings).isNotNull();
            assertThat(accountSettings.gptFastModeEnabled()).isTrue();
            assertThat(accountSettings.poolPolicy().windowMinutes()).isEqualTo(321);

            String paymentSecret = "payment-secret-" + suffix;
            paymentSettingsService.update(paymentSettingsUpdate(false, "payment-before-" + suffix, paymentSecret)).block();
            runConcurrently(
                paymentSettingsService.update(paymentSettingsUpdate(true, null, null)),
                paymentSettingsService.update(paymentSettingsUpdate(null, "payment-after-" + suffix, ""))
            );

            PaymentConfig paymentSettings = paymentSettingsService.config().block();
            assertThat(paymentSettings).isNotNull();
            assertThat(paymentSettings.enabled()).isTrue();
            assertThat(paymentSettings.productNamePrefix()).isEqualTo("payment-after-" + suffix);
            assertThat(settingValue("EASYPAY_PKEY")).isEqualTo(paymentSecret);

            String smtpSecret = "smtp-secret-" + suffix;
            String turnstileSecret = "turnstile-secret-" + suffix;
            registrationSettingsService.update(registrationSettingsUpdate(
                false,
                "smtp-before-" + suffix,
                smtpSecret,
                true,
                "turnstile-site-" + suffix,
                turnstileSecret
            )).block();
            runConcurrently(
                registrationSettingsService.update(registrationSettingsUpdate(
                    true, null, null, null, null, null
                )),
                registrationSettingsService.update(registrationSettingsUpdate(
                    null, "smtp-after-" + suffix, "", null, null, ""
                ))
            );

            AdminDtos.RegistrationSettingsResponse registrationSettings = registrationSettingsService.settings().block();
            assertThat(registrationSettings).isNotNull();
            assertThat(registrationSettings.registrationEnabled()).isTrue();
            assertThat(registrationSettings.smtpHost()).isEqualTo("smtp-after-" + suffix);
            assertThat(registrationSettings.smtpPasswordConfigured()).isTrue();
            assertThat(registrationSettings.turnstileSecretConfigured()).isTrue();
            assertThat(settingValue("smtp_password")).isEqualTo(smtpSecret);
            assertThat(settingValue("turnstile_secret_key")).isEqualTo(turnstileSecret);
        } finally {
            restoreSystemSettings(snapshot);
        }
    }

    @Test
    void listenerInvalidatesHotSettingCachesBeforeTheirFiveSecondTtl() {
        Map<String, String> snapshot = systemSettingsSnapshot();

        try {
            systemSettingNotificationListener.start();
            awaitSystemSettingNotificationListener();
            systemSettingRepository.upsertSettings(
                new String[]{"gpt_fast_mode_enabled", "payment_enabled"},
                new String[]{"true", "true"}
            ).block();
            Mono.delay(Duration.ofMillis(250)).block();
            accountSettingsService.invalidateAllCaches();
            paymentSettingsService.invalidateAllCaches();
            assertThat(accountSettingsService.gptFastModeEnabled().block()).isTrue();
            assertThat(paymentSettingsService.config().block().enabled()).isTrue();

            long startedAt = System.nanoTime();
            Long updated = databaseClient.sql("""
                    UPDATE system_settings
                    SET setting_value = 'false'
                    WHERE setting_key IN ('gpt_fast_mode_enabled', 'payment_enabled')
                    """)
                .fetch()
                .rowsUpdated()
                .block();
            Boolean observed = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
                .concatMap(ignored -> Mono.zip(
                    accountSettingsService.gptFastModeEnabled(),
                    paymentSettingsService.config(),
                    (gptFastModeEnabled, paymentConfig) -> !gptFastModeEnabled && !paymentConfig.enabled()
                ))
                .filter(Boolean.TRUE::equals)
                .next()
                .timeout(Duration.ofMillis(1_900))
                .block();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(updated).isEqualTo(2L);
            assertThat(observed).isTrue();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        } finally {
            restoreSystemSettings(snapshot);
        }
    }

    @Test
    void savesAndReadsModelMappingJsonbThroughRepository() throws Exception {
        String suffix = randomSuffix();
        Instant now = Instant.now();
        ModelMappingEntity mapping = new ModelMappingEntity();
        mapping.setMappingId("mapping-" + suffix);
        mapping.setName("PostgreSQL JSONB " + suffix);
        mapping.setEnabled(true);
        mapping.setMappingType("EXACT");
        mapping.setSourceModel("source-" + suffix);
        mapping.setTargetModels("[\"gpt-4.1\",\"o3\"]");
        mapping.setWeights("[70,30]");
        mapping.setPriority(10);
        mapping.setApiKeyIds("[\"key-a\",\"key-b\"]");
        mapping.setCreatedAt(now);
        mapping.setUpdatedAt(now);

        ModelMappingEntity saved = modelMappingRepository.save(mapping).block();
        ModelMappingEntity loaded = modelMappingRepository.findByMappingId(mapping.getMappingId()).block();

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(loaded).isNotNull();
        assertThat(objectMapper.readTree(loaded.getTargetModels()))
            .isEqualTo(objectMapper.readTree(mapping.getTargetModels()));
        assertThat(objectMapper.readTree(loaded.getWeights()))
            .isEqualTo(objectMapper.readTree(mapping.getWeights()));
        assertThat(objectMapper.readTree(loaded.getApiKeyIds()))
            .isEqualTo(objectMapper.readTree(mapping.getApiKeyIds()));

        JsonbState jsonbState = databaseClient.sql("""
                SELECT pg_typeof(target_models)::text AS target_type,
                       target_models @> '[\"o3\"]'::jsonb AS contains_target,
                       jsonb_array_length(api_key_ids) AS api_key_count
                FROM model_mappings
                WHERE mapping_id = :mappingId
                """)
            .bind("mappingId", mapping.getMappingId())
            .map((row, metadata) -> new JsonbState(
                row.get("target_type", String.class),
                Boolean.TRUE.equals(row.get("contains_target", Boolean.class)),
                row.get("api_key_count", Integer.class)
            ))
            .one()
            .block();

        assertThat(jsonbState).isEqualTo(new JsonbState("jsonb", true, 2));
    }

    @Test
    void enforcesAndQueriesCaseInsensitiveUserIdentity() {
        String suffix = randomSuffix();
        String username = "CaseUser" + suffix;
        String email = "Case" + suffix + "@Example.COM";
        String affiliateCode = "Invite" + suffix;
        insertUser("user-" + suffix, username, email, affiliateCode).block();

        assertThat(adminUserRepository.findByUsername(username.toLowerCase()).block())
            .extracting(user -> user.getUserId())
            .isEqualTo("user-" + suffix);
        assertThat(adminUserRepository.findByEmail(email.toLowerCase()).block())
            .extracting(user -> user.getUserId())
            .isEqualTo("user-" + suffix);
        assertThat(adminUserRepository.findByAffiliateCode(affiliateCode.toLowerCase()).block())
            .extracting(user -> user.getUserId())
            .isEqualTo("user-" + suffix);

        assertUniqueViolation(insertUser(
            "duplicate-username-" + suffix,
            username.toLowerCase(),
            "other-username-" + suffix + "@example.com",
            "other-username-" + suffix
        ));
        assertUniqueViolation(insertUser(
            "duplicate-email-" + suffix,
            "OtherEmail" + suffix,
            email.toLowerCase(),
            "other-email-" + suffix
        ));
        assertUniqueViolation(insertUser(
            "duplicate-affiliate-" + suffix,
            "OtherAffiliate" + suffix,
            "other-affiliate-" + suffix + "@example.com",
            affiliateCode.toLowerCase()
        ));
    }

    @Test
    void routesRequestLogsAndMaintainsMonthlyPartitions() {
        String requestId = "request-" + randomSuffix();
        Instant createdAt = Instant.now();
        databaseClient.sql("""
                INSERT INTO request_logs (
                    request_id, path, status_code, success, input_tokens, output_tokens,
                    credits, latency_ms, created_at
                )
                VALUES (:requestId, '/v1/chat/completions', 200, TRUE, 10, 5, 0.25, 12, :createdAt)
                """)
            .bind("requestId", requestId)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .block();

        String routedTable = databaseClient.sql("""
                SELECT tableoid::regclass::text AS routed_table
                FROM request_logs
                WHERE request_id = :requestId
                """)
            .bind("requestId", requestId)
            .map((row, metadata) -> row.get("routed_table", String.class))
            .one()
            .block();
        String expectedCurrentPartition = "request_logs_"
            + YearMonth.from(createdAt.atZone(ZoneOffset.UTC)).format(DateTimeFormatter.ofPattern("'y'yyy'M'MM").withLocale(java.util.Locale.ROOT))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(routedTable).endsWith(expectedCurrentPartition);

        callPartitionFunction("SELECT starry_api.ensure_request_log_partitions(0, 5)");
        YearMonth oldestMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(5);
        String oldestPartition = "starry_api.request_logs_"
            + oldestMonth.format(DateTimeFormatter.ofPattern("'y'yyy'M'MM").withLocale(java.util.Locale.ROOT))
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(relationExists(oldestPartition)).isTrue();
        assertThat(hasAppendOnlyRuntimePrivileges(oldestPartition)).isTrue();

        Integer droppedPartitions = databaseClient.sql(
                "SELECT starry_api.drop_expired_request_log_partitions(4) AS dropped_count")
            .map((row, metadata) -> row.get("dropped_count", Integer.class))
            .one()
            .block();

        assertThat(droppedPartitions).isGreaterThanOrEqualTo(2);
        assertThat(relationExists(oldestPartition)).isFalse();
        assertThat(relationExists("starry_api." + expectedCurrentPartition)).isTrue();
    }

    @Test
    void appliesEpochIsolatedModelFamilyCircuitMigrationsAcrossRequestLogPartitions() {
        Map<String, Object> state = databaseClient.sql("""
                SELECT EXISTS (
                           SELECT 1
                           FROM starry_api.flyway_schema_history
                           WHERE version = '2' AND success
                       ) AS model_pool_migration_applied,
                       EXISTS (
                           SELECT 1
                           FROM starry_api.flyway_schema_history
                           WHERE version = '5' AND success
                       ) AS epoch_migration_applied,
                       (
                           SELECT setting_value = 'false'
                           FROM starry_api.system_settings
                           WHERE setting_key = 'account_pool_policy_enabled'
                       ) AS policy_disabled,
                       (
                           SELECT pg_catalog.format_type(atttypid, atttypmod) = 'character varying(128)'
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::regclass
                             AND attname = 'circuit_key'
                             AND NOT attisdropped
                       ) AS circuit_key_type,
                       (
                           SELECT pg_catalog.format_type(atttypid, atttypmod) = 'character varying(16)'
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::regclass
                             AND attname = 'circuit_tier'
                             AND NOT attisdropped
                       ) AS circuit_tier_type,
                       (
                           SELECT pg_catalog.format_type(atttypid, atttypmod) = 'character varying(256)'
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::regclass
                             AND attname = 'upstream_model'
                             AND NOT attisdropped
                       ) AS upstream_model_type,
                       (
                           SELECT pg_catalog.format_type(atttypid, atttypmod) = 'boolean'
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::regclass
                             AND attname = 'upstream_success'
                             AND NOT attisdropped
                       ) AS upstream_success_type,
                       (
                           SELECT pg_catalog.format_type(atttypid, atttypmod) = 'uuid'
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::regclass
                             AND attname = 'circuit_epoch'
                             AND NOT attisdropped
                       ) AS circuit_epoch_type,
                       (
                           SELECT COUNT(*) = 2
                           FROM starry_api.system_settings
                           WHERE (setting_key = 'account_pool_policy_epoch' AND setting_value = '')
                              OR (
                                  setting_key = 'account_pool_policy_activation_started_at'
                                  AND setting_value = ''
                              )
                       ) AS epoch_settings_initialized,
                       NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_inherits table_partition
                           CROSS JOIN (
                               VALUES
                                   ('circuit_key'),
                                   ('circuit_tier'),
                                   ('upstream_model'),
                                   ('upstream_success'),
                                   ('circuit_epoch')
                           ) AS expected_column(column_name)
                           WHERE table_partition.inhparent = 'starry_api.request_logs'::regclass
                             AND NOT EXISTS (
                                 SELECT 1
                                 FROM pg_catalog.pg_attribute partition_column
                                 WHERE partition_column.attrelid = table_partition.inhrelid
                                   AND partition_column.attname = expected_column.column_name
                                   AND NOT partition_column.attisdropped
                             )
                       ) AS all_partitions_have_columns,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_constraint
                           WHERE conrelid = 'starry_api.request_logs'::regclass
                             AND conname = 'ck_request_logs_circuit_key'
                             AND contype = 'c'
                             AND convalidated
                       ) AS circuit_key_constraint,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_constraint
                           WHERE conrelid = 'starry_api.request_logs'::regclass
                             AND conname = 'ck_request_logs_circuit_tier'
                             AND contype = 'c'
                             AND convalidated
                       ) AS circuit_tier_constraint,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_index index_definition
                           WHERE index_definition.indexrelid =
                               'starry_api.idx_request_logs_circuit_window'::regclass
                             AND index_definition.indrelid = 'starry_api.request_logs'::regclass
                             AND index_definition.indisvalid
                             AND index_definition.indisready
                             AND index_definition.indnkeyatts = 3
                             AND index_definition.indnatts = 5
                             AND pg_catalog.pg_get_indexdef(index_definition.indexrelid, 1, TRUE)
                                 = 'circuit_epoch'
                             AND pg_catalog.pg_get_indexdef(index_definition.indexrelid, 2, TRUE)
                                 = 'created_at'
                             AND (
                                 (index_definition.indoption::smallint[])[1] & 1
                             ) = 1
                             AND pg_catalog.pg_get_indexdef(index_definition.indexrelid, 3, TRUE)
                                 = 'circuit_key'
                             AND pg_catalog.pg_get_indexdef(index_definition.indexrelid, 4, TRUE)
                                 = 'circuit_tier'
                             AND pg_catalog.pg_get_indexdef(index_definition.indexrelid, 5, TRUE)
                                 = 'upstream_success'
                             AND pg_catalog.pg_get_expr(
                                 index_definition.indpred,
                                 index_definition.indrelid
                             ) = '((circuit_epoch IS NOT NULL) AND (circuit_key IS NOT NULL) AND (upstream_success IS NOT NULL))'
                       ) AS partial_covering_index,
                       (
                           SELECT COUNT(*)
                           FROM pg_catalog.pg_inherits index_partition
                           WHERE index_partition.inhparent =
                               'starry_api.idx_request_logs_circuit_window'::regclass
                       ) = (
                           SELECT COUNT(*)
                           FROM pg_catalog.pg_inherits table_partition
                           WHERE table_partition.inhparent = 'starry_api.request_logs'::regclass
                       ) AS all_partitions_have_index
                """)
            .fetch()
            .one()
            .block();

        assertThat(state)
            .containsEntry("model_pool_migration_applied", true)
            .containsEntry("epoch_migration_applied", true)
            .containsEntry("policy_disabled", true)
            .containsEntry("circuit_key_type", true)
            .containsEntry("circuit_tier_type", true)
            .containsEntry("upstream_model_type", true)
            .containsEntry("upstream_success_type", true)
            .containsEntry("circuit_epoch_type", true)
            .containsEntry("epoch_settings_initialized", true)
            .containsEntry("all_partitions_have_columns", true)
            .containsEntry("circuit_key_constraint", true)
            .containsEntry("circuit_tier_constraint", true)
            .containsEntry("partial_covering_index", true)
            .containsEntry("all_partitions_have_index", true);
    }

    @Test
    void partitionMaintenancePreservesCircuitMetadataMovedFromDefaultPartition() {
        String requestId = "circuit-partition-move-" + randomSuffix();
        UUID circuitEpoch = UUID.randomUUID();
        YearMonth targetMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(24);
        Instant createdAt = targetMonth.atDay(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        String expectedPartition = "request_logs_"
            + targetMonth.format(
                DateTimeFormatter.ofPattern("'y'yyy'M'MM").withLocale(java.util.Locale.ROOT)
            ).toLowerCase(java.util.Locale.ROOT);

        databaseClient.sql("""
                INSERT INTO request_logs (
                    request_id, account_id, path, model, status_code, success,
                    input_tokens, output_tokens, credits, latency_ms, error_message,
                    circuit_key, circuit_tier, upstream_model, upstream_success, circuit_epoch, created_at
                )
                VALUES (
                    :requestId, 'deepseek-main', '/v1/chat/completions', 'deepseek-v4-flash',
                    503, FALSE, 11, 7, 0.5, 42, 'upstream unavailable',
                    'deepseek:deepseek-v4', 'secondary', 'deepseek-v4-flash', FALSE,
                    :circuitEpoch, :createdAt
                )
                """)
            .bind("requestId", requestId)
            .bind("circuitEpoch", circuitEpoch)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .block();

        try {
            String initialPartition = databaseClient.sql("""
                    SELECT tableoid::regclass::text AS routed_partition
                    FROM request_logs
                    WHERE request_id = :requestId
                    """)
                .bind("requestId", requestId)
                .map((row, metadata) -> row.get("routed_partition", String.class))
                .one()
                .block();
            assertThat(initialPartition).endsWith("request_logs_default");

            callPartitionFunction("SELECT starry_api.ensure_request_log_partitions(0, 24)");

            Map<String, Object> movedRow = databaseClient.sql("""
                    SELECT tableoid::regclass::text AS routed_partition,
                           circuit_key,
                           circuit_tier,
                           upstream_model,
                           upstream_success,
                           circuit_epoch
                    FROM request_logs
                    WHERE request_id = :requestId
                    """)
                .bind("requestId", requestId)
                .fetch()
                .one()
                .block();

            assertThat(movedRow)
                .containsEntry("circuit_key", "deepseek:deepseek-v4")
                .containsEntry("circuit_tier", "secondary")
                .containsEntry("upstream_model", "deepseek-v4-flash")
                .containsEntry("upstream_success", false)
                .containsEntry("circuit_epoch", circuitEpoch);
            assertThat(movedRow.get("routed_partition").toString()).endsWith(expectedPartition);
        } finally {
            executeAsDatabaseOwner(
                "DELETE FROM starry_api.request_logs WHERE request_id = '" + requestId + "'"
            );
        }
    }

    @Test
    void prunesExpiredApiUsageEventsInBoundedBatches() {
        String currentHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String keyId = "retention-key-" + randomSuffix();
        executeAsDatabaseOwner(
            "DELETE FROM starry_api.api_usage_events "
                + "WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '30 days'"
        );
        databaseClient.sql("""
                INSERT INTO api_usage_events (
                    request_hash, request_id, key_id, usage_date, credits,
                    input_tokens, output_tokens, charge_balance, created_at
                )
                SELECT
                    md5(:keyId || series::text) || md5(series::text || :keyId),
                    :keyId || '-' || series::text,
                    :keyId,
                    CURRENT_DATE - 31,
                    0,
                    0,
                    0,
                    FALSE,
                    CURRENT_TIMESTAMP - INTERVAL '31 days'
                FROM pg_catalog.generate_series(1, 101) AS series
                """)
            .bind("keyId", keyId)
            .fetch()
            .rowsUpdated()
            .block();
        databaseClient.sql("""
                INSERT INTO api_usage_events (
                    request_hash, request_id, key_id, usage_date, credits,
                    input_tokens, output_tokens, charge_balance, created_at
                )
                VALUES (:currentHash, :requestId, :keyId, CURRENT_DATE, 0, 0, 0, FALSE, CURRENT_TIMESTAMP)
                """)
            .bind("currentHash", currentHash)
            .bind("requestId", "retention-current-" + randomSuffix())
            .bind("keyId", keyId)
            .fetch()
            .rowsUpdated()
            .block();

        Integer deletedRows = databaseClient.sql(
                "SELECT starry_api.prune_api_usage_events(30, 100) AS deleted_count")
            .map((row, metadata) -> row.get("deleted_count", Integer.class))
            .one()
            .block();

        Long remainingExpiredRows = databaseClient.sql("""
                SELECT COUNT(*)::bigint AS event_count
                FROM api_usage_events
                WHERE key_id = :keyId
                  AND created_at < CURRENT_TIMESTAMP - INTERVAL '30 days'
                """)
            .bind("keyId", keyId)
            .map((row, metadata) -> row.get("event_count", Long.class))
            .one()
            .block();

        assertThat(deletedRows).isEqualTo(100);
        assertThat(remainingExpiredRows).isEqualTo(1L);
        assertThat(apiUsageEventExists(currentHash)).isTrue();
        executeAsDatabaseOwner(
            "DELETE FROM starry_api.api_usage_events WHERE key_id = '" + keyId + "'"
        );
    }

    @Test
    void prunesExpiredDefaultRequestLogsInBoundedBatches() {
        String marker = "raw-default-retention-" + randomSuffix();
        executeAsDatabaseOwner(
            "DELETE FROM starry_api.request_logs_default WHERE path = '/%s'".formatted(marker)
        );

        try {
            executeAsDatabaseOwner("""
                INSERT INTO starry_api.request_logs_default (
                    request_id, path, status_code, success, input_tokens, output_tokens,
                    credits, latency_ms, created_at
                )
                SELECT
                    '%1$s-' || series::text,
                    '/%1$s',
                    200,
                    TRUE,
                    0,
                    0,
                    0,
                    0,
                    CASE
                        WHEN series <= 101 THEN CURRENT_TIMESTAMP - INTERVAL '10 years'
                        ELSE CURRENT_TIMESTAMP + INTERVAL '10 years'
                    END
                FROM pg_catalog.generate_series(1, 102) AS series
                """.formatted(marker));

            Integer deletedRows = databaseClient.sql("""
                    SELECT starry_api.prune_expired_request_logs_default(4, 100) AS deleted_count
                    """)
                .map((row, metadata) -> row.get("deleted_count", Integer.class))
                .one()
                .block();
            Map<String, Object> state = databaseClient.sql("""
                    SELECT COUNT(*) FILTER (
                               WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '1 year'
                           )::bigint AS expired_count,
                           COUNT(*) FILTER (
                               WHERE created_at > CURRENT_TIMESTAMP + INTERVAL '1 year'
                           )::bigint AS future_count
                    FROM request_logs_default
                    WHERE path = :path
                    """)
                .bind("path", "/" + marker)
                .fetch()
                .one()
                .block();

            assertThat(deletedRows).isEqualTo(100);
            assertThat(state)
                .containsEntry("expired_count", 1L)
                .containsEntry("future_count", 1L);
        } finally {
            executeAsDatabaseOwner(
                "DELETE FROM starry_api.request_logs_default WHERE path = '/%s'".formatted(marker)
            );
        }
    }

    @Test
    void prunesExpiredHourlyAggregatesInBoundedBatches() {
        String marker = "hourly-retention-" + randomSuffix();
        executeAsDatabaseOwner(
            "DELETE FROM starry_api.request_usage_hourly WHERE model LIKE '%s%%'".formatted(marker)
        );

        try {
            executeAsDatabaseOwner("""
                INSERT INTO starry_api.request_usage_hourly (
                    bucket_start, model, request_count, successful_requests, failed_requests,
                    input_tokens, output_tokens, credits, total_latency_ms, max_latency_ms
                )
                SELECT
                    CASE
                        WHEN series <= 101
                            THEN pg_catalog.date_trunc('hour', CURRENT_TIMESTAMP, 'UTC') - INTERVAL '100 days'
                        ELSE pg_catalog.date_trunc('hour', CURRENT_TIMESTAMP, 'UTC')
                    END,
                    '%1$s' || series::text,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                FROM pg_catalog.generate_series(1, 102) AS series
                """.formatted(marker));

            Integer deletedRows = databaseClient.sql("""
                    SELECT starry_api.prune_request_usage_hourly(90, 100) AS deleted_count
                    """)
                .map((row, metadata) -> row.get("deleted_count", Integer.class))
                .one()
                .block();
            Map<String, Object> state = databaseClient.sql("""
                    SELECT COUNT(*) FILTER (
                               WHERE bucket_start < pg_catalog.date_trunc('day', CURRENT_TIMESTAMP, 'UTC')
                                   - INTERVAL '90 days'
                           )::bigint AS expired_count,
                           COUNT(*) FILTER (
                               WHERE bucket_start >= pg_catalog.date_trunc('day', CURRENT_TIMESTAMP, 'UTC')
                                   - INTERVAL '90 days'
                           )::bigint AS current_count
                    FROM request_usage_hourly
                    WHERE model LIKE :modelPrefix
                    """)
                .bind("modelPrefix", marker + "%")
                .fetch()
                .one()
                .block();

            assertThat(deletedRows).isEqualTo(100);
            assertThat(state)
                .containsEntry("expired_count", 1L)
                .containsEntry("current_count", 1L);
        } finally {
            executeAsDatabaseOwner(
                "DELETE FROM starry_api.request_usage_hourly WHERE model LIKE '%s%%'".formatted(marker)
            );
        }
    }

    @Test
    void rejectsNullRetentionPruneArguments() throws Exception {
        try (
            Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USERNAME, APP_PASSWORD);
            Statement statement = connection.createStatement()
        ) {
            assertThatThrownBy(() -> statement.execute(
                "SELECT starry_api.prune_api_usage_events(NULL, 100)"
            ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("retention_days must be between 30 and 3650");
            assertThatThrownBy(() -> statement.execute(
                "SELECT starry_api.prune_api_usage_events(30, NULL)"
            ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("delete_batch_size must be between 100 and 100000");
            assertThatThrownBy(() -> statement.execute(
                "SELECT starry_api.prune_expired_request_logs_default(NULL, 100)"
            ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("request log retention must be between 4 and 120 months");
            assertThatThrownBy(() -> statement.execute(
                "SELECT starry_api.prune_expired_request_logs_default(4, NULL)"
            ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("delete_batch_size must be between 100 and 100000");
            assertThatThrownBy(() -> statement.execute(
                "SELECT starry_api.prune_request_usage_hourly(NULL, 100)"
            ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("hourly retention must be between 7 and 3650 days");
            assertThatThrownBy(() -> statement.execute(
                "SELECT starry_api.prune_request_usage_hourly(90, NULL)"
            ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("delete_batch_size must be between 100 and 100000");
        }
    }

    @Test
    void steadyStatePartitionMaintenanceDoesNotWaitForAnExclusiveTableLock() throws Exception {
        callPartitionFunction("SELECT starry_api.ensure_request_log_partitions(12, 1)");

        try (Connection blocker = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        )) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE ONLY starry_api.request_logs IN ACCESS SHARE MODE");
                databaseClient.sql("SELECT starry_api.ensure_request_log_partitions(12, 1)")
                    .fetch()
                    .all()
                    .then()
                    .block(Duration.ofSeconds(2));
            } finally {
                blocker.rollback();
            }
        }
    }

    @Test
    void appliesPerformanceOptimizedPostgreSqlLayout() {
        Map<String, Object> state = databaseClient.sql("""
                SELECT (
                           SELECT COUNT(*)::integer
                           FROM pg_catalog.pg_inherits
                           WHERE inhparent = 'starry_api.balance_ledger'::regclass
                       ) AS balance_partition_count,
                       pg_catalog.pg_get_partkeydef('starry_api.balance_ledger'::regclass)
                           = 'HASH (idempotency_key)' AS balance_hash_partitioned,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_constraint
                           WHERE conrelid = 'starry_api.balance_ledger'::regclass
                             AND contype = 'p'
                             AND pg_catalog.pg_get_constraintdef(oid) = 'PRIMARY KEY (idempotency_key)'
                       ) AS balance_idempotency_primary_key,
                       NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_inherits inheritance
                           WHERE inheritance.inhparent = 'starry_api.balance_ledger'::regclass
                             AND (
                                 NOT pg_catalog.has_table_privilege(current_user, inheritance.inhrelid, 'SELECT,INSERT')
                                 OR pg_catalog.has_table_privilege(current_user, inheritance.inhrelid, 'UPDATE,DELETE')
                             )
                       ) AS balance_partition_privileges,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_indexes
                           WHERE schemaname = 'starry_api'
                             AND tablename = 'balance_ledger'
                             AND indexname = 'idx_balance_ledger_created_at_brin'
                             AND lower(indexdef) LIKE '%using brin%'
                       ) AS balance_brin,
                       pg_catalog.to_regclass('starry_api.idx_invite_rebate_created') IS NOT NULL
                           AS invite_created_index,
                       pg_catalog.to_regclass('starry_api.idx_payment_orders_completed_user_effective') IS NOT NULL
                           AS payment_rebate_rank_index,
                       pg_catalog.to_regclass('starry_api.idx_payment_orders_provider_reconciliation') IS NOT NULL
                           AND position('reconciliation_claim_until' IN pg_catalog.pg_get_indexdef(
                               pg_catalog.to_regclass('starry_api.idx_payment_orders_provider_reconciliation')
                           )) > 0 AS payment_provider_reconciliation_index,
                       COALESCE((
                           SELECT reloptions @> ARRAY[
                               'fillfactor=85',
                               'autovacuum_vacuum_scale_factor=0.02',
                               'autovacuum_analyze_scale_factor=0.01'
                           ]
                           FROM pg_catalog.pg_class
                           WHERE oid = 'starry_api.payment_orders'::regclass
                       ), FALSE) AS payment_hot_storage_tuned,
                       pg_catalog.to_regclass('starry_api.idx_request_logs_user_created') IS NOT NULL
                           AS request_user_index,
                       pg_catalog.to_regclass('starry_api.idx_proxy_accounts_available_pool') IS NULL
                           AND pg_catalog.to_regclass('starry_api.idx_proxy_accounts_quota_5h_reset') IS NULL
                           AND pg_catalog.to_regclass('starry_api.idx_proxy_accounts_quota_7d_reset') IS NULL
                           AS unused_hot_indexes_removed,
                       position('updated_at' IN pg_catalog.pg_get_indexdef(
                           pg_catalog.to_regclass('starry_api.idx_proxy_accounts_recovery')
                       )) = 0 AS recovery_index_excludes_hot_columns,
                       COALESCE((
                           SELECT reloptions @> ARRAY[
                               'fillfactor=75',
                               'autovacuum_vacuum_scale_factor=0.01',
                               'autovacuum_analyze_scale_factor=0.01'
                           ]
                           FROM pg_catalog.pg_class
                           WHERE oid = 'starry_api.proxy_accounts'::regclass
                       ), FALSE) AS proxy_hot_storage_tuned,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::regclass
                             AND attname = 'user_id'
                             AND NOT attnotnull
                       ) AS request_user_attribution,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_constraint
                           WHERE conrelid = 'starry_api.request_usage_hourly'::regclass
                             AND contype = 'p'
                             AND position('user_id' IN pg_catalog.pg_get_constraintdef(oid)) > 0
                       ) AND EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_constraint
                           WHERE conrelid = 'starry_api.request_usage_daily'::regclass
                             AND contype = 'p'
                             AND position('user_id' IN pg_catalog.pg_get_constraintdef(oid)) > 0
                       ) AS aggregate_user_primary_keys
                """)
            .fetch()
            .one()
            .block();

        assertThat(state).isNotNull();
        assertThat(state)
            .containsEntry("balance_partition_count", 16)
            .containsEntry("balance_hash_partitioned", true)
            .containsEntry("balance_idempotency_primary_key", true)
            .containsEntry("balance_partition_privileges", true)
            .containsEntry("balance_brin", true)
            .containsEntry("invite_created_index", true)
            .containsEntry("payment_rebate_rank_index", true)
            .containsEntry("payment_provider_reconciliation_index", true)
            .containsEntry("payment_hot_storage_tuned", true)
            .containsEntry("request_user_index", true)
            .containsEntry("unused_hot_indexes_removed", true)
            .containsEntry("recovery_index_excludes_hot_columns", true)
            .containsEntry("proxy_hot_storage_tuned", true)
            .containsEntry("request_user_attribution", true)
            .containsEntry("aggregate_user_primary_keys", true);
    }

    @Test
    void balanceLedgerHashPartitionKeepsIdempotentConflictTarget() {
        String suffix = randomSuffix();
        String idempotencyKey = "ledger-partition-" + suffix;
        String sql = """
            INSERT INTO balance_ledger (
                transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                reference_type, reference_id, idempotency_key
            )
            VALUES (
                :transactionId, :userId, 'ADMIN_ADJUSTMENT', 1, 0, 1,
                'INTEGRATION_TEST', :referenceId, :idempotencyKey
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

        Long first = insertLedgerForPartitionTest(sql, "transaction-a-" + suffix, "user-" + suffix, suffix, idempotencyKey);
        Long replay = insertLedgerForPartitionTest(sql, "transaction-b-" + suffix, "user-" + suffix, suffix, idempotencyKey);
        Map<String, Object> state = databaseClient.sql("""
                SELECT COUNT(*)::integer AS entry_count,
                       MIN(tableoid::regclass::text) AS routed_partition
                FROM balance_ledger
                WHERE idempotency_key = :idempotencyKey
                """)
            .bind("idempotencyKey", idempotencyKey)
            .fetch()
            .one()
            .block();

        assertThat(first).isEqualTo(1L);
        assertThat(replay).isZero();
        assertThat(state).isNotNull().containsEntry("entry_count", 1);
        assertThat(state.get("routed_partition").toString()).contains("balance_ledger_p");
    }

    @Test
    void userUsageSummaryCombinesDailyHourlyAndRawSegments() {
        String suffix = randomSuffix();
        String userId = "segments-user-" + suffix;
        String keyId = "segments-key-" + suffix;
        String model = "segments-model-" + suffix;
        Instant watermark = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS);
        cleanupSegmentedUsage(userId);
        try {
            // Derived tables are owner-managed; the runtime role must remain read-only.
            executeAsDatabaseOwner(
                "INSERT INTO starry_api.aggregation_watermarks (job_name, last_processed_at) "
                    + "VALUES ('request_usage', '%s')".formatted(watermark),
                """
                    INSERT INTO starry_api.request_usage_daily (
                        usage_date, api_key_id, user_id, account_id, model,
                        request_count, successful_requests, failed_requests,
                        input_tokens, output_tokens, credits, total_latency_ms, max_latency_ms, last_request_at
                    )
                    VALUES (
                        '%s', '%s', '%s', '', '%s',
                        1, 1, 0, 10, 5, 0.10, 10, 10, '%s'
                    )
                    """.formatted(
                        watermark.minus(1, ChronoUnit.DAYS).atZone(ZoneOffset.UTC).toLocalDate(),
                        keyId, userId, model, watermark.minus(1, ChronoUnit.DAYS)
                    ),
                """
                    INSERT INTO starry_api.request_usage_hourly (
                        bucket_start, api_key_id, user_id, account_id, model,
                        request_count, successful_requests, failed_requests,
                        input_tokens, output_tokens, credits, total_latency_ms, max_latency_ms, last_request_at
                    )
                    VALUES (
                        '%s', '%s', '%s', '', '%s',
                        2, 1, 1, 20, 10, 0.20, 20, 15, '%s'
                    )
                    """.formatted(
                        watermark.minus(1, ChronoUnit.HOURS), keyId, userId, model,
                        watermark.minus(30, ChronoUnit.MINUTES)
                    )
            );
            databaseClient.sql("""
                    INSERT INTO request_logs (
                        request_id, api_key_id, user_id, path, model, status_code, success,
                        input_tokens, output_tokens, credits, latency_ms, created_at
                    )
                    VALUES (
                        :requestId, :keyId, :userId, '/v1/chat/completions', :model, 200, TRUE,
                        30, 15, 0.30, 30, :createdAt
                    )
                    """)
                .bind("requestId", "segments-request-" + suffix)
                .bind("keyId", keyId)
                .bind("userId", userId)
                .bind("model", model)
                .bind("createdAt", watermark.plus(1, ChronoUnit.HOURS))
                .fetch()
                .rowsUpdated()
                .block();

            assertUsage(statsAnalyticsService.usageSummaryForUser(userId).block(), model, 4L, 3L, 1L, 60L, 30L, "0.60");
        } finally {
            cleanupSegmentedUsage(userId);
        }
    }

    @Test
    void aggregatedUsageKeepsUserAttributionAfterApiKeyDeletion() {
        String suffix = randomSuffix();
        String userId = "stats-user-" + suffix;
        String keyId = "stats-key-" + suffix;
        String model = "stats-model-" + suffix;
        cleanupSegmentedUsage(userId);

        try {
            insertUser(
                userId,
                "Stats" + suffix,
                "stats-" + suffix + "@example.com",
                "stats-" + suffix
            ).block();
            insertApiKey(keyId, userId);
            databaseClient.sql("""
                    INSERT INTO request_logs (
                        request_id, api_key_id, user_id, path, model, status_code, success,
                        input_tokens, output_tokens, credits, latency_ms, created_at
                    )
                    VALUES (
                        :requestId, :keyId, :userId, '/v1/chat/completions', :model, 200, TRUE,
                        10, 5, 0.25, 20,
                        pg_catalog.date_trunc('hour', CURRENT_TIMESTAMP, 'UTC') - INTERVAL '30 minutes'
                    )
                    """)
                .bind("requestId", "stats-request-" + suffix)
                .bind("keyId", keyId)
                .bind("userId", userId)
                .bind("model", model)
                .fetch()
                .rowsUpdated()
                .block();

            callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates()");
            databaseClient.sql("DELETE FROM api_keys WHERE key_id = :keyId")
                .bind("keyId", keyId)
                .fetch()
                .rowsUpdated()
                .block();

            StatsDtos.UsageSummaryResponse usage = statsAnalyticsService.usageSummaryForUser(userId).block();
            assertThat(usage).isNotNull();
            assertThat(usage.totalRequests()).isEqualTo(1L);
            assertThat(usage.totalInputTokens()).isEqualTo(10L);
            assertThat(usage.totalOutputTokens()).isEqualTo(5L);
            assertThat(usage.totalCredits()).isEqualByComparingTo("0.25");
            assertThat(usage.models())
                .extracting(StatsDtos.ModelUsageResponse::model)
                .containsExactly(model);
        } finally {
            cleanupSegmentedUsage(userId);
            executeAsDatabaseOwner(
                "DELETE FROM starry_api.api_keys WHERE key_id = '%s'".formatted(keyId),
                "DELETE FROM starry_api.admin_users WHERE user_id = '%s'".formatted(userId)
            );
        }
    }

    @Test
    void refreshesUsageAggregatesOncePerBoundaryAndRebuildsLookback() {
        executeAsDatabaseOwner(
            "DELETE FROM starry_api.request_logs",
            "DELETE FROM starry_api.request_usage_hourly",
            "DELETE FROM starry_api.request_usage_daily",
            "DELETE FROM starry_api.aggregation_watermarks"
        );

        String suffix = randomSuffix();
        String model = "aggregate-model-" + suffix;
        Instant aggregationBoundary = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS);
        insertRequestLog("aggregate-old-" + suffix, model, true, 10, 5, "0.10", aggregationBoundary.minus(2, ChronoUnit.HOURS).plusSeconds(600));
        insertRequestLog("aggregate-recent-" + suffix, model, false, 20, 10, "0.20", aggregationBoundary.minus(1, ChronoUnit.HOURS).plusSeconds(600));

        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates(168, 6)");
        Instant firstAggregateUpdate = hourlyAggregateUpdatedAt(model);
        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates()");

        Instant watermark = requestUsageWatermark();
        insertRequestLog("aggregate-live-" + suffix, model, true, 30, 15, "0.30", Instant.now());
        assertThat(hourlyRequestCount(model)).isEqualTo(2L);
        assertThat(firstAggregateUpdate).isNotNull();
        assertThat(hourlyAggregateUpdatedAt(model)).isEqualTo(firstAggregateUpdate);
        assertThat(watermark).isNotNull();
        assertThat(watermark).isEqualTo(watermark.truncatedTo(ChronoUnit.HOURS));
        assertUsage(statsAnalyticsService.usageSummary().block(), model, 3L, 2L, 1L, 60L, 30L, "0.60");

        insertRequestLog("aggregate-late-" + suffix, model, true, 40, 20, "0.40", watermark.minus(1, ChronoUnit.HOURS).plusSeconds(1_200));
        executeAsDatabaseOwner("""
            UPDATE starry_api.aggregation_watermarks
            SET last_processed_at = last_processed_at - INTERVAL '1 hour'
            WHERE job_name = 'request_usage'
            """);
        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates()");
        Instant lateAggregateUpdate = hourlyAggregateUpdatedAt(model);
        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates()");

        assertThat(hourlyRequestCount(model)).isEqualTo(3L);
        assertThat(hourlyAggregateUpdatedAt(model)).isEqualTo(lateAggregateUpdate);
        assertThat(requestUsageWatermark()).isEqualTo(watermark);
        assertUsage(statsAnalyticsService.usageSummary().block(), model, 4L, 3L, 1L, 100L, 50L, "1.00");
    }

    @Test
    void advancesAggregationBacklogInBoundedHourlyBatches() {
        executeAsDatabaseOwner(
            "DELETE FROM starry_api.request_logs",
            "DELETE FROM starry_api.request_usage_hourly",
            "DELETE FROM starry_api.request_usage_daily",
            "DELETE FROM starry_api.aggregation_watermarks"
        );

        String suffix = randomSuffix();
        String model = "aggregate-backlog-" + suffix;
        Instant targetBoundary = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS);
        insertRequestLog(
            "aggregate-backlog-old-" + suffix,
            model,
            true,
            10,
            5,
            "0.10",
            targetBoundary.minus(3, ChronoUnit.HOURS).plusSeconds(600)
        );
        insertRequestLog(
            "aggregate-backlog-recent-" + suffix,
            model,
            true,
            20,
            10,
            "0.20",
            targetBoundary.minus(1, ChronoUnit.HOURS).plusSeconds(600)
        );

        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates(1, 6)");
        assertThat(requestUsageWatermark()).isEqualTo(targetBoundary.minus(2, ChronoUnit.HOURS));
        assertThat(hourlyRequestCount(model)).isEqualTo(1L);

        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates(1, 6)");
        assertThat(requestUsageWatermark()).isEqualTo(targetBoundary.minus(1, ChronoUnit.HOURS));
        assertThat(hourlyRequestCount(model)).isEqualTo(1L);

        callPartitionFunction("SELECT starry_api.refresh_request_usage_aggregates(1, 6)");
        assertThat(requestUsageWatermark()).isEqualTo(targetBoundary);
        assertThat(hourlyRequestCount(model)).isEqualTo(2L);
    }

    @Test
    void creditsPaymentExactlyOnceUnderOneHundredConcurrentNotifications() {
        String suffix = randomSuffix();
        String userId = "payment-user-" + suffix;
        String orderId = "payment-order-" + suffix;
        String outTradeNo = "payment-trade-" + suffix;
        insertUser(userId, "Payment" + suffix, "payment-" + suffix + "@example.com", "pay-" + suffix).block();
        updateUserBalance(userId, "10.00");
        insertPaymentOrder(orderId, outTradeNo, userId, "5.00", "PENDING", null);

        PaymentSettingsService settingsService = mock(PaymentSettingsService.class);
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);
        PaymentConfig config = paymentConfig(false);
        Notification notification = new Notification(
            outTradeNo,
            "provider-" + suffix,
            new BigDecimal("5.00"),
            true,
            "paid"
        );
        when(settingsService.config()).thenReturn(Mono.just(config));
        when(easyPayClient.verifyNotification(any(PaymentConfig.class), anyMap(), anyString()))
            .thenReturn(notification);
        when(inviteRebateService.processPending(orderId)).thenReturn(Mono.just(1L));
        PaymentService paymentService = new PaymentService(
            paymentOrderRepository,
            adminUserRepository,
            entityTemplate,
            settingsService,
            easyPayClient,
            inviteRebateService
        );

        List<String> responses = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> paymentService.handleEasyPayNotification(Map.of("attempt", Integer.toString(index)), "paid"), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        assertThat(responses).hasSize(CONCURRENT_REQUESTS).containsOnly("success");
        assertThat(userBalance(userId)).isEqualByComparingTo("15.00");
        assertThat(ledgerState(userId, "PAYMENT_CREDIT"))
            .isEqualTo(new LedgerState(1L, new BigDecimal("5.000000")));
        assertThat(idempotencyLedgerCount("payment-order:" + orderId)).isEqualTo(1L);

        PaymentState state = databaseClient.sql("""
                SELECT status, credited_at IS NOT NULL AS credited
                FROM payment_orders
                WHERE order_id = :orderId
                """)
            .bind("orderId", orderId)
            .map((row, metadata) -> new PaymentState(
                row.get("status", String.class),
                Boolean.TRUE.equals(row.get("credited", Boolean.class))
            ))
            .one()
            .block();
        assertThat(state).isEqualTo(new PaymentState("COMPLETED", true));
    }

    @Test
    void reconcilesRebateAfterCrashUsingThePaymentTimeConfigurationSnapshot() {
        String suffix = randomSuffix();
        String inviterUserId = "recovery-inviter-" + suffix;
        String invitedUserId = "recovery-invited-" + suffix;
        String affiliateCode = "recovery-affiliate-" + suffix;
        String orderId = "recovery-order-" + suffix;
        String outTradeNo = "recovery-trade-" + suffix;
        insertUser(
            inviterUserId,
            "RecoveryInviter" + suffix,
            "recovery-inviter-" + suffix + "@example.com",
            affiliateCode
        ).block();
        insertUser(
            invitedUserId,
            "RecoveryInvited" + suffix,
            "recovery-invited-" + suffix + "@example.com",
            "recovery-code-" + suffix
        ).block();
        updateUserBalance(inviterUserId, "10.00");
        databaseClient.sql("""
                UPDATE admin_users
                SET invited_by_user_id = :inviterUserId,
                    invite_source_code = :affiliateCode
                WHERE user_id = :invitedUserId
                """)
            .bind("inviterUserId", inviterUserId)
            .bind("affiliateCode", affiliateCode)
            .bind("invitedUserId", invitedUserId)
            .fetch()
            .rowsUpdated()
            .block();
        insertPaymentOrder(orderId, outTradeNo, invitedUserId, "5.00", "PENDING", null);

        PaymentConfig paymentTimeConfig = paymentConfig(true);
        PaymentSettingsService paymentTimeSettings = mock(PaymentSettingsService.class);
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        InviteRebateService crashingRebateService = mock(InviteRebateService.class);
        Notification notification = new Notification(
            outTradeNo,
            "recovery-provider-" + suffix,
            new BigDecimal("5.00"),
            true,
            "paid"
        );
        when(paymentTimeSettings.config()).thenReturn(Mono.just(paymentTimeConfig));
        when(easyPayClient.verifyNotification(any(PaymentConfig.class), anyMap(), anyString()))
            .thenReturn(notification);
        when(crashingRebateService.processPending(orderId))
            .thenReturn(Mono.error(new IllegalStateException("simulated process exit")));
        PaymentService crashingPaymentService = new PaymentService(
            paymentOrderRepository,
            adminUserRepository,
            entityTemplate,
            paymentTimeSettings,
            easyPayClient,
            crashingRebateService
        );

        assertThatThrownBy(() -> crashingPaymentService
            .handleEasyPayNotification(Map.of("attempt", "initial"), "paid")
            .block())
            .hasMessageContaining("simulated process exit");

        PaymentRebateState pending = paymentRebateState(orderId);
        assertThat(userBalance(invitedUserId)).isEqualByComparingTo("5.00");
        assertThat(pending).isEqualTo(new PaymentRebateState(
            true,
            true,
            new BigDecimal("0.100000"),
            10,
            null,
            null,
            false,
            "recovery-provider-" + suffix
        ));

        PaymentSettingsService replaySettings = mock(PaymentSettingsService.class);
        when(replaySettings.config()).thenReturn(Mono.just(paymentConfig(
            false,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(60)
        )));
        PaymentService replayService = new PaymentService(
            paymentOrderRepository,
            adminUserRepository,
            entityTemplate,
            replaySettings,
            easyPayClient,
            crashingRebateService
        );
        assertThatThrownBy(() -> replayService
            .handleEasyPayNotification(Map.of("attempt", "replay"), "paid")
            .block())
            .hasMessageContaining("simulated process exit");
        assertThat(paymentRebateState(orderId)).isEqualTo(pending);

        PaymentSettingsService changedSettings = mock(PaymentSettingsService.class);
        InviteRebateService durableRebateService = new InviteRebateService(
            entityTemplate,
            inviteRebatePersistenceService
        );
        PaymentService recoveryService = new PaymentService(
            paymentOrderRepository,
            adminUserRepository,
            entityTemplate,
            changedSettings,
            mock(EasyPayClient.class),
            durableRebateService
        );

        recoveryService.scheduledReconcileUncreditedOrders();
        Boolean processed = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
            .concatMap(ignored -> databaseClient.sql("""
                    SELECT rebate_processed_at IS NOT NULL AS processed
                    FROM payment_orders
                    WHERE order_id = :orderId
                    """)
                .bind("orderId", orderId)
                .map((row, metadata) -> row.get("processed", Boolean.class))
                .one())
            .filter(Boolean.TRUE::equals)
            .next()
            .timeout(Duration.ofSeconds(5))
            .block();

        assertThat(processed).isTrue();
        verify(changedSettings, never()).config();
        assertThat(userBalance(inviterUserId)).isEqualByComparingTo("10.50");
        assertThat(ledgerState(inviterUserId, "INVITE_REBATE"))
            .isEqualTo(new LedgerState(1L, new BigDecimal("0.500000")));
        assertThat(paymentRebateState(orderId)).isEqualTo(new PaymentRebateState(
            true,
            true,
            new BigDecimal("0.100000"),
            10,
            null,
            null,
            true,
            "recovery-provider-" + suffix
        ));
    }

    @Test
    void enforcesNonBlankGloballyUniqueProviderTradeNumbers() {
        String suffix = randomSuffix();
        String firstUserId = "trade-unique-user-a-" + suffix;
        String secondUserId = "trade-unique-user-b-" + suffix;
        String providerTradeNo = "provider-unique-" + suffix;
        insertUser(
            firstUserId,
            "TradeUniqueA" + suffix,
            "trade-unique-a-" + suffix + "@example.com",
            "trade-unique-a-" + suffix
        ).block();
        insertUser(
            secondUserId,
            "TradeUniqueB" + suffix,
            "trade-unique-b-" + suffix + "@example.com",
            "trade-unique-b-" + suffix
        ).block();
        insertPaymentOrder(
            "trade-unique-order-a-" + suffix,
            "trade-unique-out-a-" + suffix,
            firstUserId,
            "5.00",
            "PENDING",
            null
        );
        insertPaymentOrder(
            "trade-unique-order-b-" + suffix,
            "trade-unique-out-b-" + suffix,
            secondUserId,
            "5.00",
            "PENDING",
            null
        );
        databaseClient.sql("""
                UPDATE payment_orders
                SET payment_trade_no = :paymentTradeNo
                WHERE order_id = :orderId
                """)
            .bind("paymentTradeNo", providerTradeNo)
            .bind("orderId", "trade-unique-order-a-" + suffix)
            .fetch()
            .rowsUpdated()
            .block();

        assertUniqueViolation(databaseClient.sql("""
                UPDATE payment_orders
                SET payment_trade_no = :paymentTradeNo
                WHERE order_id = :orderId
                """)
            .bind("paymentTradeNo", providerTradeNo)
            .bind("orderId", "trade-unique-order-b-" + suffix)
            .fetch()
            .rowsUpdated());
        assertThatThrownBy(() -> databaseClient.sql("""
                UPDATE payment_orders
                SET payment_trade_no = ''
                WHERE order_id = :orderId
                """)
            .bind("orderId", "trade-unique-order-b-" + suffix)
            .fetch()
            .rowsUpdated()
            .block()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void marksDisabledOrUninvitedRebateDecisionProcessedWithoutCreatingWorkForever() {
        String suffix = randomSuffix();
        String userId = "rebate-ineligible-user-" + suffix;
        String orderId = "rebate-ineligible-order-" + suffix;
        String outTradeNo = "rebate-ineligible-trade-" + suffix;
        insertUser(
            userId,
            "RebateIneligible" + suffix,
            "rebate-ineligible-" + suffix + "@example.com",
            "ineligible-code-" + suffix
        ).block();
        insertPaymentOrder(orderId, outTradeNo, userId, "5.00", "PENDING", null);
        PaymentSettingsService settingsService = mock(PaymentSettingsService.class);
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(settingsService.config()).thenReturn(Mono.just(paymentConfig(false)));
        when(easyPayClient.verifyNotification(any(PaymentConfig.class), anyMap(), anyString()))
            .thenReturn(new Notification(
                outTradeNo,
                "rebate-ineligible-provider-" + suffix,
                new BigDecimal("5.00"),
                true,
                "paid"
            ));
        PaymentService paymentService = new PaymentService(
            paymentOrderRepository,
            adminUserRepository,
            entityTemplate,
            settingsService,
            easyPayClient,
            new InviteRebateService(entityTemplate, inviteRebatePersistenceService)
        );

        assertThat(paymentService.handleEasyPayNotification(Map.of("attempt", "one"), "paid").block())
            .isEqualTo("success");

        assertThat(paymentRebateState(orderId).processed()).isTrue();
        assertThat(countRows("invite_rebate_records", "payment_order_id", orderId)).isZero();
    }

    @Test
    void redeemsCodeExactlyOnceUnderOneHundredConcurrentAttempts() {
        String suffix = randomSuffix();
        String userId = "redeem-user-" + suffix;
        String codeId = "redeem-code-id-" + suffix;
        String code = "REDEEM-" + suffix.toUpperCase(java.util.Locale.ROOT);
        insertUser(userId, "Redeem" + suffix, "redeem-" + suffix + "@example.com", "redeem-" + suffix).block();
        updateUserBalance(userId, "10.00");
        insertRedemptionCode(codeId, code, "5.00");
        RedemptionCodeService redemptionCodeService = new RedemptionCodeService(redemptionCodeRepository, entityTemplate);
        AdminPrincipal principal = new AdminPrincipal(userId, "Redeem" + suffix, "USER", Instant.now().plusSeconds(300), false);

        List<Boolean> outcomes = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> redemptionCodeService.redeem(principal, new RedeemCodeRequest(code))
                .thenReturn(true)
                .onErrorReturn(false), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        assertThat(outcomes).hasSize(CONCURRENT_REQUESTS);
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
        assertThat(userBalance(userId)).isEqualByComparingTo("15.00");
        assertThat(ledgerState(userId, "REDEMPTION_CREDIT"))
            .isEqualTo(new LedgerState(1L, new BigDecimal("5.000000")));
        assertThat(idempotencyLedgerCount("redemption-code:" + codeId)).isEqualTo(1L);

        RedemptionState state = databaseClient.sql("""
                SELECT enabled, used, used_by_user_id
                FROM redemption_codes
                WHERE code_id = :codeId
                """)
            .bind("codeId", codeId)
            .map((row, metadata) -> new RedemptionState(
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                Boolean.TRUE.equals(row.get("used", Boolean.class)),
                row.get("used_by_user_id", String.class)
            ))
            .one()
            .block();
        assertThat(state).isEqualTo(new RedemptionState(false, true, userId));
    }

    @Test
    void rollsBackRegisteredUserWhenInviteCodeConsumptionFails() {
        Map<String, String> settingsSnapshot = systemSettingsSnapshot();
        String suffix = randomSuffix();
        String userId = "registration-rollback-" + suffix;
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId(userId);
        user.setAffiliateCode("rollback-" + suffix);
        user.setUsername("RegistrationRollback" + suffix);
        user.setEmail("registration-rollback-" + suffix + "@example.com");
        user.setDisplayName("Registration rollback");
        user.setRole("USER");
        user.setBalance(BigDecimal.ZERO);
        user.setPasswordHash("a".repeat(64));
        user.setPasswordSalt("b".repeat(32));
        user.setEnabled(true);
        user.setFirstLogin(false);

        try {
            systemSettingRepository.upsertSettings(
                new String[]{
                    "registration_enabled",
                    "email_verify_enabled",
                    "invite_code_enabled",
                    "registration_email_suffix_whitelist",
                    "turnstile_enabled"
                },
                new String[]{"true", "false", "true", "[]", "false"}
            ).block();
            RegistrationSettingsService.RegistrationPolicy verifiedPolicy = registrationSettingsService
                .registrationPolicy()
                .block();

            assertThatThrownBy(() -> registrationPersistenceService.register(
                user,
                "INV-NOT-VALID-" + suffix,
                "",
                verifiedPolicy,
                ""
            ).block()).hasMessageContaining("Invite code is invalid");

            assertThat(adminUserRepository.findByUserId(userId).block()).isNull();
        } finally {
            restoreSystemSettings(settingsSnapshot);
        }
    }

    @Test
    void consumesRegistrationEmailTokenExactlyOnceUnderConcurrentAttempts() {
        String suffix = randomSuffix();
        String email = "email-token-" + suffix + "@example.com";
        String tokenHash = Hashing.sha256("registration-token-" + suffix);
        Instant now = Instant.now();

        try {
            RegistrationEmailTokenRepository.Reservation reservation = registrationEmailTokenRepository.reserve(
                email,
                tokenHash,
                now.plusSeconds(600),
                now.plusSeconds(60)
            ).block();
            assertThat(reservation).isNotNull();
            assertThat(reservation.reservedTokenHash()).isEqualTo(tokenHash);
            assertThat(reservation.previousTokenHash()).isNull();

            List<Boolean> outcomes = Flux.range(0, CONCURRENT_REQUESTS)
                .flatMap(index -> registrationEmailTokenRepository.consume(email, tokenHash), CONCURRENT_REQUESTS)
                .collectList()
                .block();

            assertThat(outcomes).hasSize(CONCURRENT_REQUESTS);
            assertThat(outcomes.stream().filter(Boolean.TRUE::equals).count()).isEqualTo(1L);
        } finally {
            registrationEmailTokenRepository.release(email, tokenHash).block();
        }
    }

    @Test
    void restoresPreviousRegistrationTokenWhenResendDeliveryFails() {
        String suffix = randomSuffix();
        String email = "email-token-resend-" + suffix + "@example.com";
        String previousTokenHash = Hashing.sha256("previous-registration-token-" + suffix);
        String reservedTokenHash = Hashing.sha256("reserved-registration-token-" + suffix);
        Instant now = Instant.now();

        try {
            registrationEmailTokenRepository.reserve(
                email,
                previousTokenHash,
                now.plusSeconds(600),
                now.plusSeconds(60)
            ).block();
            databaseClient.sql("""
                    UPDATE registration_email_tokens
                    SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        next_send_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE email = :email
                    """)
                .bind("email", email)
                .fetch()
                .rowsUpdated()
                .block();

            RegistrationEmailTokenRepository.Reservation reservation = registrationEmailTokenRepository.reserve(
                email,
                reservedTokenHash,
                now.plusSeconds(900),
                now.plusSeconds(120)
            ).block();

            assertThat(reservation).isNotNull();
            assertThat(reservation.previousTokenHash()).isEqualTo(previousTokenHash);
            assertThat(registrationEmailTokenRepository.restoreIfUsable(
                reservation.email(),
                reservation.reservedTokenHash(),
                reservation.previousTokenHash(),
                reservation.previousExpiresAt(),
                reservation.previousNextSendAt()
            ).block()).isEqualTo(1);
            assertThat(registrationEmailTokenRepository.consume(email, previousTokenHash).block()).isTrue();
            assertThat(registrationEmailTokenRepository.consume(email, reservedTokenHash).block()).isFalse();
        } finally {
            registrationEmailTokenRepository.release(email, previousTokenHash).block();
            registrationEmailTokenRepository.release(email, reservedTokenHash).block();
        }
    }

    @Test
    void doesNotRestoreExpiredRegistrationTokenWhenResendDeliveryFails() {
        String suffix = randomSuffix();
        String email = "email-token-expired-resend-" + suffix + "@example.com";
        String previousTokenHash = Hashing.sha256("expired-registration-token-" + suffix);
        String reservedTokenHash = Hashing.sha256("reserved-registration-token-" + suffix);
        Instant now = Instant.now();

        try {
            registrationEmailTokenRepository.reserve(
                email,
                previousTokenHash,
                now.plusSeconds(600),
                now.plusSeconds(60)
            ).block();
            databaseClient.sql("""
                    UPDATE registration_email_tokens
                    SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second',
                        next_send_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds'
                    WHERE email = :email
                    """)
                .bind("email", email)
                .fetch()
                .rowsUpdated()
                .block();

            RegistrationEmailTokenRepository.Reservation reservation = registrationEmailTokenRepository.reserve(
                email,
                reservedTokenHash,
                now.plusSeconds(900),
                now.plusSeconds(120)
            ).block();

            assertThat(reservation).isNotNull();
            assertThat(registrationEmailTokenRepository.restoreIfUsable(
                reservation.email(),
                reservation.reservedTokenHash(),
                reservation.previousTokenHash(),
                reservation.previousExpiresAt(),
                reservation.previousNextSendAt()
            ).block()).isZero();
            assertThat(registrationEmailTokenRepository.release(email, reservedTokenHash).block()).isEqualTo(1);
            assertThat(registrationEmailTokenRepository.consume(email, previousTokenHash).block()).isFalse();
        } finally {
            registrationEmailTokenRepository.release(email, previousTokenHash).block();
            registrationEmailTokenRepository.release(email, reservedTokenHash).block();
        }
    }

    @Test
    void staleFailedResendCannotOverwriteOrDeleteNewerRegistrationToken() {
        String suffix = randomSuffix();
        String email = "email-token-concurrent-resend-" + suffix + "@example.com";
        String previousTokenHash = Hashing.sha256("previous-registration-token-" + suffix);
        String failedTokenHash = Hashing.sha256("failed-registration-token-" + suffix);
        String newerTokenHash = Hashing.sha256("newer-registration-token-" + suffix);
        Instant now = Instant.now();

        try {
            registrationEmailTokenRepository.reserve(
                email,
                previousTokenHash,
                now.plusSeconds(600),
                now.plusSeconds(60)
            ).block();
            databaseClient.sql("""
                    UPDATE registration_email_tokens
                    SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        next_send_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE email = :email
                    """)
                .bind("email", email)
                .fetch()
                .rowsUpdated()
                .block();

            RegistrationEmailTokenRepository.Reservation failedReservation = registrationEmailTokenRepository.reserve(
                email,
                failedTokenHash,
                now.plusSeconds(900),
                now.plusSeconds(120)
            ).block();
            databaseClient.sql("""
                    UPDATE registration_email_tokens
                    SET next_send_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE email = :email
                    """)
                .bind("email", email)
                .fetch()
                .rowsUpdated()
                .block();
            RegistrationEmailTokenRepository.Reservation newerReservation = registrationEmailTokenRepository.reserve(
                email,
                newerTokenHash,
                now.plusSeconds(900),
                now.plusSeconds(120)
            ).block();

            assertThat(failedReservation).isNotNull();
            assertThat(newerReservation).isNotNull();
            assertThat(registrationEmailTokenRepository.restoreIfUsable(
                failedReservation.email(),
                failedReservation.reservedTokenHash(),
                failedReservation.previousTokenHash(),
                failedReservation.previousExpiresAt(),
                failedReservation.previousNextSendAt()
            ).block()).isZero();
            assertThat(registrationEmailTokenRepository.release(email, failedTokenHash).block()).isZero();
            assertThat(registrationEmailTokenRepository.consume(email, newerTokenHash).block()).isTrue();
        } finally {
            registrationEmailTokenRepository.release(email, previousTokenHash).block();
            registrationEmailTokenRepository.release(email, failedTokenHash).block();
            registrationEmailTokenRepository.release(email, newerTokenHash).block();
        }
    }

    @Test
    void rollsBackEmailTokenConsumptionWhenAffiliateValidationFails() {
        Map<String, String> settingsSnapshot = systemSettingsSnapshot();
        String suffix = randomSuffix();
        String userId = "registration-token-rollback-" + suffix;
        String email = "registration-token-rollback-" + suffix + "@example.com";
        String tokenHash = Hashing.sha256("registration-token-rollback-" + suffix);
        Instant now = Instant.now();
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId(userId);
        user.setAffiliateCode("token-rollback-" + suffix);
        user.setUsername("RegistrationTokenRollback" + suffix);
        user.setEmail(email);
        user.setDisplayName("Registration token rollback");
        user.setRole("USER");
        user.setBalance(BigDecimal.ZERO);
        user.setPasswordHash("a".repeat(64));
        user.setPasswordSalt("b".repeat(32));
        user.setEnabled(true);
        user.setFirstLogin(false);

        try {
            systemSettingRepository.upsertSettings(
                new String[]{
                    "registration_enabled",
                    "email_verify_enabled",
                    "invite_code_enabled",
                    "registration_email_suffix_whitelist",
                    "turnstile_enabled",
                    "turnstile_site_key",
                    "turnstile_secret_key"
                },
                new String[]{"true", "true", "false", "[]", "false", "", ""}
            ).block();
            registrationEmailTokenRepository.reserve(
                email,
                tokenHash,
                now.plusSeconds(600),
                now.plusSeconds(60)
            ).block();
            RegistrationSettingsService.RegistrationPolicy verifiedPolicy = registrationSettingsService
                .registrationPolicy()
                .block();

            assertThatThrownBy(() -> registrationPersistenceService.register(
                user,
                "",
                "missing-affiliate-" + suffix,
                verifiedPolicy,
                tokenHash
            ).block()).hasMessageContaining("Affiliate code is invalid");

            assertThat(adminUserRepository.findByUserId(userId).block()).isNull();
            assertThat(registrationEmailTokenRepository.consume(email, tokenHash).block()).isTrue();
        } finally {
            registrationEmailTokenRepository.release(email, tokenHash).block();
            restoreSystemSettings(settingsSnapshot);
        }
    }

    @Test
    void creditsInviteRebateExactlyOnceUnderOneHundredConcurrentAttempts() {
        String suffix = randomSuffix();
        String inviterUserId = "inviter-" + suffix;
        String invitedUserId = "invited-" + suffix;
        String affiliateCode = "affiliate-" + suffix;
        String orderId = "rebate-order-" + suffix;
        insertUser(inviterUserId, "Inviter" + suffix, "inviter-" + suffix + "@example.com", affiliateCode).block();
        insertUser(invitedUserId, "Invited" + suffix, "invited-" + suffix + "@example.com", "invited-code-" + suffix).block();
        updateUserBalance(inviterUserId, "10.00");
        databaseClient.sql("""
                UPDATE admin_users
                SET invited_by_user_id = :inviterUserId,
                    invite_source_code = :affiliateCode
                WHERE user_id = :invitedUserId
                """)
            .bind("inviterUserId", inviterUserId)
            .bind("affiliateCode", affiliateCode)
            .bind("invitedUserId", invitedUserId)
            .fetch()
            .rowsUpdated()
            .block();
        insertPaymentOrder(
            orderId,
            "rebate-trade-" + suffix,
            invitedUserId,
            "5.00",
            "COMPLETED",
            Instant.now()
        );
        PaymentOrderEntity order = paymentOrderRepository.findByOrderId(orderId).block();
        InviteRebateService inviteRebateService = new InviteRebateService(
            entityTemplate,
            inviteRebatePersistenceService
        );

        List<Integer> completions = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> inviteRebateService.applyIfEligible(order).thenReturn(index), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        assertThat(completions).hasSize(CONCURRENT_REQUESTS);
        assertThat(userBalance(inviterUserId)).isEqualByComparingTo("10.50");
        assertThat(ledgerState(inviterUserId, "INVITE_REBATE"))
            .isEqualTo(new LedgerState(1L, new BigDecimal("0.500000")));
        assertThat(idempotencyLedgerCount("invite-rebate:" + orderId)).isEqualTo(1L);
        assertThat(countRows("invite_rebate_records", "payment_order_id", orderId)).isEqualTo(1L);
    }

    @Test
    void usesCreditedAtWhenPaidAndCompletedTimesAreUnavailable() {
        String suffix = randomSuffix();
        String inviterUserId = "fallback-inviter-" + suffix;
        String invitedUserId = "fallback-invited-" + suffix;
        String affiliateCode = "fallback-affiliate-" + suffix;
        String orderId = "fallback-order-" + suffix;
        insertUser(
            inviterUserId,
            "FallbackInviter" + suffix,
            "fallback-inviter-" + suffix + "@example.com",
            affiliateCode
        ).block();
        insertUser(
            invitedUserId,
            "FallbackInvited" + suffix,
            "fallback-invited-" + suffix + "@example.com",
            "fallback-code-" + suffix
        ).block();
        databaseClient.sql("""
                UPDATE admin_users
                SET invited_by_user_id = :inviterUserId,
                    invite_source_code = :affiliateCode
                WHERE user_id = :invitedUserId
                """)
            .bind("inviterUserId", inviterUserId)
            .bind("affiliateCode", affiliateCode)
            .bind("invitedUserId", invitedUserId)
            .fetch()
            .rowsUpdated()
            .block();
        Instant creditedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        insertPaymentOrder(
            orderId,
            "fallback-trade-" + suffix,
            invitedUserId,
            "5.00",
            "COMPLETED",
            creditedAt
        );
        databaseClient.sql("""
                UPDATE payment_orders
                SET paid_at = NULL,
                    completed_at = NULL
                WHERE order_id = :orderId
                """)
            .bind("orderId", orderId)
            .fetch()
            .rowsUpdated()
            .block();

        inviteRebatePersistenceService.process(orderId).block();

        assertThat(inviteRebateOrderState(orderId).paymentEffectiveAt()).isEqualTo(creditedAt);
    }

    @Test
    void creditsOutOfOrderDifferentPaymentsUnderConcurrentReplaysUsingPaymentTime() {
        String suffix = randomSuffix();
        String inviterUserId = "out-of-order-inviter-" + suffix;
        String invitedUserId = "out-of-order-invited-" + suffix;
        String affiliateCode = "ooo-affiliate-" + suffix;
        String laterOrderId = "out-of-order-later-" + suffix;
        String earlierOrderId = "out-of-order-earlier-" + suffix;
        insertUser(inviterUserId, "OutOfOrderInviter" + suffix, "ooo-inviter-" + suffix + "@example.com", affiliateCode).block();
        insertUser(invitedUserId, "OutOfOrderInvited" + suffix, "ooo-invited-" + suffix + "@example.com", "ooo-code-" + suffix).block();
        updateUserBalance(inviterUserId, "10.00");
        databaseClient.sql("""
                UPDATE admin_users
                SET invited_by_user_id = :inviterUserId,
                    invite_source_code = :affiliateCode
                WHERE user_id = :invitedUserId
                """)
            .bind("inviterUserId", inviterUserId)
            .bind("affiliateCode", affiliateCode)
            .bind("invitedUserId", invitedUserId)
            .fetch()
            .rowsUpdated()
            .block();

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant campaignStartAt = now.minusSeconds(3_600);
        Instant campaignEndAt = now.minusSeconds(300);
        Instant laterPaidAt = now.minusSeconds(900);
        Instant earlierCompletedAt = now.minusSeconds(1_800);
        insertPaymentOrder(
            laterOrderId,
            "out-of-order-later-trade-" + suffix,
            invitedUserId,
            "5.00",
            "COMPLETED",
            laterPaidAt,
            now,
            now,
            campaignStartAt,
            campaignEndAt
        );
        updatePaymentRebateSnapshot(laterOrderId, "0.20", 2);

        InviteRebateService inviteRebateService = new InviteRebateService(
            entityTemplate,
            inviteRebatePersistenceService
        );
        PaymentOrderEntity laterOrder = paymentOrderRepository.findByOrderId(laterOrderId).block();

        inviteRebateService.applyIfEligible(laterOrder).block();

        insertPaymentOrder(
            earlierOrderId,
            "out-of-order-earlier-trade-" + suffix,
            invitedUserId,
            "5.00",
            "COMPLETED",
            null,
            earlierCompletedAt,
            now.minusMillis(1),
            campaignStartAt,
            campaignEndAt
        );
        updatePaymentRebateSnapshot(earlierOrderId, "0.10", 1);
        PaymentOrderEntity earlierOrder = paymentOrderRepository.findByOrderId(earlierOrderId).block();

        List<Integer> completions = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> inviteRebateService.applyIfEligible(
                    index % 2 == 0 ? earlierOrder : laterOrder
                )
                .thenReturn(index), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        InviteRebateAuditState audit = inviteRebateAuditState(invitedUserId);
        InviteRebateOrderState earlier = inviteRebateOrderState(earlierOrderId);
        InviteRebateOrderState later = inviteRebateOrderState(laterOrderId);
        assertThat(completions).hasSize(CONCURRENT_REQUESTS);
        assertThat(userBalance(inviterUserId)).isEqualByComparingTo("11.50");
        assertThat(ledgerState(inviterUserId, "INVITE_REBATE"))
            .isEqualTo(new LedgerState(2L, new BigDecimal("1.500000")));
        assertThat(audit).isEqualTo(new InviteRebateAuditState(
            2L,
            2L,
            1,
            campaignStartAt,
            campaignEndAt,
            2L
        ));
        assertThat(earlier.paymentOrderNo()).isEqualTo(1);
        assertThat(earlier.rebateOrderNo()).isEqualTo(2);
        assertThat(earlier.rebateMaxOrders()).isEqualTo(1);
        assertThat(earlier.rebateRate()).isEqualByComparingTo("0.10");
        assertThat(earlier.rebateAmount()).isEqualByComparingTo("0.50");
        assertThat(earlier.paymentEffectiveAt()).isEqualTo(earlierCompletedAt);
        assertThat(later.paymentOrderNo()).isEqualTo(2);
        assertThat(later.rebateOrderNo()).isEqualTo(1);
        assertThat(later.rebateMaxOrders()).isEqualTo(2);
        assertThat(later.rebateRate()).isEqualByComparingTo("0.20");
        assertThat(later.rebateAmount()).isEqualByComparingTo("1.00");
        assertThat(later.paymentEffectiveAt()).isEqualTo(laterPaidAt);
    }

    @Test
    void apiKeyManagementUpdatesDoNotLoseConcurrentUsageCounters() {
        String suffix = randomSuffix();
        String userId = "managed-key-user-" + suffix;
        String keyId = "managed-key-" + suffix;
        insertUser(
            userId,
            "ManagedKey" + suffix,
            "managed-key-" + suffix + "@example.com",
            "managed-key-" + suffix
        ).block();
        insertApiKey(keyId, userId);
        databaseClient.sql("UPDATE api_keys SET model_provider=\'OpenAI\' WHERE key_id=:id")
            .bind("id",keyId).fetch().rowsUpdated().block();
        ApiKeyPrincipal keyPrincipal = apiKeyPrincipal(keyId, userId, "0");
        AdminPrincipal admin = new AdminPrincipal(
            "integration-admin",
            "IntegrationAdmin",
            "ADMIN",
            Instant.now().plusSeconds(300),
            false
        );

        Mono<Void> usageWrites = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> reserveAndAccountUsage(
                keyPrincipal,
                "managed-key-request-" + suffix + "-" + index,
                BigDecimal.ZERO,
                2,
                3
            ), CONCURRENT_REQUESTS)
            .then();
        Mono<Void> managementWrites = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> apiKeyService.update(
                admin,
                keyId,
                new ApiKeyDtos.UpdateApiKeyRequest(
                    "managed-" + index,
                    true,
                    new BigDecimal("1000"),
                    "OpenAI"
                )
            ), CONCURRENT_REQUESTS)
            .then();

        runConcurrently(usageWrites, managementWrites);

        assertThat(apiKeyCounters(keyId)).isEqualTo(new UsageCounters(
            CONCURRENT_REQUESTS,
            new BigDecimal("0.000000"),
            CONCURRENT_REQUESTS * 2L,
            CONCURRENT_REQUESTS * 3L
        ));
    }

    @Test
    void accountImportUpsertDoesNotReenableOrLoseConcurrentHotState() {
        String suffix = randomSuffix();
        String accountId = "managed-account-" + suffix;
        String email = "managed-account-" + suffix + "@example.com";
        databaseClient.sql("""
                INSERT INTO proxy_accounts (
                    account_id, email, access_token, auth_method, provider,
                    enabled, request_count, error_count, quota_used
                ) VALUES (
                    :accountId, :email, 'old-token', 'api-key', 'openai',
                    TRUE, 0, 0, 0
                )
                """)
            .bind("accountId", accountId)
            .bind("email", email)
            .fetch()
            .rowsUpdated()
            .block();
        AccountDtos.CreateAccountRequest imported = new AccountDtos.CreateAccountRequest(
            "external-" + suffix,
            email.toUpperCase(java.util.Locale.ROOT),
            "new-token",
            null,
            null,
            null,
            null,
            "api-key",
            "openai",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        AccountDtos.ImportAccountsRequest importRequest = new AccountDtos.ImportAccountsRequest(List.of(imported), true);

        Mono<Void> imports = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> accountPoolService.importAccounts(importRequest), CONCURRENT_REQUESTS)
            .then();
        Mono<Void> hotStateWrites = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> databaseClient.sql("""
                    UPDATE proxy_accounts
                    SET enabled = FALSE,
                        request_count = request_count + 1,
                        error_count = error_count + 1,
                        quota_used = COALESCE(quota_used, 0) + 1,
                        updated_at = clock_timestamp()
                    WHERE account_id = :accountId
                    """)
                .bind("accountId", accountId)
                .fetch()
                .rowsUpdated(), CONCURRENT_REQUESTS)
            .then();

        runConcurrently(imports, hotStateWrites);

        AccountHotState state = databaseClient.sql("""
                SELECT account_id, access_token, enabled, request_count, error_count, quota_used
                FROM proxy_accounts
                WHERE lower(email) = lower(:email)
                """)
            .bind("email", email)
            .map((row, metadata) -> new AccountHotState(
                row.get("account_id", String.class),
                row.get("access_token", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("request_count", Long.class),
                row.get("error_count", Integer.class),
                row.get("quota_used", Long.class)
            ))
            .one()
            .block();
        assertThat(state).isEqualTo(new AccountHotState(
            accountId,
            "new-token",
            false,
            (long) CONCURRENT_REQUESTS,
            CONCURRENT_REQUESTS,
            (long) CONCURRENT_REQUESTS
        ));
    }

    @Test
    void proxyAccountEmailUniquenessIsCaseInsensitive() {
        String suffix = randomSuffix();
        String email = "unique-account-" + suffix + "@example.com";
        databaseClient.sql("""
                INSERT INTO proxy_accounts (account_id, email, access_token)
                VALUES (:accountId, :email, 'token')
                """)
            .bind("accountId", "unique-account-1-" + suffix)
            .bind("email", email)
            .fetch()
            .rowsUpdated()
            .block();

        assertThatThrownBy(() -> databaseClient.sql("""
                INSERT INTO proxy_accounts (account_id, email, access_token)
                VALUES (:accountId, :email, 'token')
                """)
            .bind("accountId", "unique-account-2-" + suffix)
            .bind("email", email.toUpperCase(java.util.Locale.ROOT))
            .fetch()
            .rowsUpdated()
            .block())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsCrossUserUsernameAndEmailLoginAmbiguity() {
        String suffix = randomSuffix();
        String sharedLogin = "shared-login-" + suffix + "@example.com";
        String firstUserId = "login-namespace-1-" + suffix;
        String secondUserId = "login-namespace-2-" + suffix;
        String thirdUserId = "login-namespace-3-" + suffix;

        insertUser(
            firstUserId,
            sharedLogin.toUpperCase(java.util.Locale.ROOT),
            "first-login-" + suffix + "@example.com",
            "first-login-" + suffix
        ).block();

        assertUniqueViolation(insertUser(
            secondUserId,
            "SecondLogin" + suffix,
            sharedLogin,
            "second-login-" + suffix
        ));

        insertUser(
            secondUserId,
            "SecondLogin" + suffix,
            "second-login-" + suffix + "@example.com",
            "second-login-" + suffix
        ).block();
        assertUniqueViolation(insertUser(
            thirdUserId,
            "second-login-" + suffix + "@example.com",
            "third-login-" + suffix + "@example.com",
            "third-login-" + suffix
        ));

        executeAsDatabaseOwner(
            "DELETE FROM starry_api.admin_users WHERE user_id IN ('"
                + firstUserId + "', '" + secondUserId + "', '" + thirdUserId + "')"
        );
    }

    @Test
    void accountsSameRequestExactlyOnceUnderOneHundredConcurrentReplays() {
        String suffix = randomSuffix();
        String userId = "usage-replay-user-" + suffix;
        String keyId = "usage-replay-key-" + suffix;
        String requestId = "usage-replay-request-" + suffix;
        insertUser(userId, "UsageReplay" + suffix, "usage-replay-" + suffix + "@example.com", "usage-replay-" + suffix).block();
        updateUserBalance(userId, "10.00");
        insertApiKey(keyId, userId);
        ApiKeyPrincipal principal = apiKeyPrincipal(keyId, userId, "10.00");

        List<Integer> completions = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> reserveAndAccountUsage(
                    principal,
                    requestId,
                    new BigDecimal("0.25"),
                    2,
                    3
                )
                .thenReturn(index), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        UsageCounters expected = new UsageCounters(1L, new BigDecimal("0.250000"), 2L, 3L);
        assertThat(completions).hasSize(CONCURRENT_REQUESTS);
        assertThat(apiKeyCounters(keyId)).isEqualTo(expected);
        assertThat(dailyUsageCounters(keyId)).isEqualTo(expected);
        assertThat(eventUsageCounters(keyId)).isEqualTo(expected);
        assertThat(userBalance(userId)).isEqualByComparingTo("9.75");
        assertThat(ledgerState(userId, "API_USAGE_RESERVE"))
            .isEqualTo(new LedgerState(1L, new BigDecimal("-0.250000")));
        String keyScopedRequest = keyId.length() + ":" + keyId + requestId;
        assertThat(idempotencyLedgerCount("billing-reserve:" + Hashing.sha256(keyScopedRequest))).isEqualTo(1L);
        assertThat(brokenLedgerTransitions(userId, "API_USAGE_RESERVE")).isZero();

        String secondUserId = "usage-replay-user-2-" + suffix;
        String secondKeyId = "usage-replay-key-2-" + suffix;
        insertUser(
            secondUserId,
            "UsageReplaySecond" + suffix,
            "usage-replay-second-" + suffix + "@example.com",
            "usage-replay-second-" + suffix
        ).block();
        updateUserBalance(secondUserId, "10.00");
        insertApiKey(secondKeyId, secondUserId);
        reserveAndAccountUsage(
            apiKeyPrincipal(secondKeyId, secondUserId, "10.00"),
            requestId,
            new BigDecimal("0.25"),
            2,
            3
        ).block();

        assertThat(eventUsageCounters(secondKeyId)).isEqualTo(expected);
        assertThat(userBalance(secondUserId)).isEqualByComparingTo("9.75");
        String secondKeyScopedRequest = secondKeyId.length() + ":" + secondKeyId + requestId;
        assertThat(idempotencyLedgerCount("billing-reserve:" + Hashing.sha256(secondKeyScopedRequest))).isEqualTo(1L);
    }

    @Test
    void accountsOneHundredDistinctRequestsWithoutOverdrawingBalance() {
        String suffix = randomSuffix();
        String userId = "usage-volume-user-" + suffix;
        String keyId = "usage-volume-key-" + suffix;
        insertUser(userId, "UsageVolume" + suffix, "usage-volume-" + suffix + "@example.com", "usage-volume-" + suffix).block();
        updateUserBalance(userId, "25.00");
        insertApiKey(keyId, userId);
        ApiKeyPrincipal principal = apiKeyPrincipal(keyId, userId, "25.00");

        List<Integer> completions = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> reserveAndAccountUsage(
                    principal,
                    "usage-volume-request-" + suffix + "-" + index,
                    new BigDecimal("0.25"),
                    2,
                    3
                )
                .thenReturn(index), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        UsageCounters expected = new UsageCounters(100L, new BigDecimal("25.000000"), 200L, 300L);
        assertThat(completions).hasSize(CONCURRENT_REQUESTS);
        assertThat(apiKeyCounters(keyId)).isEqualTo(expected);
        assertThat(dailyUsageCounters(keyId)).isEqualTo(expected);
        assertThat(eventUsageCounters(keyId)).isEqualTo(expected);
        assertThat(userBalance(userId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledgerState(userId, "API_USAGE_RESERVE"))
            .isEqualTo(new LedgerState(100L, new BigDecimal("-25.000000")));
        assertThat(brokenLedgerTransitions(userId, "API_USAGE_RESERVE")).isZero();
    }

    @Test
    void enforcesApiKeyCreditsLimitAtTheConcurrentBoundary() {
        String suffix = randomSuffix();
        String userId = "usage-credit-limit-user-" + suffix;
        String keyId = "usage-credit-limit-key-" + suffix;
        insertUser(
            userId,
            "UsageCreditLimit" + suffix,
            "usage-credit-limit-" + suffix + "@example.com",
            "usage-credit-limit-" + suffix
        ).block();
        updateUserBalance(userId, "100.00");
        insertApiKey(keyId, userId);
        databaseClient.sql("UPDATE api_keys SET credits_limit = 1.00 WHERE key_id = :keyId")
            .bind("keyId", keyId)
            .fetch()
            .rowsUpdated()
            .block();
        ApiKeyPrincipal principal = apiKeyPrincipal(keyId, userId, "100.00");

        List<Boolean> results = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> reserveAndAccountUsage(
                    principal,
                    "usage-credit-limit-request-" + suffix + "-" + index,
                    new BigDecimal("0.25"),
                    2,
                    3
                )
                .thenReturn(true)
                .onErrorResume(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("CREDITS_LIMIT_EXCEEDED");
                    return Mono.just(false);
                }), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        UsageCounters expected = new UsageCounters(4L, new BigDecimal("1.000000"), 8L, 12L);
        assertThat(results).hasSize(CONCURRENT_REQUESTS);
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(4L);
        assertThat(apiKeyCounters(keyId)).isEqualTo(expected);
        assertThat(dailyUsageCounters(keyId)).isEqualTo(expected);
        assertThat(eventUsageCounters(keyId)).isEqualTo(expected);
        assertThat(userBalance(userId)).isEqualByComparingTo("99.00");
        assertThat(ledgerState(userId, "API_USAGE_RESERVE"))
            .isEqualTo(new LedgerState(4L, new BigDecimal("-1.000000")));
        assertThat(apiKeyBillingMinimum(keyId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(brokenLedgerTransitions(userId, "API_USAGE_RESERVE")).isZero();
    }

    @Test
    void rejectsUnaffordableConcurrentUsageWithoutPartialDebitsAndRequiresAFullTopUp() {
        String suffix = randomSuffix();
        String userId = "usage-limited-user-" + suffix;
        String keyId = "usage-limited-key-" + suffix;
        insertUser(userId, "UsageLimited" + suffix, "usage-limited-" + suffix + "@example.com", "usage-limited-" + suffix).block();
        updateUserBalance(userId, "0.50");
        insertApiKey(keyId, userId);
        ApiKeyPrincipal principal = apiKeyPrincipal(keyId, userId, "0.50");

        List<Boolean> results = Flux.range(0, CONCURRENT_REQUESTS)
            .flatMap(index -> reserveAndAccountUsage(
                    principal,
                    "usage-limited-request-" + suffix + "-" + index,
                    new BigDecimal("0.25"),
                    2,
                    3
                )
                .thenReturn(true)
                .onErrorResume(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
                    return Mono.just(false);
                }), CONCURRENT_REQUESTS)
            .collectList()
            .block();

        UsageCounters expected = new UsageCounters(2L, new BigDecimal("0.500000"), 4L, 6L);
        assertThat(results).hasSize(CONCURRENT_REQUESTS);
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(2L);
        assertThat(apiKeyCounters(keyId)).isEqualTo(expected);
        assertThat(dailyUsageCounters(keyId)).isEqualTo(expected);
        assertThat(eventUsageCounters(keyId)).isEqualTo(expected);
        assertThat(userBalance(userId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledgerState(userId, "API_USAGE_RESERVE"))
            .isEqualTo(new LedgerState(2L, new BigDecimal("-0.500000")));
        assertThat(apiKeyBillingMinimum(keyId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(brokenLedgerTransitions(userId, "API_USAGE_RESERVE")).isZero();

        // This fixture top-up bypasses the ledger, so continuity is asserted before it.
        updateUserBalance(userId, "0.25");
        reserveAndAccountUsage(
            principal,
            "usage-limited-after-top-up-" + suffix,
            new BigDecimal("0.25"),
            2,
            3
        ).block();

        assertThat(userBalance(userId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(apiKeyBillingMinimum(keyId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledgerState(userId, "API_USAGE_RESERVE"))
            .isEqualTo(new LedgerState(3L, new BigDecimal("-0.750000")));
    }

    private Mono<Void> reserveAndAccountUsage(ApiKeyPrincipal principal, String requestId, BigDecimal credits, long input, long output) {
        return apiKeyService.reserveUsage(principal, requestId, credits, "{}")
            .onErrorResume(ApiException.class, error -> "BILLING_REQUEST_FINISHED".equals(error.getCode())
                ? Mono.empty() : Mono.error(error))
            .then(apiKeyService.accountUsage(principal, requestId, credits, input, output));
    }

    private void runConcurrently(Mono<?> first, Mono<?> second) {
        Mono.when(
            first.subscribeOn(Schedulers.parallel()),
            second.subscribeOn(Schedulers.parallel())
        ).block(Duration.ofSeconds(10));
    }

    private UpdatePaymentSettingsRequest paymentSettingsUpdate(
        Boolean enabled,
        String productNamePrefix,
        String easypayPkey
    ) {
        return new UpdatePaymentSettingsRequest(
            enabled,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            productNamePrefix,
            null,
            null,
            null,
            null,
            null,
            easypayPkey,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private AdminDtos.UpdateRegistrationSettingsRequest registrationSettingsUpdate(
        Boolean registrationEnabled,
        String smtpHost,
        String smtpPassword,
        Boolean turnstileEnabled,
        String turnstileSiteKey,
        String turnstileSecretKey
    ) {
        return new AdminDtos.UpdateRegistrationSettingsRequest(
            registrationEnabled,
            null,
            null,
            null,
            smtpHost,
            null,
            null,
            smtpPassword,
            null,
            null,
            null,
            null,
            turnstileEnabled,
            turnstileSiteKey,
            turnstileSecretKey
        );
    }

    private Map<String, String> systemSettingsSnapshot() {
        return systemSettingRepository.findAll()
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
            .block();
    }

    private void restoreSystemSettings(Map<String, String> snapshot) {
        executeAsDatabaseOwner("""
            DELETE FROM starry_api.system_settings
            WHERE setting_key <> 'application_root_user_id'
            """);
        if (snapshot != null && !snapshot.isEmpty()) {
            String[] keys = snapshot.keySet().toArray(String[]::new);
            String[] values = Arrays.stream(keys).map(snapshot::get).toArray(String[]::new);
            systemSettingRepository.upsertSettings(keys, values).block();
        }
        accountSettingsService.invalidateAllCaches();
        paymentSettingsService.invalidateAllCaches();
    }

    private String settingValue(String settingKey) {
        SystemSettingEntity setting = systemSettingRepository.findById(settingKey).block();
        assertThat(setting).isNotNull();
        return setting.getSettingValue();
    }

    private PaymentRebateState paymentRebateState(String orderId) {
        return databaseClient.sql("""
                SELECT rebate_snapshot_at IS NOT NULL AS snapshot_present,
                       rebate_enabled,
                       rebate_rate,
                       rebate_max_orders,
                       rebate_campaign_start_at,
                       rebate_campaign_end_at,
                       rebate_processed_at IS NOT NULL AS processed,
                       payment_trade_no
                FROM payment_orders
                WHERE order_id = :orderId
                """)
            .bind("orderId", orderId)
            .map((row, metadata) -> new PaymentRebateState(
                Boolean.TRUE.equals(row.get("snapshot_present", Boolean.class)),
                Boolean.TRUE.equals(row.get("rebate_enabled", Boolean.class)),
                row.get("rebate_rate", BigDecimal.class),
                row.get("rebate_max_orders", Integer.class),
                row.get("rebate_campaign_start_at", Instant.class),
                row.get("rebate_campaign_end_at", Instant.class),
                Boolean.TRUE.equals(row.get("processed", Boolean.class)),
                row.get("payment_trade_no", String.class)
            ))
            .one()
            .block();
    }

    private void awaitSystemSettingNotificationListener() {
        Boolean listening = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
            .concatMap(ignored -> databaseClient.sql("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_stat_activity
                        WHERE usename = CURRENT_USER
                          AND state = 'idle'
                          AND query = 'LISTEN starry_api_system_settings'
                    ) AS listening
                    """)
                .map((row, metadata) -> row.get("listening", Boolean.class))
                .one())
            .filter(Boolean.TRUE::equals)
            .next()
            .timeout(Duration.ofSeconds(5))
            .block();
        assertThat(listening).isTrue();
    }

    private reactor.core.publisher.Mono<Long> insertUser(
        String userId,
        String username,
        String email,
        String affiliateCode
    ) {
        return databaseClient.sql("""
                INSERT INTO admin_users (
                    user_id, username, email, affiliate_code, role, balance,
                    password_hash, password_salt, enabled, first_login
                )
                VALUES (
                    :userId, :username, :email, :affiliateCode, 'USER', 0,
                    :passwordHash, :passwordSalt, TRUE, FALSE
                )
                """)
            .bind("userId", userId)
            .bind("username", username)
            .bind("email", email)
            .bind("affiliateCode", affiliateCode)
            .bind("passwordHash", "a".repeat(64))
            .bind("passwordSalt", "b".repeat(32))
            .fetch()
            .rowsUpdated();
    }

    private void assertUniqueViolation(reactor.core.publisher.Mono<Long> insert) {
        StepVerifier.create(insert)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    private void callPartitionFunction(String sql) {
        databaseClient.sql(sql)
            .map((row, metadata) -> 1)
            .one()
            .block();
    }

    private void insertRequestLog(
        String requestId,
        String model,
        boolean success,
        long inputTokens,
        long outputTokens,
        String credits,
        Instant createdAt
    ) {
        databaseClient.sql("""
                INSERT INTO request_logs (
                    request_id, path, model, status_code, success, input_tokens, output_tokens,
                    credits, latency_ms, created_at
                )
                VALUES (
                    :requestId, '/v1/chat/completions', :model, :statusCode, :success,
                    :inputTokens, :outputTokens, CAST(:credits AS numeric), 25, :createdAt
                )
                """)
            .bind("requestId", requestId)
            .bind("model", model)
            .bind("statusCode", success ? 200 : 500)
            .bind("success", success)
            .bind("inputTokens", inputTokens)
            .bind("outputTokens", outputTokens)
            .bind("credits", credits)
            .bind("createdAt", createdAt)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private void updateUserBalance(String userId, String balance) {
        databaseClient.sql("UPDATE admin_users SET balance = CAST(:balance AS numeric) WHERE user_id = :userId")
            .bind("balance", balance)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private void updatePaymentRebateSnapshot(String orderId, String rebateRate, int rebateMaxOrders) {
        databaseClient.sql("""
                UPDATE payment_orders
                SET rebate_rate = CAST(:rebateRate AS numeric),
                    rebate_max_orders = :rebateMaxOrders
                WHERE order_id = :orderId
                """)
            .bind("rebateRate", rebateRate)
            .bind("rebateMaxOrders", rebateMaxOrders)
            .bind("orderId", orderId)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private void insertPaymentOrder(
        String orderId,
        String outTradeNo,
        String userId,
        String amount,
        String status,
        Instant creditedAt
    ) {
        insertPaymentOrder(
            orderId,
            outTradeNo,
            userId,
            amount,
            status,
            null,
            creditedAt,
            creditedAt,
            null,
            null
        );
    }

    private void insertPaymentOrder(
        String orderId,
        String outTradeNo,
        String userId,
        String amount,
        String status,
        Instant paidAt,
        Instant completedAt,
        Instant creditedAt
    ) {
        insertPaymentOrder(
            orderId,
            outTradeNo,
            userId,
            amount,
            status,
            paidAt,
            completedAt,
            creditedAt,
            null,
            null
        );
    }

    private void insertPaymentOrder(
        String orderId,
        String outTradeNo,
        String userId,
        String amount,
        String status,
        Instant paidAt,
        Instant completedAt,
        Instant creditedAt,
        Instant rebateCampaignStartAt,
        Instant rebateCampaignEndAt
    ) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                INSERT INTO payment_orders (
                    order_id, user_id, amount, pay_amount, fee_rate, payment_type,
                    status, out_trade_no, expires_at, paid_at, completed_at, credited_at,
                    rebate_snapshot_at, rebate_enabled, rebate_rate, rebate_max_orders,
                    rebate_campaign_start_at, rebate_campaign_end_at
                )
                VALUES (
                    :orderId, :userId, CAST(:amount AS numeric), CAST(:amount AS numeric),
                    0, 'alipay', :status, :outTradeNo, :expiresAt, :paidAt, :completedAt, :creditedAt,
                    :creditedAt,
                    CASE WHEN :creditedAt IS NULL THEN NULL ELSE TRUE END,
                    CASE WHEN :creditedAt IS NULL THEN NULL ELSE 0.10 END,
                    CASE WHEN :creditedAt IS NULL THEN NULL ELSE 10 END,
                    :rebateCampaignStartAt, :rebateCampaignEndAt
                )
                """)
            .bind("orderId", orderId)
            .bind("userId", userId)
            .bind("amount", amount)
            .bind("status", status)
            .bind("outTradeNo", outTradeNo)
            .bind("expiresAt", Instant.now().plusSeconds(3_600));
        spec = bindNullable(spec, "paidAt", paidAt);
        spec = bindNullable(spec, "completedAt", completedAt);
        spec = bindNullable(spec, "creditedAt", creditedAt);
        spec = bindNullable(spec, "rebateCampaignStartAt", rebateCampaignStartAt);
        spec = bindNullable(spec, "rebateCampaignEndAt", rebateCampaignEndAt);
        spec.fetch().rowsUpdated().block();
    }

    private DatabaseClient.GenericExecuteSpec bindNullable(
        DatabaseClient.GenericExecuteSpec spec,
        String name,
        Instant value
    ) {
        return value == null ? spec.bindNull(name, Instant.class) : spec.bind(name, value);
    }

    private void insertRedemptionCode(String codeId, String code, String balance) {
        databaseClient.sql("""
                INSERT INTO redemption_codes (code_id, code, balance, enabled, used)
                VALUES (:codeId, :code, CAST(:balance AS numeric), TRUE, FALSE)
                """)
            .bind("codeId", codeId)
            .bind("code", code)
            .bind("balance", balance)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private void insertApiKey(String keyId, String userId) {
        String keyHash = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        databaseClient.sql("""
                INSERT INTO api_keys (
                    key_id, user_id, name, key_hash, key_prefix, enabled,
                    total_requests, total_credits, total_input_tokens, total_output_tokens
                )
                VALUES (:keyId, :userId, 'Integration key', :keyHash, 'sk-integration', TRUE, 0, 0, 0, 0)
                """)
            .bind("keyId", keyId)
            .bind("userId", userId)
            .bind("keyHash", keyHash)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private ApiKeyPrincipal apiKeyPrincipal(String keyId, String userId, String balance) {
        return new ApiKeyPrincipal(
            keyId,
            userId,
            "USER",
            "Integration key",
            null,
            BigDecimal.ZERO,
            new BigDecimal(balance)
        );
    }

    private BigDecimal userBalance(String userId) {
        return databaseClient.sql("SELECT balance FROM admin_users WHERE user_id = :userId")
            .bind("userId", userId)
            .map((row, metadata) -> row.get("balance", BigDecimal.class))
            .one()
            .block();
    }

    private LedgerState ledgerState(String userId, String entryType) {
        return databaseClient.sql("""
                SELECT COUNT(*)::bigint AS entry_count,
                       COALESCE(SUM(amount), 0) AS total_amount
                FROM balance_ledger
                WHERE user_id = :userId AND entry_type = :entryType
                """)
            .bind("userId", userId)
            .bind("entryType", entryType)
            .map((row, metadata) -> new LedgerState(
                row.get("entry_count", Long.class),
                row.get("total_amount", BigDecimal.class)
            ))
            .one()
            .block();
    }

    private InviteRebateAuditState inviteRebateAuditState(String invitedUserId) {
        return databaseClient.sql("""
                SELECT COUNT(*)::bigint AS record_count,
                       COUNT(DISTINCT rebate_order_no)::bigint AS distinct_rebate_order_nos,
                       MIN(rebate_max_orders) AS rebate_max_orders,
                       MIN(campaign_start_at) AS campaign_start_at,
                       MIN(campaign_end_at) AS campaign_end_at,
                       COUNT(DISTINCT payment_effective_at)::bigint AS distinct_effective_times
                FROM invite_rebate_records
                WHERE invited_user_id = :invitedUserId
                """)
            .bind("invitedUserId", invitedUserId)
            .map((row, metadata) -> new InviteRebateAuditState(
                row.get("record_count", Long.class),
                row.get("distinct_rebate_order_nos", Long.class),
                row.get("rebate_max_orders", Integer.class),
                row.get("campaign_start_at", Instant.class),
                row.get("campaign_end_at", Instant.class),
                row.get("distinct_effective_times", Long.class)
            ))
            .one()
            .block();
    }

    private InviteRebateOrderState inviteRebateOrderState(String paymentOrderId) {
        return databaseClient.sql("""
                SELECT rebate_order_no, payment_order_no, rebate_rate, rebate_amount,
                       rebate_max_orders, payment_effective_at
                FROM invite_rebate_records
                WHERE payment_order_id = :paymentOrderId
                """)
            .bind("paymentOrderId", paymentOrderId)
            .map((row, metadata) -> new InviteRebateOrderState(
                row.get("rebate_order_no", Integer.class),
                row.get("payment_order_no", Integer.class),
                row.get("rebate_rate", BigDecimal.class),
                row.get("rebate_amount", BigDecimal.class),
                row.get("rebate_max_orders", Integer.class),
                row.get("payment_effective_at", Instant.class)
            ))
            .one()
            .block();
    }

    private long idempotencyLedgerCount(String idempotencyKey) {
        return countRows("balance_ledger", "idempotency_key", idempotencyKey);
    }

    private UsageCounters apiKeyCounters(String keyId) {
        return usageCounters("""
            SELECT total_requests AS requests,
                   total_credits AS credits,
                   total_input_tokens AS input_tokens,
                   total_output_tokens AS output_tokens
            FROM api_keys
            WHERE key_id = :keyId
            """, keyId);
    }

    private BigDecimal apiKeyBillingMinimum(String keyId) {
        return databaseClient.sql("""
                SELECT billing_minimum_credits
                FROM api_keys
                WHERE key_id = :keyId
                """)
            .bind("keyId", keyId)
            .map((row, metadata) -> row.get("billing_minimum_credits", BigDecimal.class))
            .one()
            .block();
    }

    private UsageCounters dailyUsageCounters(String keyId) {
        return usageCounters("""
            SELECT COALESCE(SUM(requests), 0)::bigint AS requests,
                   COALESCE(SUM(credits), 0) AS credits,
                   COALESCE(SUM(input_tokens), 0)::bigint AS input_tokens,
                   COALESCE(SUM(output_tokens), 0)::bigint AS output_tokens
            FROM api_key_usage_daily
            WHERE key_id = :keyId
            """, keyId);
    }

    private UsageCounters eventUsageCounters(String keyId) {
        return usageCounters("""
            SELECT COUNT(*)::bigint AS requests,
                   COALESCE(SUM(credits), 0) AS credits,
                   COALESCE(SUM(input_tokens), 0)::bigint AS input_tokens,
                   COALESCE(SUM(output_tokens), 0)::bigint AS output_tokens
            FROM api_usage_events
            WHERE key_id = :keyId
            """, keyId);
    }

    private boolean apiUsageEventExists(String requestHash) {
        return Boolean.TRUE.equals(databaseClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM api_usage_events
                    WHERE request_hash = :requestHash
                ) AS event_exists
                """)
            .bind("requestHash", requestHash)
            .map((row, metadata) -> row.get("event_exists", Boolean.class))
            .one()
            .block());
    }

    private UsageCounters usageCounters(String sql, String keyId) {
        return databaseClient.sql(sql)
            .bind("keyId", keyId)
            .map((row, metadata) -> new UsageCounters(
                row.get("requests", Long.class),
                row.get("credits", BigDecimal.class),
                row.get("input_tokens", Long.class),
                row.get("output_tokens", Long.class)
            ))
            .one()
            .block();
    }

    private long brokenLedgerTransitions(String userId, String entryType) {
        Long transitions = databaseClient.sql("""
                SELECT COUNT(*)::bigint AS broken_transitions
                FROM (
                    SELECT balance_before,
                           LAG(balance_after) OVER (ORDER BY id) AS previous_balance_after
                    FROM balance_ledger
                    WHERE user_id = :userId AND entry_type = :entryType
                ) entries
                WHERE previous_balance_after IS NOT NULL
                  AND balance_before <> previous_balance_after
                """)
            .bind("userId", userId)
            .bind("entryType", entryType)
            .map((row, metadata) -> row.get("broken_transitions", Long.class))
            .one()
            .block();
        return transitions == null ? 0L : transitions;
    }

    private long countRows(String table, String column, String value) {
        Long count = databaseClient.sql("SELECT COUNT(*)::bigint AS row_count FROM " + table + " WHERE " + column + " = :value")
            .bind("value", value)
            .map((row, metadata) -> row.get("row_count", Long.class))
            .one()
            .block();
        return count == null ? 0L : count;
    }

    private Long insertLedgerForPartitionTest(
        String sql,
        String transactionId,
        String userId,
        String referenceId,
        String idempotencyKey
    ) {
        return databaseClient.sql(sql)
            .bind("transactionId", transactionId)
            .bind("userId", userId)
            .bind("referenceId", referenceId)
            .bind("idempotencyKey", idempotencyKey)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private void cleanupSegmentedUsage(String userId) {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        )) {
            connection.setAutoCommit(false);
            try {
                for (String sql : List.of(
                    "DELETE FROM starry_api.request_logs WHERE user_id = ?",
                    "DELETE FROM starry_api.request_usage_hourly WHERE user_id = ?",
                    "DELETE FROM starry_api.request_usage_daily WHERE user_id = ?"
                )) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, userId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM starry_api.aggregation_watermarks WHERE job_name = ?"
                )) {
                    statement.setString(1, "request_usage");
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not clean segmented usage integration-test data", exception);
        }
    }

    private PaymentConfig paymentConfig(boolean inviteRebateEnabled) {
        return paymentConfig(inviteRebateEnabled, null, null);
    }

    private PaymentConfig paymentConfig(
        boolean inviteRebateEnabled,
        Instant inviteRebateStartAt,
        Instant inviteRebateEndAt
    ) {
        return new PaymentConfig(
            true,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            30,
            3,
            List.of("alipay"),
            BigDecimal.ONE,
            PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER,
            BigDecimal.ZERO,
            "",
            "",
            "",
            "",
            "https://payment.example.com",
            "test-pid",
            "test-key",
            "popup",
            inviteRebateEnabled,
            new BigDecimal("0.10"),
            10,
            inviteRebateStartAt,
            inviteRebateEndAt
        );
    }

    private long hourlyRequestCount(String model) {
        Long count = databaseClient.sql("""
                SELECT COALESCE(SUM(request_count), 0)::bigint AS request_count
                FROM request_usage_hourly
                WHERE model = :model
                """)
            .bind("model", model)
            .map((row, metadata) -> row.get("request_count", Long.class))
            .one()
            .block();
        return count == null ? 0L : count;
    }

    private Instant hourlyAggregateUpdatedAt(String model) {
        return databaseClient.sql("""
                SELECT MAX(updated_at) AS updated_at
                FROM request_usage_hourly
                WHERE model = :model
                """)
            .bind("model", model)
            .map((row, metadata) -> row.get("updated_at", Instant.class))
            .one()
            .block();
    }

    private Instant requestUsageWatermark() {
        return databaseClient.sql("""
                SELECT last_processed_at
                FROM aggregation_watermarks
                WHERE job_name = 'request_usage'
                """)
            .map((row, metadata) -> row.get("last_processed_at", Instant.class))
            .one()
            .block();
    }

    private void assertUsage(
        StatsDtos.UsageSummaryResponse summary,
        String model,
        long requests,
        long successes,
        long failures,
        long inputTokens,
        long outputTokens,
        String credits
    ) {
        assertThat(summary).isNotNull();
        assertThat(summary.totalRequests()).isEqualTo(requests);
        assertThat(summary.successfulRequests()).isEqualTo(successes);
        assertThat(summary.failedRequests()).isEqualTo(failures);
        assertThat(summary.totalInputTokens()).isEqualTo(inputTokens);
        assertThat(summary.totalOutputTokens()).isEqualTo(outputTokens);
        assertThat(summary.totalCredits()).isEqualByComparingTo(credits);

        StatsDtos.ModelUsageResponse modelUsage = summary.models().stream()
            .filter(item -> model.equals(item.model()))
            .findFirst()
            .orElseThrow();
        assertThat(modelUsage.requestCount()).isEqualTo(requests);
        assertThat(modelUsage.successfulRequests()).isEqualTo(successes);
        assertThat(modelUsage.failedRequests()).isEqualTo(failures);
        assertThat(modelUsage.inputTokens()).isEqualTo(inputTokens);
        assertThat(modelUsage.outputTokens()).isEqualTo(outputTokens);
        assertThat(modelUsage.credits()).isEqualByComparingTo(credits);
    }

    private boolean relationExists(String qualifiedName) {
        return Boolean.TRUE.equals(databaseClient.sql("""
                SELECT pg_catalog.to_regclass(CAST(:qualifiedName AS text)) IS NOT NULL AS relation_exists
                """)
            .bind("qualifiedName", qualifiedName)
            .map((row, metadata) -> row.get("relation_exists", Boolean.class))
            .one()
            .block());
    }

    private boolean hasAppendOnlyRuntimePrivileges(String qualifiedName) {
        return Boolean.TRUE.equals(databaseClient.sql("""
                SELECT pg_catalog.has_table_privilege(
                           current_user,
                           pg_catalog.to_regclass(CAST(:qualifiedName AS text)),
                           'SELECT,INSERT'
                       ) AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           pg_catalog.to_regclass(CAST(:qualifiedName AS text)),
                           'UPDATE,DELETE'
                       ) AS append_only_privileges
                """)
            .bind("qualifiedName", qualifiedName)
            .map((row, metadata) -> row.get("append_only_privileges", Boolean.class))
            .one()
            .block());
    }

    private PostgresqlConnection createNotificationConnection() {
        ConnectionFactory connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
            .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
            .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
            .option(ConnectionFactoryOptions.USER, "starry_api")
            .option(ConnectionFactoryOptions.PASSWORD, "starry_api_app_test")
            .build());
        return (PostgresqlConnection) Mono.from(connectionFactory.create()).block();
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record DatabaseIdentity(
        String databaseName,
        String schemaName,
        int serverVersionNumber,
        boolean baselineApplied,
        int usageEventPartitions,
        boolean requestHashPrimaryKey
    ) {
    }

    private record ApplicationRootIdentity(
        String markerUserId,
        String userId,
        String username,
        String role,
        boolean enabled,
        boolean migrationApplied,
        boolean settingTriggerEnabled,
        boolean userTriggerEnabled
    ) {
    }

    private record RoleSecurityState(
        String currentUser,
        boolean runtimeIsRestricted,
        boolean runtimeGroupIsRestricted,
        boolean ownerIsRestricted,
        boolean migrationRequiresSetRole,
        boolean migrationCanSetOwner,
        boolean ownerCanManageRuntimeSessions,
        boolean runtimeMember,
        boolean notOwnerMember,
        boolean schemaUsage,
        boolean noSchemaCreate,
        boolean noPublicSchemaAccess,
        boolean noPublicDatabaseAccess,
        boolean businessDml,
        boolean ledgerAppend,
        boolean ledgerImmutable,
        boolean auditTablesAppendOnly,
        boolean derivedAggregateInsertProtected,
        boolean flywayHistoryReadOnly,
        boolean identityUsage,
        boolean maintenanceExecute,
        boolean noDirectTriggerExecute,
        boolean schemaOwnedByOwner,
        boolean objectsOwnedByOwner,
        boolean noPublicFunctionExecute
    ) {
        private boolean allBoundariesEnforced() {
            return runtimeIsRestricted
                && runtimeGroupIsRestricted
                && ownerIsRestricted
                && migrationRequiresSetRole
                && migrationCanSetOwner
                && ownerCanManageRuntimeSessions
                && runtimeMember
                && notOwnerMember
                && schemaUsage
                && noSchemaCreate
                && noPublicSchemaAccess
                && noPublicDatabaseAccess
                && businessDml
                && ledgerAppend
                && ledgerImmutable
                && auditTablesAppendOnly
                && derivedAggregateInsertProtected
                && flywayHistoryReadOnly
                && identityUsage
                && maintenanceExecute
                && noDirectTriggerExecute
                && schemaOwnedByOwner
                && objectsOwnedByOwner
                && noPublicFunctionExecute;
        }
    }

    private record JsonbState(String targetType, boolean containsTarget, int apiKeyCount) {
    }

    private record LedgerState(long count, BigDecimal amount) {
    }

    private record InviteRebateAuditState(
        long recordCount,
        long distinctRebateOrderNos,
        int rebateMaxOrders,
        Instant campaignStartAt,
        Instant campaignEndAt,
        long distinctEffectiveTimes
    ) {
    }

    private record InviteRebateOrderState(
        int rebateOrderNo,
        int paymentOrderNo,
        BigDecimal rebateRate,
        BigDecimal rebateAmount,
        int rebateMaxOrders,
        Instant paymentEffectiveAt
    ) {
    }

    private record PaymentState(String status, boolean credited) {
    }

    private record PaymentRebateState(
        boolean snapshotPresent,
        boolean rebateEnabled,
        BigDecimal rebateRate,
        int rebateMaxOrders,
        Instant campaignStartAt,
        Instant campaignEndAt,
        boolean processed,
        String paymentTradeNo
    ) {
    }

    private record RedemptionState(boolean enabled, boolean used, String userId) {
    }

    private record AccountHotState(
        String accountId,
        String accessToken,
        boolean enabled,
        long requestCount,
        int errorCount,
        long quotaUsed
    ) {
    }

    private record UsageCounters(long requests, BigDecimal credits, long inputTokens, long outputTokens) {
    }
}
