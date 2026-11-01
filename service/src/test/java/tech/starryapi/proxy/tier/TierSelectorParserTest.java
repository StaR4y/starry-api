package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.proxy.tier.TierSelectorParser.Kind;
import tech.starryapi.proxy.tier.TierSelectorParser.Selector;
import tech.starryapi.proxy.tier.TierSelectorParser.Source;

class TierSelectorParserTest {

    // ---- the suffix of a model id ----

    @Test
    void theReferenceIsWhatFollowsTheLastAt() {
        assertThat(TierSelectorParser.splitSuffix("claude-sonnet-4.5@kiro")).hasValueSatisfying(suffix -> {
            assertThat(suffix.model()).isEqualTo("claude-sonnet-4.5");
            assertThat(suffix.reference()).isEqualTo("kiro");
        });
        assertThat(TierSelectorParser.splitSuffix("gpt-5.5@L2")).hasValueSatisfying(suffix -> {
            assertThat(suffix.model()).isEqualTo("gpt-5.5");
            assertThat(suffix.reference()).isEqualTo("L2");
        });
        // An id with more than one @ keeps all but the last for the model, which then is not a catalog id.
        assertThat(TierSelectorParser.splitSuffix("a@b@c")).hasValueSatisfying(suffix -> {
            assertThat(suffix.model()).isEqualTo("a@b");
            assertThat(suffix.reference()).isEqualTo("c");
        });
    }

    @Test
    void anIdWithoutAnAtHasNoSuffix() {
        assertThat(TierSelectorParser.splitSuffix("claude-sonnet-4.5")).isEmpty();
        assertThat(TierSelectorParser.splitSuffix("")).isEmpty();
        assertThat(TierSelectorParser.splitSuffix(null)).isEmpty();
    }

    @Test
    void anAtAtTheEndIsASuffixWithNothingAfterIt() {
        assertThat(TierSelectorParser.splitSuffix("claude-sonnet-4.5@")).hasValueSatisfying(suffix -> {
            assertThat(suffix.model()).isEqualTo("claude-sonnet-4.5");
            assertThat(suffix.reference()).isEmpty();
        });
    }

    // ---- the grammar ----

    @Test
    void autoIsAutoInAnyCase() {
        for (String reference : new String[] {"auto", "AUTO", " Auto "}) {
            assertThat(TierSelectorParser.parse(Source.SUFFIX, reference).kind()).isEqualTo(Kind.AUTO);
        }
        assertThat(TierSelectorParser.parse(Source.SUFFIX, "auto").auto()).isTrue();
    }

    @Test
    void aTierIsNamedByItsIdentifier() {
        Selector selector = TierSelectorParser.parse(Source.SUFFIX, "kiro");

        assertThat(selector.kind()).isEqualTo(Kind.TIER);
        assertThat(selector.tierId()).isEqualTo("kiro");
        assertThat(selector.orBelow()).isFalse();
        assertThat(selector.source()).isEqualTo(Source.SUFFIX);
        assertThat(selector.text()).isEqualTo("kiro");
    }

    @Test
    void theIdentifierIsMatchedInLowerCaseBecauseTiersAreOnlyEverNamedInLowerCase() {
        Selector selector = TierSelectorParser.parse(Source.HEADER, "Kiro-Relay");

        assertThat(selector.tierId()).isEqualTo("kiro-relay");
        assertThat(selector.text()).isEqualTo("Kiro-Relay");
    }

