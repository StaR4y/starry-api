package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.sourcetier.SourceTierDtos.Warning;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierValidator.Config;
import tech.starryapi.sourcetier.SourceTierValidator.MemberChanges;

/** The rules of a tier configuration, one by one. No database: the catalog is the built-in one. */
class SourceTierValidatorTest {

    private static final DefaultModelCatalogProvider CATALOG = new DefaultModelCatalogProvider();
    private static final ModelPoolKeyResolver RESOLVER = new ModelPoolKeyResolver(CATALOG);

    /** This release: nothing of the runtime is there yet. */
    private final SourceTierValidator validator = new SourceTierValidator(CATALOG, RESOLVER, new SourceTierRuntimeInfo());

    // ---- fixtures ----

    private static SourceTierDtos.Tier tier(String id, int level, String name, String... categories) {
        return new SourceTierDtos.Tier(id, level, name, null, List.of(categories), null, null, null);
    }

    private static SourceTierDtos.Tier tier(String id, int level, String name, BigDecimal factor, Boolean enabled) {
        return new SourceTierDtos.Tier(id, level, name, factor, List.of(), null, enabled, null);
    }

    private static SourceTierDtos.Member pin(String accountId, String tierId) {
        return new SourceTierDtos.Member(accountId, tierId, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SourceTierDtos.MemberModel mapping(String modelId, String upstream) {
        return new SourceTierDtos.MemberModel(modelId, upstream, 200_000, null, null, null, null);
    }

    private static SourceTierDtos.Member relay(String accountId, String tierId, SourceTierDtos.MemberModel... models) {
        return new SourceTierDtos.Member(accountId, tierId, "OPENAI_CHAT", null, null, null, null, null, null, null, null,
            false, false, List.of(models));
    }

    private static SourceTierDtos.Member relay(String accountId, String tierId, java.util.function.UnaryOperator<RelayFields> change,
                                               SourceTierDtos.MemberModel... models) {
        RelayFields fields = change.apply(new RelayFields());
        return new SourceTierDtos.Member(accountId, tierId, fields.protocol, fields.exclusive, fields.vision, fields.jsonSchema,
            fields.priorityTier, fields.cacheControlPassthrough, fields.upstreamStream, fields.reasoningMode, fields.maxDuration,
            fields.usageRead, fields.usageWrite, List.of(models));
    }

    /** The settings of a relay row, all at their defaults until a test changes one. */
    private static final class RelayFields {
        String protocol = "OPENAI_CHAT";
        Boolean exclusive, vision, jsonSchema, priorityTier, cacheControlPassthrough, upstreamStream, usageRead = false, usageWrite = false;
        String reasoningMode;
        Integer maxDuration;
    }

    private static SourceTierDtos.Gate gate(String entry, String state, String... keys) {
        return new SourceTierDtos.Gate(null, entry, state, List.of(keys));
    }

    private static SourceTierDtos.ReplaceVendor replace(
        boolean enabled, List<SourceTierDtos.Tier> tiers, List<SourceTierDtos.Member> members, List<SourceTierDtos.Gate> gates
    ) {
        return new SourceTierDtos.ReplaceVendor(0L, enabled, null, null, null, null, null, tiers, members, gates);
    }

    private static SourceTierDtos.ReplaceVendor tiersOnly(SourceTierDtos.Tier... tiers) {
        return replace(false, List.of(tiers), List.of(), List.of());
    }

    private static final List<SourceTierDtos.Tier> OWNER_TIERS = List.of(
        tier("official", 1, "Official", "OFFICIAL_API_KEY"),
        tier("kiro", 2, "Kiro proxy", "KIRO", "CLAUDE_OAUTH")
    );

    private static ProxyAccountEntity account(String id, String provider, String authMethod, String baseUrl, boolean enabled) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(id);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setEnabled(enabled);
        return account;
    }

    private static void assertRejected(ThrowingCallable call, HttpStatus status, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).contains(message);
        });
    }

    private void assertInvalid(String vendor, SourceTierDtos.ReplaceVendor request, String message) {
        assertRejected(() -> validator.normalize(vendor, request), HttpStatus.BAD_REQUEST, "SOURCE_TIER_INVALID", message);
    }

    private void assertMemberInvalid(String vendor, SourceTierDtos.ReplaceVendor request, String message) {
        assertRejected(() -> validator.normalize(vendor, request), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", message);
    }

    private SourceTierValidator withRuntime(boolean nativeTiers, boolean unify, boolean xp, PairStatus pairs) {
        return new SourceTierValidator(CATALOG, RESOLVER, new SourceTierRuntimeInfo(nativeTiers, unify, xp, pair -> pairs));
    }

    // ---- vendors ----

    @Test
    void theVendorsAreTheProvidersOfTheCatalogWellKnownOnesFirst() {
        assertThat(validator.catalogVendors().keySet()).startsWith("anthropic", "openai", "google", "deepseek");
        assertThat(validator.catalogVendors()).containsEntry("anthropic", "Anthropic").containsEntry("openai", "OpenAI");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Anthropic", "a b", "a_b", "-x", "x-", "a/b", "../x", "a..b"})
    void aVendorIdThatCannotBeAProviderSlugIsUnknown(String vendor) {
        assertRejected(() -> SourceTierValidator.vendorId(vendor), HttpStatus.BAD_REQUEST, "SOURCE_TIER_VENDOR_UNKNOWN", "Unknown vendor");
    }

    @Test
    void aVendorIdLongerThanASlugIsUnknown() {
        assertRejected(() -> SourceTierValidator.vendorId("a".repeat(64)), HttpStatus.BAD_REQUEST, "SOURCE_TIER_VENDOR_UNKNOWN", "Unknown vendor");
        assertThat(SourceTierValidator.vendorId("a".repeat(63))).hasSize(63);
        assertThat(SourceTierValidator.vendorId(" anthropic ")).isEqualTo("anthropic");
        assertThat(SourceTierValidator.vendorId("my-gateway.v2")).isEqualTo("my-gateway.v2");
    }

    @Test
    void theVersionARequestWasMadeFromIsRequired() {
        assertThat(SourceTierValidator.expectedVersion(0L)).isZero();
        assertThat(SourceTierValidator.expectedVersion(7L)).isEqualTo(7L);
        assertRejected(() -> SourceTierValidator.expectedVersion(null), HttpStatus.BAD_REQUEST, "SOURCE_TIER_INVALID", "expectedVersion");
        assertRejected(() -> SourceTierValidator.expectedVersion(-1L), HttpStatus.BAD_REQUEST, "SOURCE_TIER_INVALID", "expectedVersion");
    }

    // ---- tiers ----

    @Test
    void theOwnersAnthropicExampleIsAValidConfigurationAndKeepsWhatWasSent() {
        Config config = validator.normalize("anthropic", replace(false, OWNER_TIERS, List.of(pin("claude-1", "kiro")), List.of()));

        assertThat(config.tiers()).extracting(Tier::tierId).containsExactly("official", "kiro");
        assertThat(config.tiers().get(1).memberCategories())
            .containsExactlyInAnyOrder(AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH);
        assertThat(config.members()).singleElement().satisfies(member -> {
            assertThat(member.accountId()).isEqualTo("claude-1");
            assertThat(member.tierId()).isEqualTo("kiro");
            assertThat(member.crossProtocol()).isFalse();
        });
        assertThat(config.models()).isEmpty();
        assertThat(config.gates()).isEmpty();
    }

    @Test
    void whatIsLeftOutTakesItsDefault() {
        Config config = validator.normalize("anthropic", tiersOnly(tier("official", 1, " Official ")));

        assertThat(config.vendor().enabled()).isFalse();
        assertThat(config.vendor().unifyEndpoints()).isFalse();
        assertThat(config.vendor().publicListing()).isFalse();
        assertThat(config.vendor().stickySessions()).isTrue();
        assertThat(config.vendor().refusalCap()).isEqualTo(16);
        assertThat(config.vendor().crossTierReservedSlot()).isTrue();
        Tier tier = config.tiers().getFirst();
        assertThat(tier.vendor()).isEqualTo("anthropic");
        assertThat(tier.name()).isEqualTo("Official");
        assertThat(tier.priceFactor()).isEqualByComparingTo("1");
        assertThat(tier.memberCategories()).isEmpty();
        assertThat(tier.userSelectable()).isTrue();
        assertThat(tier.enabled()).isTrue();
        assertThat(tier.description()).isNull();
    }

    @Test
    void theTiersAreOrderedByLevelWhateverTheOrderOfTheRequest() {
        Config config = validator.normalize("anthropic", tiersOnly(tier("b", 3, "B"), tier("a", 1, "A"), tier("c", 2, "C")));

        assertThat(config.tiers()).extracting(Tier::tierId).containsExactly("a", "c", "b");
    }

    @ParameterizedTest
    @MethodSource("badTierIds")
    void aTierIdIsLowerCaseLettersDigitsAndHyphens(String id) {
        assertInvalid("anthropic", tiersOnly(tier(id, 1, "A")), "tiers[0].tierId must be lower case letters");
    }

    static Stream<String> badTierIds() {
        return Stream.of("", "Official", "-x", "a b", "a_b", "x.y", "a".repeat(33), "官方");
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "l2", "2", "12", "l10", "0"})
    void aTierIdThatASelectorWouldReadAsAnotherThingIsReserved(String id) {
        assertInvalid("anthropic", tiersOnly(tier(id, 1, "A")), "is reserved");
    }

    @Test
    void aTierIdOfThirtyTwoCharactersAndAHyphenInsideAreFine() {
        assertThat(validator.normalize("anthropic", tiersOnly(tier("a".repeat(32), 1, "A"))).tiers()).hasSize(1);
        assertThat(validator.normalize("anthropic", tiersOnly(tier("openai-relay", 1, "A"))).tiers()).hasSize(1);
        assertThat(validator.normalize("anthropic", tiersOnly(tier("2fast", 1, "A"))).tiers()).hasSize(1);
        assertThat(validator.normalize("anthropic", tiersOnly(tier("l2x", 1, "A"))).tiers()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 10, 100})
    void aLevelIsBetweenOneAndNine(int level) {
        assertInvalid("anthropic", tiersOnly(tier("a", level, "A")), "tiers[0].level must be between 1 and 9");
    }

    @Test
    void aLevelIsRequired() {
        assertInvalid("anthropic", tiersOnly(new SourceTierDtos.Tier("a", null, "A", null, null, null, null, null)),
            "tiers[0].level must be between 1 and 9");
    }

    @Test
    void levelsAreNotRequiredToBeContiguous() {
        assertThat(validator.normalize("anthropic", tiersOnly(tier("a", 1, "A"), tier("b", 5, "B"))).tiers()).hasSize(2);
    }

    @Test
    void twoTiersCannotShareALevelAnIdOrAName() {
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A"), tier("b", 1, "B")), "are both level 1");
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A"), tier("a", 2, "B")), "Tier id a is used twice");
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "Same"), tier("b", 2, "Same")), "are both named Same");
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "Same"), tier("b", 2, " Same ")), "are both named Same");
    }

    @Test
    void aVendorHasAtMostNineTiers() {
        List<SourceTierDtos.Tier> ten = IntStream.rangeClosed(1, 10)
            .mapToObj(level -> tier("t" + level, Math.min(level, 9), "T" + level)).toList();

        assertInvalid("anthropic", replace(false, ten, List.of(), List.of()), "at most 9 tiers");
    }

    @Test
    void aNameIsNotBlankAtMostThirtyTwoCharactersAndWithoutControlCharacters() {
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "  ")), "tiers[0].name must not be blank");
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "x".repeat(33))), "tiers[0].name must be at most 32 characters");
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "line\nbreak")), "control characters");
        assertInvalid("anthropic", tiersOnly(new SourceTierDtos.Tier("a", 1, null, null, null, null, null, null)), "must not be blank");
        // Characters are counted, not bytes or UTF-16 units: 32 Chinese characters fit.
        assertThat(validator.normalize("anthropic", tiersOnly(tier("a", 1, "号".repeat(32)))).tiers()).hasSize(1);
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "号".repeat(33))), "at most 32 characters");
        assertThat(validator.normalize("anthropic", tiersOnly(tier("a", 1, "😀".repeat(32)))).tiers()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1000.000001", "1001", "0.0000001", "1.1234567"})
    void aPriceFactorIsAboveZeroAtMostAThousandWithSixDecimals(String factor) {
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A", new BigDecimal(factor), null)), "tiers[0].priceFactor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "0.6", "0.000001", "1000", "1000.000000", "2.500000", "0.1000000"})
    void thePriceFactorsThatFit(String factor) {
        Config config = validator.normalize("anthropic", tiersOnly(tier("a", 1, "A", new BigDecimal(factor), null)));

        assertThat(config.tiers().getFirst().priceFactor()).isEqualByComparingTo(factor);
        assertThat(config.tiers().getFirst().priceFactor().scale()).isEqualTo(6);
    }

    @Test
    void aCategoryMustBeOneTheClassifierKnows() {
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A", "OFFICIAL")), "unknown category \"OFFICIAL\"");
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A", "official_api_key")), "unknown category");
    }

    @Test
    void aCategoryBelongsToOneTierOfAVendor() {
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A", "KIRO"), tier("b", 2, "B", "KIRO", "CODEX")),
            "Category KIRO is listed by tiers a and b");
        // Even if one of the two is switched off: it would claim the category again when switched on.
        assertInvalid("anthropic", tiersOnly(tier("a", 1, "A", "KIRO"), new SourceTierDtos.Tier("b", 2, "B", null,
            List.of("KIRO"), null, false, null)), "Category KIRO is listed");
        // The same category listed twice by one tier is one claim.
        assertThat(validator.normalize("anthropic", tiersOnly(tier("a", 1, "A", "KIRO", "KIRO"))).tiers().getFirst().memberCategories())
            .containsExactly(AccountAllocationCategory.KIRO);
    }

    @Test
    void aDescriptionIsAtMost256CharactersAndBlankMeansNone() {
        assertThat(validator.normalize("anthropic", tiersOnly(new SourceTierDtos.Tier("a", 1, "A", null, null, null, null, "  ")))
            .tiers().getFirst().description()).isNull();
        assertThat(validator.normalize("anthropic", tiersOnly(new SourceTierDtos.Tier("a", 1, "A", null, null, null, null, " first ")))
            .tiers().getFirst().description()).isEqualTo("first");
        assertInvalid("anthropic", tiersOnly(new SourceTierDtos.Tier("a", 1, "A", null, null, null, null, "x".repeat(257))),
            "description must be at most 256");
    }

    // ---- the vendor ----

    @Test
    void aVendorCanOnlyBeEnabledWithAnEnabledTier() {
        assertInvalid("anthropic", replace(true, List.of(), List.of(), List.of()), "without an enabled tier");
        assertInvalid("anthropic", replace(true, List.of(tier("a", 1, "A", BigDecimal.ONE, false)), List.of(), List.of()),
            "without an enabled tier");
        assertThat(validator.normalize("anthropic", replace(true, List.of(tier("a", 1, "A")), List.of(), List.of()))
            .vendor().enabled()).isTrue();
        // A vendor that is off may have nothing at all: that is how a configuration is emptied.
        assertThat(validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of())).tiers()).isEmpty();
    }

    @Test
    void theRefusalCapIsBetweenFourAndSixtyFour() {
        for (int cap : new int[] {3, 65, 0, -1}) {
            assertInvalid("anthropic", new SourceTierDtos.ReplaceVendor(0L, false, null, null, null, cap, null, null, null, null),
                "refusalCap must be between 4 and 64");
        }
        for (int cap : new int[] {4, 16, 64}) {
            assertThat(validator.normalize("anthropic", new SourceTierDtos.ReplaceVendor(0L, false, null, null, null, cap, null, null,
                null, null)).vendor().refusalCap()).isEqualTo(cap);
        }
    }

    @Test
    void theSwitchesAreKept() {
        Config config = validator.normalize("anthropic", new SourceTierDtos.ReplaceVendor(5L, false, true, true, false, 8, false,
            List.of(tier("a", 1, "A")), null, null));

        assertThat(config.vendor().unifyEndpoints()).isTrue();
        assertThat(config.vendor().publicListing()).isTrue();
        assertThat(config.vendor().stickySessions()).isFalse();
        assertThat(config.vendor().refusalCap()).isEqualTo(8);
        assertThat(config.vendor().crossTierReservedSlot()).isFalse();
        assertThat(config.vendor().version()).isEqualTo(5L);
    }

    @Test
    void theAllEndpointsSwitchOnlyExistsForAnthropic() {
        SourceTierDtos.ReplaceVendor unify = new SourceTierDtos.ReplaceVendor(0L, false, true, null, null, null, null, null, null, null);

        assertThat(validator.normalize("anthropic", unify).vendor().unifyEndpoints()).isTrue();
        assertInvalid("openai", unify, "only exists for the anthropic vendor");
    }

    // ---- members ----

    @Test
    void aPinNeedsATierTheConfigurationHas() {
        assertMemberInvalid("anthropic", replace(false, OWNER_TIERS, List.of(pin("claude-1", "relay")), List.of()),
            "placed in tier relay, which this configuration does not have");
        assertMemberInvalid("anthropic", replace(false, OWNER_TIERS, List.of(pin("claude-1", "")), List.of()), "tierId is required");
    }

    @Test
    void anAccountIsPlacedOncePerVendor() {
        assertMemberInvalid("anthropic", replace(false, OWNER_TIERS, List.of(pin("claude-1", "kiro"), pin("claude-1", "official")), List.of()),
            "more than one member row");
    }

    @Test
    void anAccountIdIsOneToSixtyFourCharacters() {
        assertMemberInvalid("anthropic", replace(false, OWNER_TIERS, List.of(pin(" ", "kiro")), List.of()), "accountId must be 1 to 64");
        assertMemberInvalid("anthropic", replace(false, OWNER_TIERS, List.of(pin("a".repeat(65), "kiro")), List.of()), "accountId must be 1 to 64");
        assertThat(validator.normalize("anthropic", replace(false, OWNER_TIERS, List.of(pin("a".repeat(64), "kiro")), List.of())).members())
            .hasSize(1);
    }

    @Test
    void aPinKeepsTheDefaultsOfTheTableAndIgnoresWhatOnlyARelayHas() {
        SourceTierDtos.Member row = new SourceTierDtos.Member("claude-1", "kiro", null, false, true, true, true, true, false, "EFFORT",
            100, true, true, null);

        Member member = validator.normalize("anthropic", replace(false, OWNER_TIERS, List.of(row), List.of())).members().getFirst();

        assertThat(member.protocol()).isNull();
        assertThat(member.exclusive()).isTrue();
        assertThat(member.vision()).isFalse();
        assertThat(member.upstreamStream()).isTrue();
        assertThat(member.reasoningMode()).isEqualTo("NONE");
        assertThat(member.maxDurationSeconds()).isEqualTo(540);
        assertThat(member.usageCacheReadInPrompt()).isNull();
        assertThat(member.usageCacheWriteInPrompt()).isNull();
    }

    @Test
    void aPinHasNoModels() {
        SourceTierDtos.Member row = new SourceTierDtos.Member("claude-1", "kiro", null, null, null, null, null, null, null, null, null,
            null, null, List.of(mapping("claude-sonnet-4.5", "x")));

        assertMemberInvalid("anthropic", replace(false, OWNER_TIERS, List.of(row), List.of()), "models only apply to a member with a protocol");
    }

    // ---- relay members ----

    private static final List<SourceTierDtos.Tier> RELAY_TIERS = List.of(tier("relay", 1, "Relay"), tier("kiro", 2, "Kiro", "KIRO"));

    private ThrowingCallable relayRejected(SourceTierDtos.Member row, String vendor) {
        return () -> validator.normalize(vendor, replace(false, RELAY_TIERS, List.of(row), List.of()));
    }

    @Test
    void aRelayWithItsMappingsIsValidAndTakesTheDocumentedDefaults() {
        Config config = validator.normalize("anthropic", replace(false, RELAY_TIERS,
            List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", "relay/sonnet"), mapping("claude-haiku-4.5", "relay/haiku"))),
            List.of()));

        Member member = config.members().getFirst();
        assertThat(member.protocol()).isEqualTo("OPENAI_CHAT");
        assertThat(member.crossProtocol()).isTrue();
        assertThat(member.exclusive()).isTrue();
        assertThat(member.vision()).isFalse();
        assertThat(member.jsonSchema()).isFalse();
        assertThat(member.priorityTier()).isFalse();
        assertThat(member.cacheControlPassthrough()).isFalse();
        assertThat(member.upstreamStream()).isTrue();
        assertThat(member.reasoningMode()).isEqualTo("NONE");
        assertThat(member.maxDurationSeconds()).isEqualTo(540);
        assertThat(member.usageCacheReadInPrompt()).isFalse();
        assertThat(member.usageCacheWriteInPrompt()).isFalse();
        assertThat(config.models()).extracting(MemberModel::modelId).containsExactly("claude-sonnet-4.5", "claude-haiku-4.5");
        MemberModel model = config.models().getFirst();
        assertThat(model.vendor()).isEqualTo("anthropic");
        assertThat(model.accountId()).isEqualTo("relay-1");
        assertThat(model.upstreamModel()).isEqualTo("relay/sonnet");
        assertThat(model.maxContextTokens()).isEqualTo(200_000);
        assertThat(model.imageTokenCap()).isEqualTo(8192);
        assertThat(model.enabled()).isTrue();
        assertThat(model.verifiedAt()).isNull();
        assertThat(model.verifiedHash()).isNull();
    }

    @Test
    void theRelaySettingsAreKept() {
        Config config = validator.normalize("openai", replace(false, RELAY_TIERS, List.of(relay("relay-1", "relay", fields -> {
            fields.exclusive = false;
            fields.vision = true;
            fields.jsonSchema = true;
            fields.priorityTier = true;
            fields.cacheControlPassthrough = true;
            fields.upstreamStream = false;
            fields.reasoningMode = "EFFORT";
            fields.maxDuration = 900;
            fields.usageRead = true;
            fields.usageWrite = false;
            return fields;
        }, new SourceTierDtos.MemberModel("gpt-5.2", "relay/gpt", 400_000, 1_000_000, false, null, null))), List.of()));

        Member member = config.members().getFirst();
        assertThat(member).extracting(Member::exclusive, Member::vision, Member::jsonSchema, Member::priorityTier,
                Member::cacheControlPassthrough, Member::upstreamStream, Member::reasoningMode, Member::maxDurationSeconds,
                Member::usageCacheReadInPrompt, Member::usageCacheWriteInPrompt)
            .containsExactly(false, true, true, true, true, false, "EFFORT", 900, true, false);
        assertThat(config.models().getFirst()).extracting(MemberModel::maxContextTokens, MemberModel::imageTokenCap, MemberModel::enabled)
            .containsExactly(400_000, 1_000_000, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI_RESPONSES", "ANTHROPIC_MESSAGES", "openai_chat", "GRPC"})
    void onlyTheChatProtocolIsImplemented(String protocol) {
        SourceTierDtos.Member row = relay("relay-1", "relay", fields -> {
            fields.protocol = protocol;
            return fields;
        }, mapping("claude-sonnet-4.5", "x"));

        assertRejected(relayRejected(row, "anthropic"), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_PROTOCOL_INVALID", "only OPENAI_CHAT is");
    }

    @Test
    void aRelayOnlyServesVendorsWhoseOwnAccountsAreNotCompatibleKeys() {
        SourceTierDtos.Member row = relay("relay-1", "relay", mapping("deepseek-v4-pro", "x"));

        assertRejected(relayRejected(row, "deepseek"), HttpStatus.BAD_REQUEST, "SOURCE_PROFILE_NOT_APPLICABLE", "already uses compatible API keys");
        assertRejected(relayRejected(row, "my-gateway"), HttpStatus.BAD_REQUEST, "SOURCE_PROFILE_NOT_APPLICABLE", "already uses compatible API keys");
        for (String vendor : List.of("anthropic", "openai", "google")) {
            SourceTierDtos.Member ok = relay("relay-1", "relay", mapping(switch (vendor) {
                case "anthropic" -> "claude-sonnet-4.5";
                case "openai" -> "gpt-5.2";
                default -> "gemini-2.5-pro";
            }, "x"));
            assertThat(validator.normalize(vendor, replace(false, RELAY_TIERS, List.of(ok), List.of())).members()).hasSize(1);
        }
    }

    @Test
    void bothCacheBasisFlagsMustBeSaid() {
        for (int missing = 0; missing < 2; missing++) {
            int which = missing;
            SourceTierDtos.Member row = relay("relay-1", "relay", fields -> {
                if (which == 0) {
                    fields.usageRead = null;
                } else {
                    fields.usageWrite = null;
                }
                return fields;
            }, mapping("claude-sonnet-4.5", "x"));
            assertRejected(relayRejected(row, "anthropic"), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID",
                "usageCacheReadInPrompt and usageCacheWriteInPrompt must both be set");
        }
    }

    @Test
    void aRelayNeedsAMappingAndAKnownReasoningModeAndADurationInRange() {
        assertRejected(relayRejected(relay("relay-1", "relay"), "anthropic"), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID",
            "needs at least one mapping");
        assertRejected(relayRejected(relay("relay-1", "relay", fields -> {
            fields.reasoningMode = "FULL";
            return fields;
        }, mapping("claude-sonnet-4.5", "x")), "anthropic"), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "reasoningMode");
        for (int duration : new int[] {29, 901, 0, -5}) {
            assertRejected(relayRejected(relay("relay-1", "relay", fields -> {
                fields.maxDuration = duration;
                return fields;
            }, mapping("claude-sonnet-4.5", "x")), "anthropic"), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID",
                "maxDurationSeconds must be between 30 and 900");
        }
        for (int duration : new int[] {30, 900}) {
            assertThat(validator.normalize("anthropic", replace(false, RELAY_TIERS, List.of(relay("relay-1", "relay", fields -> {
                fields.maxDuration = duration;
                return fields;
            }, mapping("claude-sonnet-4.5", "x"))), List.of())).members().getFirst().maxDurationSeconds()).isEqualTo(duration);
        }
    }

    // ---- mappings ----

    private ThrowingCallable mappingRejected(String vendor, SourceTierDtos.MemberModel model) {
        return relayRejected(relay("relay-1", "relay", model), vendor);
    }

    @Test
    void aMappingKeyIsAnEnabledCatalogModelOfTheSameVendor() {
        assertRejected(mappingRejected("anthropic", mapping("claude-nonexistent", "x")), HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_MEMBER_INVALID", "is not an enabled model of the anthropic catalog");
        assertRejected(mappingRejected("anthropic", mapping("gpt-5.2", "x")), HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_MEMBER_INVALID", "is not an enabled model of the anthropic catalog");
        assertRejected(mappingRejected("openai", mapping("claude-sonnet-4.5", "x")), HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_MEMBER_INVALID", "is not an enabled model of the openai catalog");
        assertRejected(mappingRejected("anthropic", mapping("", "x")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "modelId must be 1 to 256");
        assertRejected(mappingRejected("anthropic", mapping("claude-*", "x")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID",
            "is not an enabled model");
    }

    @Test
    void routingPresetsAndCodeWhispererIdsAreNotModels() {
        for (String id : List.of("auto", "simple-task", "CLAUDE_SONNET_4_20250514_V1_0")) {
            assertRejected(mappingRejected("anthropic", mapping(id, "x")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID",
                "routing presets and CodeWhisperer internal ids cannot be mapped");
        }
    }

    @Test
    void aMappingKeyIsMatchedWithoutRegardToCaseAndAliasesAreModelsToo() {
        MemberModel model = validator.normalize("anthropic", replace(false, RELAY_TIERS, List.of(relay("relay-1", "relay",
            mapping("Claude-Sonnet-4.5", "x"), mapping("sonnet", "y"))), List.of())).models().getFirst();

        assertThat(model.modelId()).isEqualTo("Claude-Sonnet-4.5");
    }

    @Test
    void aModelIsMappedOncePerRelayWhateverTheCase() {
        SourceTierDtos.Member row = relay("relay-1", "relay", mapping("claude-sonnet-4.5", "x"), mapping("CLAUDE-SONNET-4.5", "y"));

        assertRejected(relayRejected(row, "anthropic"), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "is mapped twice");
    }

    static Stream<String> badWireNames() {
        return Stream.of("", " x", "x ", ".x", "-x", "a*b", "a b", "a\nb", "a?b", "x".repeat(256), "模型");
    }

    @ParameterizedTest
    @MethodSource("badWireNames")
    void theRelaysNameForAModelIsAPlainNameSentAsIs(String wire) {
        assertRejected(mappingRejected("anthropic", mapping("claude-sonnet-4.5", wire)), HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_MEMBER_INVALID", "upstreamModel must be a plain model name");
    }

    static Stream<String> goodWireNames() {
        return Stream.of("x", "openai/gpt-5", "vendor:model@2024-10", "claude-sonnet-4.5", "a+b_c.d-e", "x".repeat(255));
    }

    @ParameterizedTest
    @MethodSource("goodWireNames")
    void thePlainNamesThatFit(String wire) {
        Config config = validator.normalize("anthropic", replace(false, RELAY_TIERS,
            List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", wire))), List.of()));

        assertThat(config.models().getFirst().upstreamModel()).isEqualTo(wire);
    }

    @Test
    void theContextWindowIsRequiredAndTheImageCapIsInRange() {
        assertRejected(mappingRejected("anthropic", new SourceTierDtos.MemberModel("claude-sonnet-4.5", "x", null, null, null, null, null)),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "maxContextTokens is required");
        assertRejected(mappingRejected("anthropic", new SourceTierDtos.MemberModel("claude-sonnet-4.5", "x", 0, null, null, null, null)),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "maxContextTokens is required");
        assertRejected(mappingRejected("anthropic", new SourceTierDtos.MemberModel("claude-sonnet-4.5", "x", 1000, 0, null, null, null)),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "imageTokenCap must be between 1 and 1000000");
        assertRejected(mappingRejected("anthropic", new SourceTierDtos.MemberModel("claude-sonnet-4.5", "x", 1000, 1_000_001, null, null, null)),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "imageTokenCap must be between 1 and 1000000");
        assertThat(validator.normalize("anthropic", replace(false, RELAY_TIERS, List.of(relay("relay-1", "relay",
            new SourceTierDtos.MemberModel("claude-sonnet-4.5", "x", 1, 1, null, null, null))), List.of()))
            .models().getFirst().imageTokenCap()).isEqualTo(1);
    }

    @Test
    void whatTheClientSaysAboutVerificationIsNotTrusted() {
        MemberModel model = validator.normalize("anthropic", replace(false, RELAY_TIERS, List.of(relay("relay-1", "relay",
            new SourceTierDtos.MemberModel("claude-sonnet-4.5", "x", 1000, null, null, java.time.Instant.now(), true))), List.of()))
            .models().getFirst();

        assertThat(model.verifiedAt()).isNull();
        assertThat(model.verifiedHash()).isNull();
    }

    // ---- gates ----

    @Test
    void aGateIsKeptWithItsKeysDeduplicated() {
        Config config = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(
            gate("MESSAGES", "KEYS", "key-1", "key-2", "key-1", " key-3 "), gate("CHAT", "OFF"))));

        assertThat(config.gates()).containsExactly(
            new PairGate("anthropic", "OPENAI_CHAT", "MESSAGES", "KEYS", List.of("key-1", "key-2", "key-3")),
            new PairGate("anthropic", "OPENAI_CHAT", "CHAT", "OFF", List.of()));
    }

    @Test
    void aGateNamesAnEntryEndpointAStateAndTheOnlyProtocol() {
        for (SourceTierDtos.Gate bad : List.of(gate("WEBSOCKET", "OFF"), gate("", "OFF"), gate("chat", "OFF"), gate("CHAT", "MAYBE"),
            gate("CHAT", "on"), new SourceTierDtos.Gate("OPENAI_RESPONSES", "CHAT", "OFF", null))) {
            assertRejected(() -> validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(bad))),
                HttpStatus.BAD_REQUEST, "SOURCE_TIER_GATE_INVALID", "gates[0].");
        }
    }

    @Test
    void aPairHasOneGate() {
        assertRejected(() -> validator.normalize("anthropic", replace(false, List.of(), List.of(),
                List.of(gate("CHAT", "OFF"), gate("CHAT", "ON")))), HttpStatus.BAD_REQUEST, "SOURCE_TIER_GATE_INVALID", "appears twice");
    }

    @Test
    void aGateHoldsAtMostFiftyKeysOfAReasonableLength() {
        String[] fifty = IntStream.range(0, 50).mapToObj(i -> "key-" + i).toArray(String[]::new);
        String[] fiftyOne = IntStream.range(0, 51).mapToObj(i -> "key-" + i).toArray(String[]::new);

        assertThat(validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "KEYS", fifty))))
            .gates().getFirst().allowKeyIds()).hasSize(50);
        assertRejected(() -> validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "KEYS", fiftyOne)))),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_GATE_INVALID", "at most 50 keys");
        assertRejected(() -> validator.normalize("anthropic", replace(false, List.of(), List.of(),
                List.of(gate("CHAT", "KEYS", "k".repeat(129))))), HttpStatus.BAD_REQUEST, "SOURCE_TIER_GATE_INVALID", "1 to 128 characters");
    }

    @Test
    void gatesOnlyExistForTheVendorsARelayCanServe() {
        assertRejected(() -> validator.normalize("deepseek", replace(false, List.of(), List.of(), List.of(gate("CHAT", "OFF")))),
            HttpStatus.BAD_REQUEST, "SOURCE_PROFILE_NOT_APPLICABLE", "already uses compatible API keys");
    }

    // ---- what the release runs ----

    @Test
    void enablingAVendorIsRefusedWhileTheRuntimeCannotTierNatively() {
        Config config = validator.normalize("anthropic", replace(true, OWNER_TIERS, List.of(), List.of()));

        assertThatThrownBy(() -> validator.requireSupported(config)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getCode()).isEqualTo("SOURCE_TIER_RUNTIME_UNSUPPORTED");
            assertThat(error.getDetails()).isEqualTo(List.of("enabled"));
            assertThat(error.getMessage()).contains("enabling a vendor");
        });
        // The same configuration with the vendor off is fine: the tiers can be prepared.
        assertThatCode(() -> validator.requireSupported(validator.normalize("anthropic", replace(false, OWNER_TIERS, List.of(), List.of()))))
            .doesNotThrowAnyException();
    }

    @Test
    void theAllEndpointsSwitchCrossProtocolRowsAndOpenGatesAreRefusedWhileTheRuntimeLacksThem() {
        Config unify = validator.normalize("anthropic", new SourceTierDtos.ReplaceVendor(0L, false, true, null, null, null, null,
            OWNER_TIERS, null, null));
        Config relay = validator.normalize("anthropic", replace(false, RELAY_TIERS,
            List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", "x"))), List.of()));
        Config keysGate = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("MESSAGES", "KEYS", "k"))));
        Config onGate = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "ON"))));
        Config offGate = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "OFF"))));

        assertUnsupported(unify, "unifyEndpoints");
        assertUnsupported(relay, "crossProtocolMembers");
        assertUnsupported(keysGate, "pairGates");
        assertUnsupported(onGate, "pairGates");
        assertThatCode(() -> validator.requireSupported(offGate)).doesNotThrowAnyException();
    }

    @Test
    void everythingThatIsRefusedIsNamedInOneAnswer() {
        Config everything = validator.normalize("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, true, null, null, null, null,
            RELAY_TIERS, List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", "x"))), List.of(gate("CHAT", "ON"))));

        assertUnsupported(everything, "enabled", "unifyEndpoints", "crossProtocolMembers", "pairGates");
    }

    private void assertUnsupported(Config config, String... features) {
        assertThatThrownBy(() -> validator.requireSupported(config)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getCode()).isEqualTo("SOURCE_TIER_RUNTIME_UNSUPPORTED");
            assertThat(error.getDetails()).isEqualTo(List.of(features));
        });
    }

    @Test
    void aRuntimeWithTheCapabilitiesAcceptsWhatTheOneWithoutRefuses() {
        SourceTierValidator capable = withRuntime(true, true, true, PairStatus.VERIFIED);
        Config config = capable.normalize("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, true, null, null, null, null,
            RELAY_TIERS, List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", "x"))), List.of(gate("CHAT", "ON"),
                gate("MESSAGES", "KEYS", "k"))));

        assertThatCode(() -> capable.requireSupported(config)).doesNotThrowAnyException();
    }

    @Test
    void eachCapabilityIsIndependent() {
        Config enabled = validator.normalize("anthropic", replace(true, OWNER_TIERS, List.of(), List.of()));
        Config relay = validator.normalize("anthropic", replace(false, RELAY_TIERS,
            List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", "x"))), List.of()));

        assertThatCode(() -> withRuntime(true, false, false, PairStatus.NOT_OFFERED).requireSupported(enabled)).doesNotThrowAnyException();
        assertThatThrownBy(() -> withRuntime(false, true, true, PairStatus.VERIFIED).requireSupported(enabled))
            .isInstanceOf(ApiException.class);
        assertThatCode(() -> withRuntime(false, false, true, PairStatus.NOT_OFFERED).requireSupported(relay)).doesNotThrowAnyException();
        assertThatThrownBy(() -> withRuntime(true, true, false, PairStatus.VERIFIED).requireSupported(relay))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void aGateFollowsTheStatusOfItsPairOnceRelaysCanRun() {
        Config keys = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "KEYS", "k"))));
        Config on = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "ON"))));
        Config off = validator.normalize("anthropic", replace(false, List.of(), List.of(), List.of(gate("CHAT", "OFF"))));

        // Not offered: nothing but OFF.
        SourceTierValidator notOffered = withRuntime(true, true, true, PairStatus.NOT_OFFERED);
        assertRejected(() -> notOffered.requireSupported(keys), HttpStatus.BAD_REQUEST, "SOURCE_PAIR_NOT_OFFERED", "is not offered");
        assertRejected(() -> notOffered.requireSupported(on), HttpStatus.BAD_REQUEST, "SOURCE_PAIR_NOT_OFFERED", "is not offered");
        assertThatCode(() -> notOffered.requireSupported(off)).doesNotThrowAnyException();
        // Experimental: listed keys only.
        SourceTierValidator experimental = withRuntime(true, true, true, PairStatus.EXPERIMENTAL);
        assertThatCode(() -> experimental.requireSupported(keys)).doesNotThrowAnyException();
        assertRejected(() -> experimental.requireSupported(on), HttpStatus.CONFLICT, "SOURCE_PAIR_GATE_FORBIDDEN", "experimental");
        // Verified: anything.
        SourceTierValidator verified = withRuntime(true, true, true, PairStatus.VERIFIED);
        assertThatCode(() -> verified.requireSupported(keys)).doesNotThrowAnyException();
        assertThatCode(() -> verified.requireSupported(on)).doesNotThrowAnyException();
    }

    // ---- accounts ----

    @Test
    void everyMemberAccountMustExist() {
        Config config = validator.normalize("anthropic", replace(false, OWNER_TIERS, List.of(pin("claude-1", "kiro")), List.of()));

        assertRejected(() -> validator.checkAccounts(config, Map.of()), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID",
            "Account claude-1 does not exist");
        assertThatCode(() -> validator.checkAccounts(config, Map.of("claude-1", account("claude-1", "Anthropic", "oauth", null, true))))
            .doesNotThrowAnyException();
        // A pin does not have to be in the vendor's family: that is a warning, not a refusal.
        assertThatCode(() -> validator.checkAccounts(config, Map.of("claude-1", account("claude-1", "OpenAI", "api-key", null, true))))
            .doesNotThrowAnyException();
    }

    @Test
    void aRelayAccountMustBeAnOpenAiCompatibleApiKey() {
        Config config = validator.normalize("anthropic", replace(false, RELAY_TIERS,
            List.of(relay("relay-1", "relay", mapping("claude-sonnet-4.5", "x"))), List.of()));

        assertThatCode(() -> validator.checkAccounts(config, Map.of("relay-1",
            account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1", true)))).doesNotThrowAnyException();
        for (ProxyAccountEntity wrong : List.of(
            account("relay-1", "OpenAI", "api-key", null, true),
            account("relay-1", "OpenAI", "codex", null, true),
            account("relay-1", "Anthropic", "api-key", "https://claude-gateway.example/v1", true),
            account("relay-1", "BuilderId", "idc", null, true),
            account("relay-1", "DeepSeek", "api-key", null, true))) {
            assertRejected(() -> validator.checkAccounts(config, Map.of("relay-1", wrong)), HttpStatus.BAD_REQUEST,
                "SOURCE_TIER_MEMBER_INVALID", "is not an OpenAI-compatible API key");
        }
    }

    // ---- member changes ----

    private Config stored() {
        return validator.normalize("anthropic", replace(false, RELAY_TIERS, List.of(
            pin("kiro-1", "kiro"),
            relay("relay-1", "relay", mapping("claude-sonnet-4.5", "old-sonnet"), mapping("claude-haiku-4.5", "old-haiku"))), List.of()));
    }

    private MemberChanges changes(SourceTierDtos.Member[] members, String... removed) {
        return validator.normalizeChanges("anthropic", new SourceTierDtos.UpsertMembers(0L, List.of(members), List.of(removed)));
    }

    @Test
    void addingAMemberKeepsTheOthers() {
        Config after = validator.apply(stored(), changes(new SourceTierDtos.Member[] {pin("kiro-2", "kiro")}));

        assertThat(after.members()).extracting(Member::accountId).containsExactly("kiro-1", "relay-1", "kiro-2");
        assertThat(after.models()).extracting(MemberModel::accountId).containsOnly("relay-1");
    }

    @Test
    void updatingARelayReplacesItsRowAndItsModels() {
        Config after = validator.apply(stored(), changes(new SourceTierDtos.Member[] {
            relay("relay-1", "relay", mapping("claude-opus-4.5", "new-opus"))}));

        assertThat(after.members()).extracting(Member::accountId).containsExactly("kiro-1", "relay-1");
        assertThat(after.models()).extracting(MemberModel::modelId).containsExactly("claude-opus-4.5");
    }

    @Test
    void movingAPinToAnotherTierIsAnUpdate() {
        Config after = validator.apply(stored(), changes(new SourceTierDtos.Member[] {pin("kiro-1", "relay")}));

        assertThat(after.members().stream().filter(member -> member.accountId().equals("kiro-1")).findFirst().orElseThrow().tierId())
            .isEqualTo("relay");
    }

    @Test
    void removingAMemberDropsItsModelsAndAnUnknownAccountIsIgnored() {
        Config after = validator.apply(stored(), changes(new SourceTierDtos.Member[0], "relay-1", "never-there"));

        assertThat(after.members()).extracting(Member::accountId).containsExactly("kiro-1");
        assertThat(after.models()).isEmpty();
    }

    @Test
    void theResultOfAChangeIsCheckedAsAWholeConfiguration() {
        assertRejected(() -> validator.apply(stored(), changes(new SourceTierDtos.Member[] {pin("kiro-2", "gone")})),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "does not have");
    }

    @Test
    void aChangeNeedsSomethingToChangeAndCannotAddAndRemoveOneAccount() {
        assertRejected(() -> changes(new SourceTierDtos.Member[0]), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "Nothing to change");
        assertRejected(() -> changes(new SourceTierDtos.Member[] {pin("kiro-1", "kiro")}, "kiro-1"), HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_MEMBER_INVALID", "both added and removed");
        assertRejected(() -> changes(new SourceTierDtos.Member[] {pin("kiro-1", "kiro"), pin("kiro-1", "relay")}), HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_MEMBER_INVALID", "appears twice");
        assertRejected(() -> changes(new SourceTierDtos.Member[0], " "), HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "empty account id");
    }

    @Test
    void aChangeFollowsTheSameRulesAsAReplace() {
        assertRejected(() -> changes(new SourceTierDtos.Member[] {relay("relay-2", "relay", mapping("gpt-5.2", "x"))}),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_MEMBER_INVALID", "is not an enabled model of the anthropic catalog");
        assertRejected(() -> validator.normalizeChanges("deepseek", new SourceTierDtos.UpsertMembers(0L,
                List.of(relay("relay-2", "relay", mapping("deepseek-v4-pro", "x"))), null)),
            HttpStatus.BAD_REQUEST, "SOURCE_PROFILE_NOT_APPLICABLE", "already uses compatible API keys");
    }

    // ---- warnings ----

    private static final ProxyAccountEntity OFFICIAL_ANTHROPIC = account("official-1", "Anthropic", "api-key", null, true);
    private static final ProxyAccountEntity OFFICIAL_OPENAI = account("official-oa", "OpenAI", "api-key", null, true);
    private static final ProxyAccountEntity KIRO = account("kiro-1", "BuilderId", "idc", null, true);
    private static final ProxyAccountEntity CLAUDE_GATEWAY = account("gateway-1", "Anthropic", "api-key", "https://claude-gateway.example/v1", true);

    private static Tier stored(String id, int level, boolean enabled, AccountAllocationCategory... categories) {
        return new Tier("anthropic", id, level, id, BigDecimal.ONE, Set.of(categories), true, enabled, null);
    }

    private static Member nativePin(String accountId, String tierId) {
        return new Member("anthropic", accountId, tierId, null, true, false, false, false, false, true, "NONE", 540, null, null);
    }

    /** The warnings of the configuration the rows make up for {@code vendor}; the rows are made for "anthropic" and moved. */
    private static List<Warning> warningsOf(String vendor, List<Tier> tiers, List<Member> members, List<ProxyAccountEntity> accounts) {
        List<Tier> ofVendor = tiers.stream().map(tier -> new Tier(vendor, tier.tierId(), tier.level(), tier.name(),
            tier.priceFactor(), tier.memberCategories(), tier.userSelectable(), tier.enabled(), tier.description())).toList();
        List<Member> pinned = members.stream().map(member -> new Member(vendor, member.accountId(), member.tierId(),
            member.protocol(), member.exclusive(), member.vision(), member.jsonSchema(), member.priorityTier(),
            member.cacheControlPassthrough(), member.upstreamStream(), member.reasoningMode(), member.maxDurationSeconds(),
            member.usageCacheReadInPrompt(), member.usageCacheWriteInPrompt())).toList();
        SourceTierSnapshot snapshot = SourceTierSnapshot.of(1L, List.of(SourceTierSnapshot.VendorConfig.defaults(vendor)),
            ofVendor, pinned, List.of(), List.of());
        return SourceTierValidator.warnings(vendor, snapshot, accounts);
    }

    @Test
    void anOfficialKeyNoTierListsFallsIntoTheLowestTierWithAWarning() {
        List<Warning> warnings = warningsOf("anthropic",
            List.of(stored("relay", 1, true), stored("kiro", 2, true, AccountAllocationCategory.KIRO)), List.of(),
            List.of(OFFICIAL_ANTHROPIC, KIRO, CLAUDE_GATEWAY));

        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("LOWEST_TIER_COLLECTS_OFFICIAL_KEYS");
            assertThat(warning.tierId()).isEqualTo("kiro");
            assertThat(warning.count()).isEqualTo(1);
            assertThat(warning.accountIds()).containsExactly("official-1");
            assertThat(warning.message()).contains("1 official API key").contains("kiro");
        });
    }

    @Test
    void noWarningWhenATierListsOfficialKeysOrThereIsNothingToCollect() {
        List<Tier> official = List.of(stored("official", 1, true, AccountAllocationCategory.OFFICIAL_API_KEY),
            stored("kiro", 2, true, AccountAllocationCategory.KIRO));
        List<Tier> unclaimed = List.of(stored("relay", 1, true), stored("kiro", 2, true, AccountAllocationCategory.KIRO));

        assertThat(warningsOf("anthropic", official, List.of(), List.of(OFFICIAL_ANTHROPIC, KIRO))).isEmpty();
        assertThat(warningsOf("anthropic", unclaimed, List.of(), List.of(KIRO, CLAUDE_GATEWAY))).isEmpty();
        assertThat(warningsOf("anthropic", List.of(), List.of(), List.of(OFFICIAL_ANTHROPIC))).isEmpty();
    }

    @Test
    void aPinnedAccountAndADisabledOneAreNotCollected() {
        Member pinned = new Member("anthropic", "official-1", "relay", null, true, false, false, false, false, true, "NONE", 540, null, null);
        ProxyAccountEntity disabled = account("official-2", "Anthropic", "api-key", null, false);
        List<Tier> tiers = List.of(stored("relay", 1, true), stored("kiro", 2, true, AccountAllocationCategory.KIRO));

        assertThat(warningsOf("anthropic", tiers, List.of(pinned), List.of(OFFICIAL_ANTHROPIC, disabled))).isEmpty();
    }

    @Test
    void theLowestTierIsTheLowestEnabledOne() {
        List<Tier> tiers = List.of(stored("relay", 1, true), stored("kiro", 2, true, AccountAllocationCategory.KIRO), stored("spare", 3, false));

        assertThat(warningsOf("anthropic", tiers, List.of(), List.of(OFFICIAL_ANTHROPIC)))
            .singleElement().extracting(Warning::tierId).isEqualTo("kiro");
        assertThat(warningsOf("anthropic", List.of(stored("spare", 1, false)), List.of(), List.of(OFFICIAL_ANTHROPIC))).isEmpty();
    }

    @Test
    void onlyKeysOfTheVendorsOwnProviderAreCounted() {
        List<Tier> tiers = List.of(stored("relay", 1, true), stored("kiro", 2, true, AccountAllocationCategory.KIRO));

        assertThat(warningsOf("anthropic", tiers, List.of(), List.of(OFFICIAL_OPENAI))).isEmpty();
        assertThat(warningsOf("openai", tiers, List.of(), List.of(OFFICIAL_OPENAI, OFFICIAL_ANTHROPIC)))
            .singleElement().satisfies(warning -> assertThat(warning.accountIds()).containsExactly("official-oa"));
    }

    @Test
    void theWarningNamesTheFirstFiveAccountsAndCountsAll() {
        List<ProxyAccountEntity> keys = IntStream.rangeClosed(1, 8)
            .mapToObj(i -> account("key-" + i, "Anthropic", "api-key", null, true)).toList();

        Warning warning = warningsOf("anthropic", List.of(stored("kiro", 1, true)), List.of(), keys).getFirst();

        assertThat(warning.count()).isEqualTo(8);
        assertThat(warning.accountIds()).containsExactly("key-1", "key-2", "key-3", "key-4", "key-5");
    }

    @Test
    void aPinnedAccountThatTheVendorsRouteNeverUsesIsWarnedAbout() {
        Member useless = new Member("anthropic", "official-oa", "kiro", null, true, false, false, false, false, true, "NONE", 540, null, null);
        Member fine = new Member("anthropic", "kiro-1", "kiro", null, true, false, false, false, false, true, "NONE", 540, null, null);
        Member relay = new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540,
            false, false);
        ProxyAccountEntity relayAccount = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1", true);
        List<Tier> tiers = List.of(stored("relay", 1, true), stored("kiro", 2, true, AccountAllocationCategory.OFFICIAL_API_KEY));

        List<Warning> warnings = warningsOf("anthropic", tiers, List.of(useless, fine, relay),
            List.of(OFFICIAL_OPENAI, KIRO, relayAccount));

        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("MEMBER_NOT_SERVING_VENDOR");
            assertThat(warning.tierId()).isEqualTo("kiro");
            assertThat(warning.accountIds()).containsExactly("official-oa");
        });
    }

    @Test
    void anAnthropicKeyOrAKiroAccountPinnedToAnotherVendorIsLiveBecauseMessagesRoutesEveryModelThere() {
        ProxyAccountEntity codex = account("codex-1", "OpenAI", "codex", null, true);
        ProxyAccountEntity relayKey = account("relay-key", "OpenAI-compatible", "api-key", "https://relay.example/v1", true);
        List<Tier> tiers = List.of(stored("official", 1, true), stored("kiro", 2, true));
        List<ProxyAccountEntity> accounts = List.of(OFFICIAL_ANTHROPIC, CLAUDE_GATEWAY, KIRO, codex, relayKey);

        // A gpt-* request through /v1/messages is served by Anthropic keys and Kiro accounts, so these pins decide a tier.
        assertThat(unservedPins("openai", tiers, accounts, "official-1", "gateway-1", "kiro-1")).isEmpty();
        // Codex only serves Codex requests and a compatible key only its own models: neither is reached for Claude.
        assertThat(unservedPins("anthropic", tiers, accounts, "codex-1", "relay-key", "kiro-1", "official-1"))
            .containsExactly("codex-1", "relay-key");
        // And an Anthropic key is no more useless in a custom vendor than in OpenAI.
        assertThat(unservedPins("moonshot", tiers, accounts, "official-1", "relay-key", "codex-1")).containsExactly("codex-1");
    }

    private static List<String> unservedPins(String vendor, List<Tier> tiers, List<ProxyAccountEntity> accounts, String... pinned) {
        List<Member> members = Stream.of(pinned).map(accountId -> nativePin(accountId, "kiro")).toList();
        return warningsOf(vendor, tiers, members, accounts).stream()
            .filter(warning -> warning.code().equals("MEMBER_NOT_SERVING_VENDOR"))
            .flatMap(warning -> warning.accountIds().stream())
            .toList();
    }

    // ---- fingerprint ----

    private static final Member RELAY = new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false,
        true, "NONE", 540, false, false);
    private static final MemberModel MODEL = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "relay/sonnet", 200_000, 8192,
        true, null, null);
    private static final ProxyAccountEntity RELAY_ACCOUNT = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1", true);

    @Test
    void theFingerprintIsAStableSha256() {
        String fingerprint = SourceTierValidator.mappingFingerprint(RELAY, MODEL, RELAY_ACCOUNT);

        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(SourceTierValidator.mappingFingerprint(RELAY, MODEL, RELAY_ACCOUNT)).isEqualTo(fingerprint);
    }

    @Test
    void theFingerprintChangesWithWhatAVerificationVouchesFor() {
        String base = SourceTierValidator.mappingFingerprint(RELAY, MODEL, RELAY_ACCOUNT);
        List<String> changed = new ArrayList<>();
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "vision", true), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "jsonSchema", true), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "priorityTier", true), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "cacheControlPassthrough", true), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "upstreamStream", false), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "reasoningMode", "EFFORT"), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "usageCacheReadInPrompt", true), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(copy(RELAY, "usageCacheWriteInPrompt", true), MODEL, RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(RELAY, new MemberModel("anthropic", "relay-1", "claude-haiku-4.5", "relay/sonnet",
            200_000, 8192, true, null, null), RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(RELAY, new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "relay/other",
            200_000, 8192, true, null, null), RELAY_ACCOUNT));
        changed.add(SourceTierValidator.mappingFingerprint(RELAY, MODEL, account("relay-1", "OpenAI", "api-key", "https://relay.example/v1", true)));
        changed.add(SourceTierValidator.mappingFingerprint(RELAY, MODEL, account("relay-1", "OpenAI-compatible", "api-key", "https://other.example/v1", true)));
        changed.add(SourceTierValidator.mappingFingerprint(RELAY, MODEL, null));

        assertThat(changed).doesNotContain(base).doesNotHaveDuplicates();
    }

    @Test
    void theFingerprintIgnoresWhatMayChangeWithoutANewProbe() {
        String base = SourceTierValidator.mappingFingerprint(RELAY, MODEL, RELAY_ACCOUNT);

        assertThat(SourceTierValidator.mappingFingerprint(copy(RELAY, "tierId", "kiro"), MODEL, RELAY_ACCOUNT)).isEqualTo(base);
        assertThat(SourceTierValidator.mappingFingerprint(copy(RELAY, "exclusive", false), MODEL, RELAY_ACCOUNT)).isEqualTo(base);
        assertThat(SourceTierValidator.mappingFingerprint(copy(RELAY, "maxDurationSeconds", 60), MODEL, RELAY_ACCOUNT)).isEqualTo(base);
        assertThat(SourceTierValidator.mappingFingerprint(RELAY, new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "relay/sonnet",
            1_000, 1, false, java.time.Instant.now(), "0".repeat(64)), RELAY_ACCOUNT)).isEqualTo(base);
        assertThat(SourceTierValidator.mappingFingerprint(RELAY, new MemberModel("anthropic", "relay-1", "CLAUDE-SONNET-4.5", "relay/sonnet",
            200_000, 8192, true, null, null), RELAY_ACCOUNT)).isEqualTo(base);
    }

    @Test
    void theFieldsOfTheFingerprintCannotRunIntoEachOther() {
        MemberModel ab = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "ab", 1, 1, true, null, null);
        MemberModel a = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "a", 1, 1, true, null, null);

        // "ab" + "c" and "a" + "bc" are the same characters in the same order.
        assertThat(SourceTierValidator.mappingFingerprint(RELAY, ab, account("x", "c", "api-key", "d", true)))
            .isNotEqualTo(SourceTierValidator.mappingFingerprint(RELAY, a, account("x", "bc", "api-key", "d", true)));
    }

    private static Member copy(Member member, String field, Object value) {
        return new Member(
            member.vendor(),
            member.accountId(),
            field.equals("tierId") ? (String) value : member.tierId(),
            member.protocol(),
            field.equals("exclusive") ? (Boolean) value : member.exclusive(),
            field.equals("vision") ? (Boolean) value : member.vision(),
            field.equals("jsonSchema") ? (Boolean) value : member.jsonSchema(),
            field.equals("priorityTier") ? (Boolean) value : member.priorityTier(),
            field.equals("cacheControlPassthrough") ? (Boolean) value : member.cacheControlPassthrough(),
            field.equals("upstreamStream") ? (Boolean) value : member.upstreamStream(),
            field.equals("reasoningMode") ? (String) value : member.reasoningMode(),
            field.equals("maxDurationSeconds") ? (Integer) value : member.maxDurationSeconds(),
            field.equals("usageCacheReadInPrompt") ? (Boolean) value : member.usageCacheReadInPrompt(),
            field.equals("usageCacheWriteInPrompt") ? (Boolean) value : member.usageCacheWriteInPrompt()
        );
    }
}
