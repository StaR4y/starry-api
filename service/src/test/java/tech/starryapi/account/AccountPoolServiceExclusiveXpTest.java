package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * An account registered as the exclusive relay of another protocol (design 7.5) is out of the pools that serve untiered
 * traffic: the OpenAI-compatible pool and the owner of a response chain. Without such a relay nothing is different.
 */
class AccountPoolServiceExclusiveXpTest {

    private static final String RELAY = "relay-claude";
    private static final String GLM = "glm-1";
    private static final String COMPATIBLE_MESSAGE = "No available OpenAI-compatible upstream account";
    private static final String OWNER_MESSAGE = "The account that owns the OpenAI response is unavailable";

    // ---- nothing is configured ----

    @Test
    void withoutARelayEveryPoolSelectsAsItAlwaysDid() {
        for (ProxyAccountEntity account : oneOfEveryKind()) {
            AccountPoolService legacy = legacy(account);
            List<AccountPoolService> same = List.of(
                withExclusiveRelays(Set::of, account),
                withExclusiveRelays(() -> null, account),
                withExclusiveRelays(() -> Set.of("another-account"), account)
            );
            for (var entry : selections().entrySet()) {
                String expected = outcome(entry.getValue().apply(legacy));
                for (AccountPoolService service : same) {
                    assertThat(outcome(entry.getValue().apply(service)))
                        .as("%s via %s", account.getAccountId(), entry.getKey())
                        .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void thePoolsThatNeedNoExclusionNeverAskForTheConfiguration() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<Set<String>> counting = () -> {
            asked.incrementAndGet();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TIER_CONFIG_UNAVAILABLE", "not loaded");
        };
        for (ProxyAccountEntity account : oneOfEveryKind()) {
            AccountPoolService service = withExclusiveRelays(counting, account);
            for (var entry : selections().entrySet()) {
                if (entry.getKey().startsWith("openai-compatible") || entry.getKey().startsWith("owner")) {
                    continue;
                }
                // These pools exclude nothing, so the configuration is not read: they answer as they always did, even
                // while it has never been loaded.
                assertThat(outcome(entry.getValue().apply(service))).as(entry.getKey())
                    .isEqualTo(outcome(entry.getValue().apply(legacy(account))));
            }
        }
        assertThat(asked.get()).isZero();
    }

    // ---- the compatible pool ----

    @Test
    void anExclusiveRelayIsNotInTheCompatiblePool() {
        for (BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> select : compatibleSelections()) {
            AccountPoolService service = withExclusiveRelays(() -> Set.of(RELAY), relay(RELAY), glm(GLM));

            // The pool takes its accounts in turn, so a relay that was still in it would be chosen every other time.
            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                chosen.add(select.apply(service, null).block().getAccountId());
            }
            assertThat(chosen).containsOnly(GLM);
        }
    }

    @Test
    void theSameTwoAccountsBothServeWhenTheRelayIsNotExclusive() {
        for (BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> select : compatibleSelections()) {
            AccountPoolService service = withExclusiveRelays(Set::of, relay(RELAY), glm(GLM));

            Set<String> chosen = new HashSet<>();
            for (int i = 0; i < 8; i++) {
                chosen.add(select.apply(service, null).block().getAccountId());
            }
            assertThat(chosen).containsExactlyInAnyOrder(RELAY, GLM);
        }
    }

    @Test
    void aRelayThatIsRegisteredAsNotExclusiveStillServesCompatibleTraffic() {
        AccountPoolService exclusive = withExclusiveRelays(exclusiveRelaysOf(relayMember(true)), relay(RELAY));
        AccountPoolService shared = withExclusiveRelays(exclusiveRelaysOf(relayMember(false)), relay(RELAY));

        for (BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> select : compatibleSelections()) {
            assertThat(outcome(select.apply(exclusive, null))).isEqualTo("error NO_AVAILABLE_ACCOUNT " + COMPATIBLE_MESSAGE);
            assertThat(outcome(select.apply(shared, null))).isEqualTo(RELAY);
        }
    }

    @Test
    void whenTheRelayIsTheOnlyCompatibleAccountThePoolIsEmptyWithTheSameAnswerAsEver() {
        AccountPoolService service = withExclusiveRelays(() -> Set.of(RELAY), relay(RELAY));

        for (BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> select : compatibleSelections()) {
            assertThat(outcome(select.apply(service, null))).isEqualTo("error NO_AVAILABLE_ACCOUNT " + COMPATIBLE_MESSAGE);
        }
    }

    @Test
    void theAccountsARequestAlreadyTriedStayOutOfThePoolAsWell() {
        AccountPoolService service = withExclusiveRelays(() -> Set.of(RELAY), relay(RELAY), glm(GLM), glm("glm-2"));
        BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> excluding =
            (pool, tried) -> pool.selectOpenAiCompatibleAccountForPool("hint", tried, null);

        assertThat(outcome(excluding.apply(service, List.of(GLM)))).isEqualTo("glm-2");
        assertThat(outcome(excluding.apply(service, List.of("glm-2")))).isEqualTo(GLM);
        assertThat(outcome(excluding.apply(service, List.of(GLM, "glm-2"))))
            .isEqualTo("error NO_AVAILABLE_ACCOUNT " + COMPATIBLE_MESSAGE);
        // The list the caller passed is not changed by adding the relays to it.
        List<String> tried = new ArrayList<>(List.of(GLM));
        excluding.apply(service, tried).block();
        assertThat(tried).containsExactly(GLM);
    }

    @Test
    void theExclusionFollowsTheConfigurationWithoutARestart() {
        AtomicReference<Set<String>> configured = new AtomicReference<>(Set.of());
        AccountPoolService service = withExclusiveRelays(configured::get, relay(RELAY));

        assertThat(outcome(service.selectOpenAiCompatibleAccountForPool("hint", null))).isEqualTo(RELAY);
        configured.set(Set.of(RELAY));
        assertThat(outcome(service.selectOpenAiCompatibleAccountForPool("hint", null)))
            .isEqualTo("error NO_AVAILABLE_ACCOUNT " + COMPATIBLE_MESSAGE);
        configured.set(Set.of());
        assertThat(outcome(service.selectOpenAiCompatibleAccountForPool("hint", null))).isEqualTo(RELAY);
    }

    // ---- the owner of a response chain ----

    @Test
    void anExclusiveRelayDoesNotOwnAResponseChain() {
        AccountPoolService service = withExclusiveRelays(() -> Set.of(RELAY), relay(RELAY), glm(GLM), official("official-1"));

        assertThat(outcome(service.selectOpenAiAccountByIdForPool(RELAY, null))).isEqualTo("error NO_AVAILABLE_ACCOUNT " + OWNER_MESSAGE);
        // The owner of any other chain is found as before: compatible keys that are no relay, official keys.
        assertThat(outcome(service.selectOpenAiAccountByIdForPool(GLM, null))).isEqualTo(GLM);
        assertThat(outcome(service.selectOpenAiAccountByIdForPool("official-1", null))).isEqualTo("official-1");
    }

    @Test
    void anOwnerWithoutAnIdIsAskedForAsItAlwaysWasWhateverTheSetOfRelaysIs() {
        // Set.of cannot be asked whether it holds null.
        AccountPoolService service = withExclusiveRelays(() -> Set.of(RELAY), glm(GLM));

        assertThat(outcome(service.selectOpenAiAccountByIdForPool(null, null)))
            .isEqualTo(outcome(legacy(glm(GLM)).selectOpenAiAccountByIdForPool(null, null)));
    }

    @Test
    void aRelayThatIsNotExclusiveCanStillOwnAResponseChain() {
        AccountPoolService service = withExclusiveRelays(Set::of, relay(RELAY));

        assertThat(outcome(service.selectOpenAiAccountByIdForPool(RELAY, null))).isEqualTo(RELAY);
    }

    // ---- the configuration cannot be read ----

    @Test
    void whileTheConfigurationIsUnknownTheseTwoPoolsFailAsAnAnswerAndNotAsAnException() {
        Supplier<Set<String>> unknown = () -> {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TIER_CONFIG_UNAVAILABLE", "not loaded");
        };
        AccountPoolService service = withExclusiveRelays(unknown, relay(RELAY), glm(GLM));

        for (BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> select : compatibleSelections()) {
            Mono<ProxyAccountEntity> selection = select.apply(service, null);
            assertThatThrownBy(selection::block).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE"));
        }
        assertThatCode(() -> service.selectOpenAiAccountByIdForPool(GLM, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.selectOpenAiAccountByIdForPool(GLM, null).block()).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE"));
    }

    // ---- the registry ----

    @Test
    void theRegistryTheContainerInjectsDecidesWhichAccountsAreLeftOut() {
        SourceTierRegistry registry = mock(SourceTierRegistry.class);
        when(registry.exclusiveXpAccountIds()).thenReturn(Set.of(RELAY));
        ProxyAccountRepository repository = repository(relay(RELAY), glm(GLM));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties(), null,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), null, registry);
        service.refreshCache().block();

        assertThat(service.exclusiveRelayAccountIds()).containsExactly(RELAY);
        assertThat(outcome(service.selectOpenAiCompatibleAccountForPool("hint", null))).isEqualTo(GLM);
        assertThat(outcome(service.selectOpenAiCompatibleAccountForPool("hint", List.of(GLM), null)))
            .isEqualTo("error NO_AVAILABLE_ACCOUNT " + COMPATIBLE_MESSAGE);
    }

    @Test
    void aPoolBuiltWithTheOldConstructorsHasNoExclusiveRelays() {
        ProxyProperties properties = new ProxyProperties();
        ProxyAccountRepository repository = repository(relay(RELAY));
        List<AccountPoolService> services = List.of(
            new AccountPoolService(repository, null, properties),
            new AccountPoolService(repository, null, properties, null),
            new AccountPoolService(repository, null, properties, null, SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()),
            new AccountPoolService(repository, null, properties, null, SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), null)
        );

        for (AccountPoolService service : services) {
            service.refreshCache().block();
            assertThat(service.exclusiveRelayAccountIds()).isEmpty();
            assertThat(service.isExclusiveRelay(relay(RELAY))).isFalse();
            assertThat(outcome(service.selectOpenAiCompatibleAccountForPool("hint", null))).isEqualTo(RELAY);
        }
    }