    @Test
    void aPlusMeansTheTierOrAnyBelowIt() {
        Selector selector = TierSelectorParser.parse(Source.SUFFIX, "kiro+");

        assertThat(selector.kind()).isEqualTo(Kind.TIER);
        assertThat(selector.tierId()).isEqualTo("kiro");
        assertThat(selector.orBelow()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"L2", "l2", "2"})
    void aLevelIsANumberWithOrWithoutTheL(String reference) {
        Selector selector = TierSelectorParser.parse(Source.SUFFIX, reference);

        assertThat(selector.kind()).isEqualTo(Kind.LEVEL);
        assertThat(selector.level()).isEqualTo(2);
        assertThat(selector.orBelow()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1+", "L1+", "l1+"})
    void aLevelMayBeFollowedByAPlusToo(String reference) {
        Selector selector = TierSelectorParser.parse(Source.SUFFIX, reference);

        assertThat(selector.kind()).isEqualTo(Kind.LEVEL);
        assertThat(selector.level()).isEqualTo(1);
        assertThat(selector.orBelow()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "+", "kiro++", "ki ro", "-kiro", "kiro_1", "kiro/1", "ki.ro", "Ünicode",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void whatIsNotInTheGrammarIsInvalidAndNeverAFailure(String reference) {
        Selector selector = TierSelectorParser.parse(Source.SUFFIX, reference);

        assertThat(selector.kind()).isEqualTo(Kind.INVALID);
        assertThat(selector.auto()).isFalse();
    }

    @Test
    void anIdentifierLongerThanTheColumnAllowsIsInvalid() {
        assertThat(TierSelectorParser.parse(Source.SUFFIX, "a".repeat(32)).kind()).isEqualTo(Kind.TIER);
        assertThat(TierSelectorParser.parse(Source.SUFFIX, "a".repeat(33)).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void anLWithoutANumberIsAnIdentifierLikeAny() {
        assertThat(TierSelectorParser.parse(Source.SUFFIX, "L").tierId()).isEqualTo("l");
        assertThat(TierSelectorParser.parse(Source.SUFFIX, "l2x3").tierId()).isEqualTo("l2x3");
    }

    @Test
    void autoWithAPlusIsNotAutoButAnIdentifierNoTierCanHave() {
        Selector selector = TierSelectorParser.parse(Source.SUFFIX, "auto+");

        assertThat(selector.kind()).isEqualTo(Kind.TIER);
        assertThat(selector.tierId()).isEqualTo("auto");
    }

    // ---- the channels ----

    @Test
    void aMissingOrBlankHeaderSaysNothing() {
        assertThat(TierSelectorParser.fromHeader(null)).isNull();
        assertThat(TierSelectorParser.fromHeader("")).isNull();
        assertThat(TierSelectorParser.fromHeader("   ")).isNull();
    }

    @Test
    void aHeaderIsReadWithoutItsSpaces() {
        Selector selector = TierSelectorParser.fromHeader("  kiro+ ");

        assertThat(selector.source()).isEqualTo(Source.HEADER);
        assertThat(selector.tierId()).isEqualTo("kiro");
        assertThat(selector.orBelow()).isTrue();
    }

    @Test
    void theSuffixBeatsTheHeaderAndTheHeaderBeatsThePreference() {
        Selector suffix = TierSelectorParser.parse(Source.SUFFIX, "official");
        Selector header = TierSelectorParser.parse(Source.HEADER, "kiro");
        Selector preference = TierSelectorParser.parse(Source.PREFERENCE, "L2");

        assertThat(TierSelectorParser.choose(suffix, header, preference)).isSameAs(suffix);
        assertThat(TierSelectorParser.choose(null, header, preference)).isSameAs(header);
        assertThat(TierSelectorParser.choose(null, null, preference)).isSameAs(preference);
        assertThat(TierSelectorParser.choose(null, null, null)).isNull();
    }

    @Test
    void anAutoSuffixClearsAHeader() {
        Selector suffix = TierSelectorParser.parse(Source.SUFFIX, "auto");
        Selector header = TierSelectorParser.parse(Source.HEADER, "kiro");

        assertThat(TierSelectorParser.choose(suffix, header, null).auto()).isTrue();
    }

    @Test
    void aChannelThatCannotBeUsedDoesNotPassTheTurnToTheNextOne() {
        Selector header = TierSelectorParser.parse(Source.HEADER, "not a tier");
        Selector preference = TierSelectorParser.parse(Source.PREFERENCE, "kiro");

        assertThat(TierSelectorParser.choose(null, header, preference)).isSameAs(header);
    }

    // ---- the preference of a key ----

    @Test
    void aKeyWithoutAPreferenceOrWithAutoSaysNothing() {
        assertThat(TierSelectorParser.fromPreference(null)).isNull();
        assertThat(TierSelectorParser.fromPreference("")).isNull();
        assertThat(TierSelectorParser.fromPreference("  ")).isNull();
        assertThat(TierSelectorParser.fromPreference("auto")).isNull();
        assertThat(TierSelectorParser.fromPreference("AUTO")).isNull();
    }

    @Test
    void aPreferenceIsReadLikeAnyOtherSelectorAndKnowsItsChannel() {
        Selector tier = TierSelectorParser.fromPreference("kiro+");
        Selector level = TierSelectorParser.fromPreference("l2");

        assertThat(tier.source()).isEqualTo(Source.PREFERENCE);
        assertThat(tier.kind()).isEqualTo(Kind.TIER);
        assertThat(tier.tierId()).isEqualTo("kiro");
        assertThat(tier.orBelow()).isTrue();
        assertThat(level.source()).isEqualTo(Source.PREFERENCE);
        assertThat(level.kind()).isEqualTo(Kind.LEVEL);
        assertThat(level.level()).isEqualTo(2);
    }

    @Test
    void aPreferenceThatIsNotInTheGrammarIsAnInvalidSelectorTheRequestIgnores() {
        assertThat(TierSelectorParser.fromPreference("ki ro").kind()).isEqualTo(Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kiro", "kiro+", "official-2", "l2", "L2", "2", "9+", "l9+", "a", "0kiro",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void aPreferenceAKeyCanHoldIsStoredTrimmedAndInLowerCase(String preference) {
        assertThat(TierSelectorParser.storedPreference("  " + preference.toUpperCase() + " "))
            .contains(preference.toLowerCase());
    }

    @ParameterizedTest
    @CsvSource({"L02,l2", "02,2", "002+,2+", "l007,l7", "l01,l1", "L09,l9", "009+,9+", "003,3", "l001+,l1+", "L2,l2", "9,9"})
    void aLevelIsStoredInItsPlainSpellingWhateverZerosItWasWrittenWith(String preference, String stored) {
        assertThat(TierSelectorParser.storedPreference(preference)).contains(stored);
        // The request reads the spelling it was given and the plain one as the same level.
        Selector given = TierSelectorParser.fromPreference(preference);
        Selector plain = TierSelectorParser.fromPreference(stored);
        assertThat(given.kind()).isEqualTo(Kind.LEVEL);
        assertThat(given.level()).isEqualTo(plain.level());
        assertThat(given.orBelow()).isEqualTo(plain.orBelow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "auto", "Auto", " AUTO "})
    void noPreferenceAndAutoAreStoredAsAuto(String preference) {
        assertThat(TierSelectorParser.storedPreference(preference)).contains("auto");
    }

    @Test
    void noPreferenceAtAllIsStoredAsAuto() {
        assertThat(TierSelectorParser.storedPreference(null)).contains("auto");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ki ro", "kiro++", "+", "-kiro", "kiro_1", "ki.ro", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "l0",
        "0", "00", "000", "l000+", "010", "l010", "10", "l10+", "123", "1234", "auto+", "l12345"})
    void aPreferenceNoTierCouldAnswerToIsNotStored(String preference) {
        assertThat(TierSelectorParser.storedPreference(preference)).isEmpty();
    }

    @Test
    void whatIsStoredIsWhatTheDatabaseColumnAccepts() {
        java.util.regex.Pattern column = java.util.regex.Pattern.compile("(auto|[a-z0-9][a-z0-9-]{0,31}\\+?|[Ll]?[1-9]\\+?)");
        for (String preference : new String[] {"auto", "kiro", "kiro+", "official-2", "l2", "2", "9+", "l9+", "a", "0kiro",
            "L02", "02", "002+", "l007", "l01", "009+"}) {
            assertThat(TierSelectorParser.storedPreference(preference)).hasValueSatisfying(stored ->
                assertThat(column.matcher(stored).matches()).as(stored).isTrue());
        }
    }

    @Test
    void strippedPlainKeepsTheModelAndHasNoSelector() {
        assertThat(TierSelectorParser.Stripped.plain("gpt-5.5@L2").model()).isEqualTo("gpt-5.5@L2");
        assertThat(TierSelectorParser.Stripped.plain("gpt-5.5@L2").selector()).isNull();
        assertThat(TierSelectorParser.Stripped.plain("gpt-5.5@L2").destination()).isNull();
    }

    @Test
    void aSelectorThatWasTakenOffComesWithTheDestinationItWasDecidedWith() {
        TierSelectorParser.Selector selector = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "kiro");

        assertThatThrownBy(() -> new TierSelectorParser.Stripped("gpt-5.5", selector, null))
            .isInstanceOf(NullPointerException.class);
        assertThat(new TierSelectorParser.Stripped("gpt-5.5", selector, new TierRuntime.Destination("gpt-5.5", "OpenAI")).destination())
            .isNotNull();
    }

    @Test
    void aCallerThatMayNotChooseHasWhatItNamedTurnedIntoAHintThatIsDropped() {
        for (String text : new String[] {"kiro", "L2", "2+", "kiro+", "no such pool!"}) {
            for (TierSelectorParser.Source source : TierSelectorParser.Source.values()) {
                TierSelectorParser.Selector dropped = TierSelectorParser.unchoosable(TierSelectorParser.parse(source, text));

                // A header that names no usable pool: the planner serves in the tiers' order and says it did not use it.
                assertThat(dropped.kind()).as(text).isEqualTo(TierSelectorParser.Kind.INVALID);
                assertThat(dropped.source()).isEqualTo(TierSelectorParser.Source.HEADER);
                assertThat(dropped.text()).isEqualTo(text);
            }
        }
        // Nothing named, or the automatic choice, is nothing to drop.
        assertThat(TierSelectorParser.unchoosable(null)).isNull();
        TierSelectorParser.Selector auto = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "auto");
        assertThat(TierSelectorParser.unchoosable(auto)).isSameAs(auto);
    }
}
