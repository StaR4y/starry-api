package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.egress.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "starry.stats.aggregation-enabled=false"
})
class ProxyChannelsPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired tech.starryapi.account.AccountPoolService accounts;
    @Autowired tech.starryapi.account.AccountAllocationPolicyService policies;

    private final SubscriptionFetcher fetcher = mock(SubscriptionFetcher.class);
    private final MihomoRuntime runtime = mock(MihomoRuntime.class);
    private final EgressProperties props = new EgressProperties();
    private ClashNodes parser;
    private ChannelRoutes routes;
    private ProxyChannelService service;

    @BeforeEach void freshChannels() {
        // The container is shared by the tests of this class: every test starts without nodes.
        db.sql("UPDATE proxy_accounts SET proxy_channel_id=NULL").fetch().rowsUpdated().block();
        db.sql("DELETE FROM proxy_channels").fetch().rowsUpdated().block();
        db.sql("DELETE FROM proxy_subscriptions").fetch().rowsUpdated().block();
        reset(runtime, fetcher);
        when(runtime.reconcile()).thenReturn(Mono.empty());
        when(runtime.health()).thenReturn(new MihomoRuntime.Health(null, null, null, 0, null, null));
        props.setMihomoEnabled(false);
        parser = new ClashNodes(json);
        routes = new ChannelRoutes(db, runtime, json, props);
        service = new ProxyChannelService(db, json, parser, fetcher, props, runtime, routes, transactions);
    }

    @Test void nestedPolicyWritesAreAtomicAndSkippedImportStillReturnsSkipped() throws Exception {
        var create = json.readValue("""
            {"accountId":"atomic-egress","email":"atomic-egress@example.test","accessToken":"test",
             "provider":"OpenAI","authMethod":"api-key","allocationPolicy":{"safeConcurrency":3}}
            """, tech.starryapi.account.AccountDtos.CreateAccountRequest.class);
        var account = accounts.create(create).block();
        assertThat(policies.get(account.getAccountId()).block().safeConcurrency()).isEqualTo(3);
        var imported = accounts.importAccounts(new tech.starryapi.account.AccountDtos.ImportAccountsRequest(java.util.List.of(create), false)).block();
        assertThat(imported.skipped()).isEqualTo(1);
        assertThat(imported.failed()).isZero();
        var invalid = json.readValue("""
            {"accountId":"invalid-egress","email":"invalid-egress@example.test","accessToken":"test",
             "provider":"OpenAI","authMethod":"api-key","allocationPolicy":{"safeConcurrency":-1}}
            """, tech.starryapi.account.AccountDtos.CreateAccountRequest.class);
        assertThatThrownBy(() -> accounts.create(invalid).block()).isInstanceOf(RuntimeException.class);
        assertThat(db.sql("SELECT count(*) AS n FROM proxy_accounts WHERE email='invalid-egress@example.test'")
            .map((r,m) -> r.get("n", Long.class)).one().block()).isZero();
    }

    @Test void subscriptionKeepsBindingsDeduplicatesAndFailsClosedWhenNodeDisappears() {
        String url = "https://source.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: A, type: vless, server: example.com, port: 443}\n  - {name: duplicate, type: vless, server: example.com, port: 443}"));
        var imported = service.subscribe(new ProxyChannelDtos.ImportSubscription("test", url, null)).block();
        var nodes = service.catalog().block().channels().stream().filter(c -> imported.subscriptionId().equals(c.subscriptionId())).toList();
        assertThat(nodes).hasSize(1);
        UUID channel = nodes.getFirst().channelId();
        var portsBefore = ports();
        service.sync(imported.subscriptionId()).block();
        // An unchanged node keeps its row and its port; a refresh consumes nothing.
        assertThat(ports()).isEqualTo(portsBefore);
        String accountId = "egress-" + UUID.randomUUID();
        insertAccount(accountId, channel);
        assertThatThrownBy(() -> service.delete(channel).block()).hasMessageContaining("Unbind accounts");

        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: B, type: socks5, server: example.com, port: 8081}"));
        service.sync(imported.subscriptionId()).block();
        assertThat(service.channel(channel).block().runtimeStatus()).isEqualTo("ERROR");
        var transport = new AccountEgressTransport(db, routes, props, new ProxyProperties(), parser);
        var direct = new AtomicBoolean();
        var client = WebClient.builder().filter(transport.filter()).exchangeFunction(request -> {
            direct.set(true); return Mono.error(new AssertionError("Must never use direct transport"));
        }).build();
        var account = new ProxyAccountEntity(); account.setAccountId(accountId);
        assertThatThrownBy(() -> client.get().uri("https://target.invalid").attribute(AccountEgressTransport.ACCOUNT, account)
            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5))).isInstanceOf(EgressException.class)
            .satisfies(error -> assertThat(((EgressException) error).requestMayHaveBeenSent()).isFalse());
        assertThat(direct).isFalse();
    }

    @Test void expandsProvidersAndKeepsLastSubscriptionAfterAnInvalidRefresh() {
        String url = "https://source.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxy-providers:\n  one:\n    type: http\n    url: https://provider.example/nodes"));
        when(fetcher.fetch("https://provider.example/nodes")).thenReturn(Mono.just("proxies:\n  - {name: A, type: http, server: example.com, port: 8080}"));
        var imported = service.subscribe(new ProxyChannelDtos.ImportSubscription("providers", url, null)).block();
        assertThat(imported.imported()).isEqualTo(1);
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxy-providers:\n  bad: {type: file, path: /etc/passwd}"));
        assertThatThrownBy(() -> service.sync(imported.subscriptionId()).block()).hasMessageContaining("HTTP and inline");
        assertThat(service.catalog().block().channels().stream().filter(c -> imported.subscriptionId().equals(c.subscriptionId()))).hasSize(1);
    }

    // ---- A3: listener ports ----

    @Test void portsAreAssignedLowestFirstReusedAndOnlyHeldByNodesThatNeedMihomo() {
        UUID a = createVless("a"), b = createVless("b"), c = createVless("c");
        assertThat(ports()).containsExactly(20000, 20001, 20002);
        service.delete(b).block();
        UUID d = createVless("d");
        assertThat(portOf(d)).isEqualTo(20001);
        // A plain HTTP or SOCKS5 node is reached directly and never takes a listener port.
        UUID http = service.create(new ProxyChannelDtos.CreateChannel("http", "http://127.0.0.1:3128", null)).block().channelId();
        UUID socks = service.create(new ProxyChannelDtos.CreateChannel("socks", "socks5://127.0.0.1:1080", null)).block().channelId();
        assertThat(portOf(http)).isNull();
        assertThat(portOf(socks)).isNull();
        assertThat(ports()).containsExactly(20000, 20001, 20002);
        assertThat(List.of(a, c)).allSatisfy(id -> assertThat(portOf(id)).isNotNull());
    }

    @Test void aFullRangeRejectsTheNextMihomoNodeWithAClearConflictButNotADirectOne() {
        db.sql("""
            INSERT INTO proxy_channels(channel_id,name,proxy_name,protocol,node_config,config_fingerprint,listener_port)
            SELECT gen_random_uuid(),'fill-'||p,'fill-'||p,'vless','{"type":"vless"}'::jsonb,repeat('0',64),p
            FROM generate_series(20000,29999) AS p
            """).fetch().rowsUpdated().block();
        try {
            assertThatThrownBy(() -> createVless("one-too-many")).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("PROXY_PORT_RANGE_FULL");
            });
            assertThat(service.create(new ProxyChannelDtos.CreateChannel("direct", "http://127.0.0.1:3128", null)).block()).isNotNull();
            // A failed subscription import rolls back as a whole and says why.
            when(fetcher.fetch("https://full.example/sub")).thenReturn(Mono.just("proxies:\n  - {name: A, type: vless, server: example.com, port: 443}"));
            assertThatThrownBy(() -> service.subscribe(new ProxyChannelDtos.ImportSubscription("full", "https://full.example/sub", null)).block())
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("PROXY_PORT_RANGE_FULL"));
            assertThat(service.catalog().block().subscriptions()).singleElement().satisfies(subscription -> {
                assertThat(subscription.status()).isEqualTo("ERROR");
                assertThat(subscription.nodeCount()).isZero();
            });
        } finally {
            db.sql("DELETE FROM proxy_channels WHERE name LIKE 'fill-%'").fetch().rowsUpdated().block();
        }
    }

    @Test void concurrentCreationsNeverShareOrSkipAPort() {
        Flux.range(0, 24).flatMap(i -> Mono.fromCallable(() -> createVless("n" + i)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()), 24)
            .collectList().block(Duration.ofSeconds(30));
        assertThat(ports()).containsExactlyElementsOf(java.util.stream.IntStream.range(20000, 20024).boxed().toList());
    }

    @Test void rotatingCredentialsDoNotExhaustThePortsBecauseGoneNodesReleaseThem() {
        String url = "https://rotate.example/" + UUID.randomUUID();
        var subscription = new UUID[1];
        for (int version = 0; version < 6; version++) {
            when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: A, type: vless, server: example.com, port: 443, uuid: v" + version + "}"));
            if (version == 0) subscription[0] = service.subscribe(new ProxyChannelDtos.ImportSubscription("rotating", url, null)).block().subscriptionId();
            else service.sync(subscription[0]).block();
        }
        // Six generations of the node exist (the old ones stay as missing rows), but only the current one holds a port.
        assertThat(db.sql("SELECT count(*) AS n FROM proxy_channels WHERE subscription_id=:id").bind("id", subscription[0])
            .map((r, m) -> r.get("n", Long.class)).one().block()).isEqualTo(6);
        // The generations alternate between the two lowest ports; the pool never grows.
        assertThat(ports()).hasSize(1).allMatch(port -> port <= 20001);
        // The node that comes back after a refresh gets a port again.
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: A, type: vless, server: example.com, port: 443, uuid: v0}"));
        service.sync(subscription[0]).block();
        assertThat(ports()).hasSize(1);
        assertThat(db.sql("SELECT count(*) AS n FROM proxy_channels WHERE listener_port IS NOT NULL AND source_present")
            .map((r, m) -> r.get("n", Long.class)).one().block()).isEqualTo(1);
    }

    // ---- A4: subscriptions and names ----

    @Test void anAdministratorRenameSurvivesRefreshesWhileUntouchedNodesFollowTheSubscription() {
        String url = "https://names.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: First, type: vless, server: one.example, port: 443}\n  - {name: Second, type: vless, server: two.example, port: 443}"));
        var imported = service.subscribe(new ProxyChannelDtos.ImportSubscription("names", url, null)).block();
        var channels = service.catalog().block().channels();
        UUID first = channels.stream().filter(c -> c.name().equals("First")).findFirst().orElseThrow().channelId();
        UUID second = channels.stream().filter(c -> c.name().equals("Second")).findFirst().orElseThrow().channelId();
        service.update(first, new ProxyChannelDtos.UpdateChannel("Tokyo exit", null)).block();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: First v2, type: vless, server: one.example, port: 443}\n  - {name: Second v2, type: vless, server: two.example, port: 443}"));
        service.sync(imported.subscriptionId()).block();
        assertThat(service.channel(first).block().name()).isEqualTo("Tokyo exit");
        assertThat(service.channel(second).block().name()).isEqualTo("Second v2");
        assertThat(service.channel(first).block().subscriptionName()).isEqualTo("names");
    }

    @Test void subscriptionsCanBeRenamedAndDeletedUnlessANodeIsBound() {
        String url = "https://delete.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: A, type: vless, server: one.example, port: 443}\n  - {name: B, type: socks5, server: two.example, port: 1080}"));
        UUID subscription = service.subscribe(new ProxyChannelDtos.ImportSubscription("old name", url, null)).block().subscriptionId();
        assertThat(service.renameSubscription(subscription, new ProxyChannelDtos.RenameSubscription("  new name ")).block().name()).isEqualTo("new name");
        assertThatThrownBy(() -> service.renameSubscription(UUID.randomUUID(), new ProxyChannelDtos.RenameSubscription("x")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        UUID node = service.catalog().block().channels().stream().filter(c -> c.name().equals("B")).findFirst().orElseThrow().channelId();
        insertAccount("bound-" + UUID.randomUUID(), node);

        assertThatThrownBy(() -> service.deleteSubscription(subscription).block()).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getCode()).isEqualTo("PROXY_NODE_IN_USE");
            assertThat(error.getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        });
        assertThat(service.catalog().block().subscriptions()).hasSize(1);
        assertThat(service.catalog().block().channels()).hasSize(2);

        db.sql("UPDATE proxy_accounts SET proxy_channel_id=NULL").fetch().rowsUpdated().block();
        clearInvocations(runtime);
        service.deleteSubscription(subscription).block();
        assertThat(service.catalog().block().subscriptions()).isEmpty();
        assertThat(service.catalog().block().channels()).isEmpty();
        verify(runtime).reconcile();
        assertThatThrownBy(() -> service.deleteSubscription(subscription).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- A5: probe results and bound accounts ----

    @Test void probeResultsAreStoredAndShownInTheCatalogAndAFailureClearsTheOldExit() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    headers(socket.getInputStream());
                    String body = "{\"ip\":\"203.0.113.9\"}";
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);
                } catch (Exception ignored) { }
            });
            props.setProbeUrl("http://probe.test/ip");
            UUID channel = service.create(new ProxyChannelDtos.CreateChannel("probe", "http://127.0.0.1:" + proxy.getLocalPort(), null)).block().channelId();
            assertThat(service.channel(channel).block().lastTestResult()).isNull();
            var result = service.test(channel).block();
            assertThat(result.success()).isTrue();
            var stored = service.catalog().block().channels().getFirst();
            assertThat(stored.lastTestResult()).isEqualTo("SUCCESS");
            assertThat(stored.lastTestExitIp()).isEqualTo("203.0.113.9");
            assertThat(stored.lastTestLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
            assertThat(stored.lastTestedAt()).isNotNull();
            assertThat(stored.lastTestErrorCode()).isNull();
            assertThat(stored.lastTestError()).isNull();
        }
        // The proxy is gone now: the failure replaces the previous result, without any transport text.
        UUID channel = service.catalog().block().channels().getFirst().channelId();
        var failed = service.test(channel).block();
        assertThat(failed.success()).isFalse();
        var stored = service.channel(channel).block();
        assertThat(stored.lastTestResult()).isEqualTo("FAILED");
        assertThat(stored.lastTestExitIp()).isNull();
        assertThat(stored.lastTestErrorCode()).isEqualTo("PROXY_CONNECT_FAILED");
        assertThat(stored.lastTestError()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED.message());
    }

    @Test void boundAccountsAreListedAndAreInTheConflictOfADelete() {
        UUID channel = createVless("bound");
        UUID other = createVless("other");
        insertAccount("b-1-" + UUID.randomUUID(), channel);
        insertAccount("b-2-" + UUID.randomUUID(), channel);
        insertAccount("b-3-" + UUID.randomUUID(), other);
        var listed = service.accounts(channel).block();
        assertThat(listed).hasSize(2);
        assertThat(listed).allSatisfy(account -> {
            assertThat(account.channelId()).isEqualTo(channel);
            assertThat(account.provider()).isEqualTo("OpenAI");
            assertThat(account.authMethod()).isEqualTo("api-key");
            assertThat(account.enabled()).isTrue();
        });
        assertThat(service.catalog().block().channels().stream().filter(c -> c.channelId().equals(channel)).findFirst().orElseThrow().boundAccounts()).isEqualTo(2);
        assertThatThrownBy(() -> service.delete(channel).block()).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getCode()).isEqualTo("PROXY_NODE_IN_USE");
            assertThat(error.getDetails()).isEqualTo(listed);
        });
        assertThatThrownBy(() -> service.accounts(UUID.randomUUID()).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void theCatalogShowsConfigIssuesMissingNodesAndTheRuntimeHealth() {
        var activatedAt = java.time.Instant.parse("2026-10-24T01:00:00Z");
        when(runtime.health()).thenReturn(new MihomoRuntime.Health(true, activatedAt, activatedAt, 3,
            EgressFailure.CONFIG_REJECTED, activatedAt));
        String url = "https://issues.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: Good, type: vless, server: one.example, port: 443}\n  - {name: Odd, type: wireguard, server: two.example, port: 51820}"));
        var imported = service.subscribe(new ProxyChannelDtos.ImportSubscription("issues", url, null)).block();
        var catalog = service.catalog().block();
        var odd = catalog.channels().stream().filter(c -> c.name().equals("Odd")).findFirst().orElseThrow();
        assertThat(odd.configIssue()).contains("Unsupported node protocol");
        assertThat(odd.sourcePresent()).isTrue();
        assertThat(odd.subscriptionName()).isEqualTo("issues");
        assertThat(portOf(odd.channelId())).isNull();
        assertThat(catalog.runtime().controllerReachable()).isTrue();
        assertThat(catalog.runtime().lastActivatedAt()).isEqualTo(activatedAt);
        assertThat(catalog.runtime().activeNodes()).isEqualTo(3);
        assertThat(catalog.runtime().lastFailureCode()).isEqualTo("CONFIG_REJECTED");
        assertThat(catalog.runtime().lastFailureMessage()).isEqualTo(EgressFailure.CONFIG_REJECTED.message());
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: Other, type: vless, server: three.example, port: 443}"));
        service.sync(imported.subscriptionId()).block();
        assertThat(service.catalog().block().channels().stream().filter(c -> c.name().equals("Good")).findFirst().orElseThrow().sourcePresent()).isFalse();
    }

    // ---- A8: plain HTTP and SOCKS5 channels do not need Mihomo ----

    @Test void anHttpChannelWorksForAccountsAndLoginsWithMihomoDisabled() throws Exception {
        assertThat(props.isMihomoEnabled()).isFalse();
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var observed = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    String connect = headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nexit-a");
                    observed.complete(connect);
                } catch (Exception error) { observed.completeExceptionally(error); }
            });
            var channel = service.create(new ProxyChannelDtos.CreateChannel("http", "http://user:pass@127.0.0.1:" + proxy.getLocalPort(), null)).block();
            assertThat(channel.route()).isEqualTo("DIRECT_PROXY");
            assertThat(channel.runtimeStatus()).isEqualTo("ACTIVE");
            service.requireUsable(channel.channelId()).block();
            String accountId = "http-" + UUID.randomUUID();
            insertAccount(accountId, channel.channelId());
            var account = new ProxyAccountEntity(); account.setAccountId(accountId);
            var transport = new AccountEgressTransport(db, routes, props, new ProxyProperties(), parser);
            var client = WebClient.builder().filter(transport.filter()).exchangeFunction(request ->
                Mono.error(new AssertionError("Must never use direct transport"))).build();
            assertThat(client.get().uri("http://target.invalid/x").attribute(AccountEgressTransport.ACCOUNT, account)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(8))).isEqualTo("exit-a");
            assertThat(observed.get(5, TimeUnit.SECONDS)).contains("CONNECT target.invalid:80", "proxy-authorization: Basic dXNlcjpwYXNz");
        }
    }

    @Test void aSocks5ChannelWorksWithMihomoDisabledAndItsProbeUsesTheSamePath() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var observed = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readUnsignedByte();
                    input.readNBytes(input.readUnsignedByte());
                    socket.getOutputStream().write(new byte[]{5, 2});
                    input.readUnsignedByte();
                    String user = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    String password = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    socket.getOutputStream().write(new byte[]{1, 0});
                    input.readNBytes(4);
                    String target = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    input.readUnsignedShort();
                    socket.getOutputStream().write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                    headers(input);
                    String body = "{\"ip\":\"198.51.100.7\"}";
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);
                    observed.complete(user + ":" + password + "@" + target);
                } catch (Throwable error) { observed.completeExceptionally(error); }
            });
            props.setProbeUrl("http://probe.test/ip");
            var channel = service.create(new ProxyChannelDtos.CreateChannel("socks", "socks5://user:pass@127.0.0.1:" + proxy.getLocalPort(), null)).block();
            assertThat(channel.route()).isEqualTo("DIRECT_PROXY");
            var probe = service.test(channel.channelId()).block();
            assertThat(probe.success()).isTrue();
            assertThat(probe.exitIp()).isEqualTo("198.51.100.7");
            assertThat(observed.get(5, TimeUnit.SECONDS)).isEqualTo("user:pass@probe.test");
        }
    }

    @Test void aVlessChannelStillNeedsMihomoAndNeverGoesDirect() {
        UUID channel = createVless("vless");
        var listed = service.channel(channel).block();
        assertThat(listed.route()).isEqualTo("MIHOMO");
        assertThat(listed.runtimeStatus()).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.requireUsable(channel).block()).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("PROXY_RUNTIME_DISABLED"));
        // The real runtime is disabled, so the node cannot be used and the request is not sent anywhere.
        var disabledRuntime = new MihomoRuntime(db, json, props);
        var strict = new ChannelRoutes(db, disabledRuntime, json, props);
        assertThatThrownBy(() -> strict.resolve(channel).block()).isInstanceOfSatisfying(EgressException.class, error -> {
            assertThat(error.failure()).isEqualTo(EgressFailure.RUNTIME_DISABLED);
            assertThat(error.requestMayHaveBeenSent()).isFalse();
        });
        var strictService = new ProxyChannelService(db, json, parser, fetcher, props, runtime, strict, transactions);
        var probe = strictService.test(channel).block();
        assertThat(probe.success()).isFalse();
        assertThat(probe.errorCode()).isEqualTo("RUNTIME_DISABLED");
        // A node with options that only Mihomo honours (here: skipping certificate checks) is not "plain" and needs it too.
        var tolerantHttps = service.create(new ProxyChannelDtos.CreateChannel("https proxy", null,
            "{name: x, type: http, server: proxy.example, port: 8443, tls: true, skip-cert-verify: true}")).block();
        assertThat(tolerantHttps.route()).isEqualTo("MIHOMO");
        assertThat(portOf(tolerantHttps.channelId())).isNotNull();
        // ...whereas an https:// proxy that is verified normally is reached directly.
        var https = service.create(new ProxyChannelDtos.CreateChannel("https", "https://user:pass@proxy.example:8443", null)).block();
        assertThat(https.route()).isEqualTo("DIRECT_PROXY");
        assertThat(portOf(https.channelId())).isNull();
    }

    // ---- helpers ----

    private UUID createVless(String name) {
        return service.create(new ProxyChannelDtos.CreateChannel(name, null,
            "{name: " + name + ", type: vless, server: " + name + ".example, port: 443}")).block().channelId();
    }

    private Integer portOf(UUID channel) {
        // A node without a port has a NULL column, which a row mapper cannot return.
        return db.sql("SELECT coalesce(listener_port,-1) AS port FROM proxy_channels WHERE channel_id=:id").bind("id", channel)
            .map((r, m) -> r.get("port", Integer.class)).one().map(port -> java.util.Optional.of(port).filter(value -> value >= 0))
            .block().orElse(null);
    }

    private List<Integer> ports() {
        return db.sql("SELECT listener_port FROM proxy_channels WHERE listener_port IS NOT NULL ORDER BY listener_port")
            .map((r, m) -> r.get("listener_port", Integer.class)).all().collectList().block();
    }

    private void insertAccount(String accountId, UUID channel) {
        db.sql("INSERT INTO proxy_accounts(account_id,access_token,provider,auth_method,proxy_channel_id) VALUES(:id,'test','OpenAI','api-key',:node)")
            .bind("id", accountId).bind("node", channel).fetch().rowsUpdated().block();
    }

    private static String headers(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 16384) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            bytes.write(next);
            if (bytes.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        throw new IOException("Oversized test headers");
    }

    private static void write(Socket socket, String value) throws IOException {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
}