    @Test
    void anAccountIsAnExclusiveRelayByItsId() {
        AccountPoolService service = withExclusiveRelays(() -> Set.of(RELAY));

        assertThat(service.isExclusiveRelay(relay(RELAY))).isTrue();
        assertThat(service.isExclusiveRelay(glm(GLM))).isFalse();
        assertThat(service.isExclusiveRelay(account(null, "OpenAI-compatible", "api-key", "https://relay.example/v1"))).isFalse();
        assertThat(service.isExclusiveRelay(null)).isFalse();
    }

    // ---- fixtures ----

    /** Every select that serves a pool of untiered traffic by an account id or a cell, plain and with accounts excluded. */
    private Map<String, Function<AccountPoolService, Mono<ProxyAccountEntity>>> selections() {
        Map<String, Function<AccountPoolService, Mono<ProxyAccountEntity>>> all = new LinkedHashMap<>();
        all.put("kiro", s -> s.selectKiroAccountForPool("hint", null));
        all.put("kiro excluding", s -> s.selectKiroAccountForPool("hint", List.of("other"), null));
        all.put("openai", s -> s.selectOpenAiAccountForPool("hint", null));
        all.put("openai excluding", s -> s.selectOpenAiAccountForPool("hint", List.of("other"), null));
        all.put("openai-key", s -> s.selectOpenAiApiKeyAccountForPool("hint", null));
        all.put("openai-key excluding", s -> s.selectOpenAiApiKeyAccountForPool("hint", List.of("other"), null));
        all.put("openai-compatible", s -> s.selectOpenAiCompatibleAccountForPool("hint", null));
        all.put("openai-compatible excluding", s -> s.selectOpenAiCompatibleAccountForPool("hint", List.of("other"), null));
        all.put("openai-compatible excluding all", s -> s.selectOpenAiCompatibleAccountForPool("hint", List.of(RELAY, GLM, "other"), null));
        all.put("anthropic", s -> s.selectAnthropicAccountForPool("hint", null));
        all.put("anthropic excluding", s -> s.selectAnthropicAccountForPool("hint", List.of("other"), null));
        all.put("codex", s -> s.selectCodexOpenAiAccountForPool("hint", null));
        all.put("codex excluding", s -> s.selectCodexOpenAiAccountForPool("hint", List.of("other"), null));
        all.put("deepseek", s -> s.selectDeepSeekOpenAiAccountForPool("hint", null));
        all.put("deepseek excluding", s -> s.selectDeepSeekOpenAiAccountForPool("hint", List.of("other"), null));
        for (ProxyAccountEntity account : oneOfEveryKind()) {
            all.put("owner " + account.getAccountId(), s -> s.selectOpenAiAccountByIdForPool(account.getAccountId(), null));
        }
        return all;
    }

