package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class PoolCellTest {

    /**
     * The inline predicates AccountPoolService passed to selectAccount before the cells existed, as they were written
     * there (git dbcc0c0). XP_OPENAI_CHAT is new: it never had an inline predicate.
     */
    private static final Map<PoolCell, Predicate<ProxyAccountEntity>> INLINE_BEFORE = inlineBefore();

    private static Map<PoolCell, Predicate<ProxyAccountEntity>> inlineBefore() {
        Map<PoolCell, Predicate<ProxyAccountEntity>> before = new EnumMap<>(PoolCell.class);
        before.put(PoolCell.DEEPSEEK, AccountPlatforms::isDeepSeek);
        before.put(PoolCell.OPENAI_COMPATIBLE, AccountPlatforms::isOpenAiCompatibleApiKey);
        before.put(PoolCell.CODEX, AccountPlatforms::isCodex);
        before.put(PoolCell.OFFICIAL_OPENAI, AccountPlatforms::isOfficialOpenAiApiKey);
        before.put(
            PoolCell.OFFICIAL_OR_CODEX,
            account -> AccountPlatforms.isOfficialOpenAiApiKey(account) || AccountPlatforms.isCodex(account)
        );
        before.put(PoolCell.ANTHROPIC_KEY, AccountPlatforms::isAnthropicApiKey);
        before.put(PoolCell.KIRO_COMPATIBLE, AccountPlatforms::isKiroCompatible);
        return before;
    }

    /** The overloads that took excluded account ids appended this to the same predicate; AccountPoolService.isExcluded as it was. */
    private static Predicate<ProxyAccountEntity> inlineBeforeExcluding(PoolCell cell, Collection<String> excludedAccountIds) {
        Predicate<ProxyAccountEntity> plain = INLINE_BEFORE.get(cell);
        return account -> plain.test(account)
            && !(account != null
                && account.getAccountId() != null
                && excludedAccountIds != null
                && excludedAccountIds.contains(account.getAccountId()));
    }

    private record Sample(String name, ProxyAccountEntity account, Set<PoolCell> cells) {
    }

    @Test
    void everyCellButTheNewRelayCellHasAnInlinePredicateItReplaced() {
        assertThat(PoolCell.values()).hasSize(8);
        assertThat(INLINE_BEFORE.keySet()).containsExactlyInAnyOrder(
            Arrays.stream(PoolCell.values()).filter(cell -> cell != PoolCell.XP_OPENAI_CHAT).toArray(PoolCell[]::new));
    }

    @Test
    void accountsBelongToTheCellsOfTheirKind() {
        for (Sample sample : samples()) {
            for (PoolCell cell : PoolCell.values()) {
                assertThat(cell.predicate().test(sample.account()))
                    .as("%s in %s", sample.name(), cell)
                    .isEqualTo(sample.cells().contains(cell));
            }
        }
    }

    @Test
    void predicatesAgreeWithTheInlineOnesTheyReplaced() {
        for (Sample sample : samples()) {
            String id = sample.account().getAccountId();
            for (Map.Entry<PoolCell, Predicate<ProxyAccountEntity>> before : INLINE_BEFORE.entrySet()) {
                PoolCell cell = before.getKey();
                assertThat(cell.predicate().test(sample.account()))
                    .as("%s in %s", sample.name(), cell)
                    .isEqualTo(before.getValue().test(sample.account()));
                // The excluding forms, with the exclusion lists the select methods are called with.
                for (Collection<String> excluded : Arrays.<Collection<String>>asList(null, Set.of(), List.of("other"), List.of("other", id), Set.of(id))) {
                    assertThat(cell.predicate(excluded).test(sample.account()))
                        .as("%s in %s excluding %s", sample.name(), cell, excluded)
                        .isEqualTo(inlineBeforeExcluding(cell, excluded).test(sample.account()));
                }
            }
        }
    }

    @Test
    void nativeCellsDoNotOverlap() {
        // The cells that feed different upstream clients never overlap, whatever the account is; the OpenAI
        // ones are left out because OFFICIAL_OR_CODEX is the union of two others.
        List<PoolCell> disjoint = List.of(
            PoolCell.DEEPSEEK,
            PoolCell.OPENAI_COMPATIBLE,
            PoolCell.OFFICIAL_OR_CODEX,
            PoolCell.ANTHROPIC_KEY,
            PoolCell.KIRO_COMPATIBLE
        );
        for (Sample sample : samples()) {
            long hits = disjoint.stream().filter(cell -> cell.predicate().test(sample.account())).count();
            assertThat(hits).as(sample.name()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void excludedAccountsLeaveTheCell() {
        for (Sample sample : samples()) {
            String id = sample.account().getAccountId();
            for (PoolCell cell : PoolCell.values()) {
                boolean member = sample.cells().contains(cell);
                assertThat(cell.predicate(Set.of(id)).test(sample.account())).as("%s excluded from %s", sample.name(), cell)
                    .isFalse();
                assertThat(cell.predicate(List.of("other", id)).test(sample.account())).as("%s in a list", sample.name())
                    .isFalse();
                assertThat(cell.predicate(Set.of("other")).test(sample.account())).as("%s other", sample.name())
                    .isEqualTo(member);
                assertThat(cell.predicate(Set.of()).test(sample.account())).as("%s empty", sample.name())
                    .isEqualTo(member);
                assertThat(cell.predicate(null).test(sample.account())).as("%s null", sample.name())
                    .isEqualTo(member);
            }
        }
    }

    @Test
    void anAccountWithoutAnIdIsNeverExcluded() {
        ProxyAccountEntity account = account(null, "OpenAI", "api-key", null);
        Collection<String> withNull = Arrays.asList("a", null);

        assertThat(PoolCell.OFFICIAL_OPENAI.predicate(withNull).test(account)).isTrue();
        assertThat(PoolCell.OFFICIAL_OPENAI.predicate(Set.of("a")).test(account)).isTrue();
    }

    @Test
    void nullAccountIsInNoCell() {
        for (PoolCell cell : PoolCell.values()) {
            assertThat(cell.predicate().test(null)).as(cell.name()).isFalse();
            assertThat(cell.predicate(Set.of("a")).test(null)).as(cell.name()).isFalse();
        }
    }

    @Test
    void selectMethodsServeExactlyTheirCell() {
        Map<String, Wiring> wirings = wirings();
        for (Sample sample : samples()) {
            ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
            when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(sample.account()));
            AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
            service.refreshCache().block();
            String id = sample.account().getAccountId();

            for (Map.Entry<String, Wiring> entry : wirings.entrySet()) {
                Wiring wiring = entry.getValue();
                String label = sample.name() + " via " + entry.getKey();
                if (sample.cells().contains(wiring.cell())) {
                    assertThat(wiring.plain().apply(service).block()).as(label).extracting(ProxyAccountEntity::getAccountId)
                        .isEqualTo(id);
                    assertThat(wiring.excluding().apply(service, List.of("other")).block()).as(label)
                        .extracting(ProxyAccountEntity::getAccountId)
                        .isEqualTo(id);
                } else {
                    assertNoAccount(wiring.plain().apply(service), wiring.message(), label);
                    assertNoAccount(wiring.excluding().apply(service, List.of("other")), wiring.message(), label);
                }
                assertNoAccount(wiring.excluding().apply(service, List.of(id)), wiring.message(), label + " excluded");
            }
        }
    }

    private void assertNoAccount(Mono<ProxyAccountEntity> selection, String message, String label) {
        assertThatThrownBy(selection::block)
            .as(label)
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
                assertThat(error.getMessage()).isEqualTo(message);
            });
    }

    private record Wiring(
        PoolCell cell,
        String message,
        Function<AccountPoolService, Mono<ProxyAccountEntity>> plain,
        BiFunction<AccountPoolService, Collection<String>, Mono<ProxyAccountEntity>> excluding
    ) {
    }

    /** Every select*ForPool method that is made of a cell, with the error message it has always had. */
    private Map<String, Wiring> wirings() {
        return Map.of(
            "kiro", new Wiring(PoolCell.KIRO_COMPATIBLE, "No available Kiro upstream account",
                service -> service.selectKiroAccountForPool("hint", null),
                (service, excluded) -> service.selectKiroAccountForPool("hint", excluded, null)),
            "openai", new Wiring(PoolCell.OFFICIAL_OR_CODEX, "No available OpenAI upstream account",
                service -> service.selectOpenAiAccountForPool("hint", null),
                (service, excluded) -> service.selectOpenAiAccountForPool("hint", excluded, null)),
            "openai-key", new Wiring(PoolCell.OFFICIAL_OPENAI, "No available OpenAI upstream account",
                service -> service.selectOpenAiApiKeyAccountForPool("hint", null),
                (service, excluded) -> service.selectOpenAiApiKeyAccountForPool("hint", excluded, null)),
            "openai-compatible", new Wiring(PoolCell.OPENAI_COMPATIBLE,
                "No available OpenAI-compatible upstream account",
                service -> service.selectOpenAiCompatibleAccountForPool("hint", null),
                (service, excluded) -> service.selectOpenAiCompatibleAccountForPool("hint", excluded, null)),
            "anthropic", new Wiring(PoolCell.ANTHROPIC_KEY, "No available Anthropic upstream account",
                service -> service.selectAnthropicAccountForPool("hint", null),
                (service, excluded) -> service.selectAnthropicAccountForPool("hint", excluded, null)),
            "codex", new Wiring(PoolCell.CODEX, "No available Codex upstream account",
                service -> service.selectCodexOpenAiAccountForPool("hint", null),
                (service, excluded) -> service.selectCodexOpenAiAccountForPool("hint", excluded, null)),
            "deepseek", new Wiring(PoolCell.DEEPSEEK, "No available DeepSeek upstream account",
                service -> service.selectDeepSeekOpenAiAccountForPool("hint", null),
                (service, excluded) -> service.selectDeepSeekOpenAiAccountForPool("hint", excluded, null))
        );
    }

    /** One account of every kind that routing tells apart, with the cells it belongs to. */
    private List<Sample> samples() {
        return List.of(
            sample("official OpenAI key, default URL", account("official-default", "OpenAI", "api-key", null),
                PoolCell.OFFICIAL_OPENAI, PoolCell.OFFICIAL_OR_CODEX),
            sample("official OpenAI key, explicit URL",
                account("official-url", "OpenAI", "api-key", "https://api.openai.com/v1"),
                PoolCell.OFFICIAL_OPENAI, PoolCell.OFFICIAL_OR_CODEX),
            sample("API key without a provider", account("official-blank", null, "api-key", null),
                PoolCell.OFFICIAL_OPENAI, PoolCell.OFFICIAL_OR_CODEX),
            sample("OpenAI-compatible relay",
                account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1"),
                PoolCell.OPENAI_COMPATIBLE, PoolCell.XP_OPENAI_CHAT),
            sample("legacy openaicompatibility provider",
                account("legacy-relay", "openaicompatibility", "api-key", "https://relay.example/v1"),
                PoolCell.OPENAI_COMPATIBLE, PoolCell.XP_OPENAI_CHAT),
            sample("OpenAI provider on a relay URL",
                account("openai-on-relay", "OpenAI", "api-key", "https://relay.example/v1"),
                PoolCell.OPENAI_COMPATIBLE, PoolCell.XP_OPENAI_CHAT),
            sample("custom catalog vendor",
                account("openrouter", "OpenRouter", "api-key", "https://openrouter.ai/api/v1"),
                PoolCell.OPENAI_COMPATIBLE, PoolCell.XP_OPENAI_CHAT),
            sample("DeepSeek provider", account("deepseek", "DeepSeek", "api-key", null), PoolCell.DEEPSEEK),
            sample("legacy compatible account on the DeepSeek host",
                account("legacy-deepseek", "OpenAI-compatible", "api-key", "https://api.deepseek.com/v1"),
                PoolCell.DEEPSEEK),
            sample("Codex by auth method", account("codex-auth", "OpenAI", "codex", null),
                PoolCell.CODEX, PoolCell.OFFICIAL_OR_CODEX),
            sample("Codex by provider", account("codex-provider", "Codex", null, null),
                PoolCell.CODEX, PoolCell.OFFICIAL_OR_CODEX),
            sample("official Anthropic key", account("anthropic-official", "Anthropic", "api-key", null),
                PoolCell.ANTHROPIC_KEY),
            sample("third-party Anthropic key",
                account("anthropic-gateway", "Anthropic", "api-key", "https://claude-gateway.example/v1"),
                PoolCell.ANTHROPIC_KEY),
            sample("Anthropic key by the claude alias", account("claude-alias", "claude", "apikey", null),
                PoolCell.ANTHROPIC_KEY),
            sample("Claude OAuth", account("claude-oauth", "Anthropic", "oauth", null), PoolCell.KIRO_COMPATIBLE),
            sample("Kiro social login", account("kiro-social", "Google", "social", null), PoolCell.KIRO_COMPATIBLE),
            sample("Kiro BuilderId", account("kiro-builder", "BuilderId", "idc", null), PoolCell.KIRO_COMPATIBLE),
            sample("account with no provider or auth method", account("kiro-blank", null, null, null),
                PoolCell.KIRO_COMPATIBLE),
            sample("Google API key", account("google-key", "Google", "api-key", null)),
            sample("Gemini API key alias", account("gemini-key", "gemini", "apikey", null))
        );
    }

    private Sample sample(String name, ProxyAccountEntity account, PoolCell... cells) {
        Set<PoolCell> members = cells.length == 0 ? EnumSet.noneOf(PoolCell.class) : EnumSet.copyOf(List.of(cells));
        return new Sample(name, account, members);
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
