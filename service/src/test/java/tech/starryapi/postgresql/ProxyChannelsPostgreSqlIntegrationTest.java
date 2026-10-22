package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ProxyAccountEntity;
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
        var fetcher = mock(SubscriptionFetcher.class);
        var runtime = mock(MihomoRuntime.class);
        when(runtime.reconcile()).thenReturn(Mono.empty());
        var props = new EgressProperties();
        var parser = new ClashNodes(json);
        var service = new ProxyChannelService(db, json, parser, fetcher, props, runtime, transactions);
        String url = "https://source.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: A, type: http, server: example.com, port: 8080}\n  - {name: duplicate, type: http, server: example.com, port: 8080}"));
        var imported = service.subscribe(new ProxyChannelDtos.ImportSubscription("test", url, null)).block();
        var nodes = service.catalog().block().channels().stream().filter(c -> imported.subscriptionId().equals(c.subscriptionId())).toList();
        assertThat(nodes).hasSize(1);
        UUID channel = nodes.getFirst().channelId();
        Long portSequence = db.sql("SELECT last_value FROM proxy_channels_listener_port_seq")
            .map((r,m) -> r.get("last_value", Long.class)).one().block();
        service.sync(imported.subscriptionId()).block();
        assertThat(db.sql("SELECT last_value FROM proxy_channels_listener_port_seq")
            .map((r,m) -> r.get("last_value", Long.class)).one().block()).isEqualTo(portSequence);
        String accountId = "egress-" + UUID.randomUUID();
        db.sql("INSERT INTO proxy_accounts(account_id,access_token,provider,auth_method,proxy_channel_id) VALUES(:id,'test','OpenAI','api-key',:node)")
            .bind("id", accountId).bind("node", channel).fetch().rowsUpdated().block();
        assertThatThrownBy(() -> service.delete(channel).block()).hasMessageContaining("Unbind accounts");

        when(fetcher.fetch(url)).thenReturn(Mono.just("proxies:\n  - {name: B, type: socks5, server: example.com, port: 8081}"));
        service.sync(imported.subscriptionId()).block();
        assertThat(service.channel(channel).block().runtimeStatus()).isEqualTo("ERROR");
        var actualRuntime = new MihomoRuntime(db, json, props);
        var transport = new AccountEgressTransport(db, actualRuntime, props, new ProxyProperties(), parser);
        var direct = new AtomicBoolean();
        var client = WebClient.builder().filter(transport.filter()).exchangeFunction(request -> {
            direct.set(true); return Mono.error(new AssertionError("Must never use direct transport"));
        }).build();
        var account = new ProxyAccountEntity(); account.setAccountId(accountId);
        assertThatThrownBy(() -> client.get().uri("https://target.invalid").attribute(AccountEgressTransport.ACCOUNT, account)
            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5))).isInstanceOf(EgressException.class);
        assertThat(direct).isFalse();
    }

    @Test void expandsProvidersAndKeepsLastSubscriptionAfterAnInvalidRefresh() {
        var fetcher = mock(SubscriptionFetcher.class);
        var runtime = mock(MihomoRuntime.class);
        when(runtime.reconcile()).thenReturn(Mono.empty());
        var service = new ProxyChannelService(db, json, new ClashNodes(json), fetcher, new EgressProperties(), runtime, transactions);
        String url = "https://source.example/" + UUID.randomUUID();
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxy-providers:\n  one:\n    type: http\n    url: https://provider.example/nodes"));
        when(fetcher.fetch("https://provider.example/nodes")).thenReturn(Mono.just("proxies:\n  - {name: A, type: http, server: example.com, port: 8080}"));
        var imported = service.subscribe(new ProxyChannelDtos.ImportSubscription("providers", url, null)).block();
        assertThat(imported.imported()).isEqualTo(1);
        when(fetcher.fetch(url)).thenReturn(Mono.just("proxy-providers:\n  bad: {type: file, path: /etc/passwd}"));
        assertThatThrownBy(() -> service.sync(imported.subscriptionId()).block()).hasMessageContaining("HTTP and inline");
        assertThat(service.catalog().block().channels().stream().filter(c -> imported.subscriptionId().equals(c.subscriptionId()))).hasSize(1);
    }
}