    private List<BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>>> compatibleSelections() {
        return List.of(
            (service, tried) -> service.selectOpenAiCompatibleAccountForPool("hint", null),
            (service, tried) -> service.selectOpenAiCompatibleAccountForPool("hint", tried, null)
        );
    }

    /** What a selection came to: the id of the account, or the error code and message. */
    private static String outcome(Mono<ProxyAccountEntity> selection) {
        try {
            ProxyAccountEntity account = selection.block();
            return account == null ? "empty" : account.getAccountId();
        } catch (ApiException error) {
            return "error " + error.getCode() + " " + error.getMessage();
        }
    }

    private AccountPoolService legacy(ProxyAccountEntity... accounts) {
        AccountPoolService service = new AccountPoolService(repository(accounts), null, new ProxyProperties());
        service.refreshCache().block();
        return service;
    }

    private AccountPoolService withExclusiveRelays(Supplier<Set<String>> exclusive, ProxyAccountEntity... accounts) {
        AccountPoolService service = new AccountPoolService(repository(accounts), null, new ProxyProperties(), null,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), null, exclusive);
        service.refreshCache().block();
        return service;
    }

    private ProxyAccountRepository repository(ProxyAccountEntity... accounts) {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(accounts));
        return repository;
    }

    /** The set the registry derives from a configuration that has one relay member. */
    private Supplier<Set<String>> exclusiveRelaysOf(Member member) {
        VendorConfig vendor = new VendorConfig("anthropic", true, false, false, true, 16, true, 1L);
        Tier tier = new Tier("anthropic", "relay", 1, "relay", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true, true, null);
        SourceTierSnapshot snapshot = SourceTierSnapshot.of(1L, List.of(vendor), List.of(tier), List.of(member), List.of(), List.of());
        return snapshot::exclusiveXpAccountIds;
    }

    private static Member relayMember(boolean exclusive) {
        return new Member("anthropic", RELAY, "relay", "OPENAI_CHAT", exclusive, false, false, false, false, true, "NONE", 540, true, true);
    }

    /** One account of every kind a select method tells apart, among them the relay and a GLM key. */
    private List<ProxyAccountEntity> oneOfEveryKind() {
        return List.of(
            official("official-1"),
            relay(RELAY),
            glm(GLM),
            account("deepseek-1", "DeepSeek", "api-key", null),
            account("codex-1", "OpenAI", "codex", null),
            account("anthropic-1", "Anthropic", "api-key", null),
            account("kiro-1", "BuilderId", "idc", null),
            account("google-1", "Google", "api-key", null)
        );
    }

    private ProxyAccountEntity official(String accountId) {
        return account(accountId, "OpenAI", "api-key", null);
    }

    private ProxyAccountEntity relay(String accountId) {
        return account(accountId, "OpenAI-compatible", "api-key", "https://relay.example/v1");
    }

    private ProxyAccountEntity glm(String accountId) {
        return account(accountId, "GLM", "api-key", "https://open.bigmodel.cn/api/paas/v4");
    }

    private ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setEnabled(true);
        return account;
    }
}
