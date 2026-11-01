package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.XpCacheEvidence.Call;
import tech.starryapi.sourcetier.XpCacheEvidence.Finding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the cache probe may conclude (design 5.8, 7.4): the same prompt twice, and from the counts of both answers
 * whether the relay's prompt tokens contain its cache read and its cache write. The relays that exist are the examples:
 * Anthropic counts neither cache in its input, OpenAI and DeepSeek count the read in the prompt. What the counts do
 * not settle stays a null, and a null is never evidence for "not contained", which is the claim that bills the cache
 * twice if it is wrong.
 */
class XpCacheEvidenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Call call(Long prompt, long read, long write) {
        return new Call(prompt, read, write);
    }

    @Test
    void aRelayThatKeepsBothCachesOutOfThePromptIsConcludedOnBoth() {
        // Anthropic: the first call writes the cache, the second reads it, and input_tokens is what is left over.
        Finding finding = XpCacheEvidence.analyze(List.of(call(3L, 0, 1240), call(3L, 1240, 0)));

        assertThat(finding.readInPrompt()).isFalse();
        assertThat(finding.writeInPrompt()).isFalse();
        assertThat(finding.readEvidence()).contains("call 2: prompt 3, cache read 1240").contains("does not contain");
        assertThat(finding.writeEvidence()).contains("call 1: prompt 3, cache write 1240");
        assertThat(finding.message()).isNull();
    }

    @Test
    void aRelayWhosePromptContainsTheReadIsConcludedOnTheReadOnly() {
        // OpenAI: prompt_tokens is the whole prompt, cached_tokens a part of it, and nothing is ever written.
        Finding finding = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1230L, 1024, 0)));

        assertThat(finding.readInPrompt()).isTrue();
        assertThat(finding.readEvidence()).contains("the prompt contains it");
        assertThat(finding.writeInPrompt()).as("nothing was written, so nothing is known about it").isNull();
        assertThat(finding.writeEvidence()).isNull();
    }

    @Test
    void aRelayThatReportsHitsAndMissesIsReadAsOneWhosePromptContainsTheHits() {
        // DeepSeek has no prompt count of its own: the prompt is the hits and the misses.
        Finding finding = XpCacheEvidence.analyze(List.of(
            Call.of(RelayUsageNormalizer.read(JSON.readTree("{\"prompt_cache_hit_tokens\":0,\"prompt_cache_miss_tokens\":1230}"))),
            Call.of(RelayUsageNormalizer.read(JSON.readTree("{\"prompt_cache_hit_tokens\":1024,\"prompt_cache_miss_tokens\":206}")))));

        assertThat(finding.calls()).extracting(Call::prompt).containsExactly(1230L, 1230L);
        assertThat(finding.readInPrompt()).isTrue();
    }

    @Test
    void aRelayThatCachedNothingProvesNothing() {
        Finding finding = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1230L, 0, 0)));

        assertThat(finding.readInPrompt()).isNull();
        assertThat(finding.writeInPrompt()).isNull();
        assertThat(finding.message()).contains("no cached tokens");
    }

    @Test
    void aMissingPromptCountProvesNothing() {
        assertThat(XpCacheEvidence.analyze(List.of(call(null, 0, 1240), call(3L, 1240, 0))).readInPrompt()).isNull();
        assertThat(XpCacheEvidence.analyze(List.of(call(0L, 0, 1240), call(3L, 1240, 0))).message()).contains("prompt tokens");
        assertThat(XpCacheEvidence.analyze(List.of(call(3L, 1240, 0))).readInPrompt()).isNull();
        assertThat(XpCacheEvidence.analyze(List.of()).readInPrompt()).isNull();
    }

    @Test
    void figuresThatFitNoReadingProveNothing() {
        // The prompt weighs 1,230 in one call and 700 in the other, whatever is added to it.
        Finding finding = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(700L, 100, 0)));

        assertThat(finding.readInPrompt()).isNull();
        assertThat(finding.writeInPrompt()).isNull();
        assertThat(finding.message()).contains("fit no reading");
    }

    @Test
    void aSmallCacheNextToTheMarginOfTheCountsIsNotEvidenceOfAnything() {
        // A cached count of 3 is inside the margin of four tokens: both readings add up, so neither is shown.
        Finding finding = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1230L, 3, 0)));

        assertThat(finding.readInPrompt()).isNull();
        assertThat(finding.message()).contains("too small");
    }

    @Test
    void theMarginBetweenAgreeingAndRejectedSizesLeavesTheConclusionOpen() {
        // The two sizes differ by 20 (margin 13, so 13 < 20 <= 39): neither agreeing nor clearly different.
        Finding finding = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1210L, 20, 0)));

        assertThat(finding.readInPrompt()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("relays")
    void theReadingsOfRelaysThatExist(String name, List<Call> calls, Boolean read, Boolean write) {
        Finding finding = XpCacheEvidence.analyze(calls);

        assertThat(finding.readInPrompt()).isEqualTo(read);
        assertThat(finding.writeInPrompt()).isEqualTo(write);
    }

    static Stream<Arguments> relays() {
        return Stream.of(
            Arguments.of("Anthropic through a relay, warm both times", List.of(call(5L, 1240L, 0), call(5L, 1240L, 0)), false, null),
            Arguments.of("Anthropic, write and read", List.of(call(5L, 0, 1240), call(5L, 1240, 0)), false, false),
            Arguments.of("a relay that counts the write but not the read",
                List.of(call(1245L, 0, 1240), call(5L, 1240, 0)), false, true),
            // The write of the first call and the read of the second are the same size, so adding both to the prompt gives
            // the same total as adding neither: the counts cannot tell the two apart, and nothing is concluded.
            Arguments.of("a relay that counts both in the prompt, with a write as big as the read",
                List.of(call(1245L, 0, 1240), call(1245L, 1240, 0)), null, null),
            Arguments.of("a relay that counts both in the prompt, with a write of another size",
                List.of(call(1245L, 0, 600), call(1245L, 1240, 0)), true, true),
            Arguments.of("OpenAI, cold then warm", List.of(call(1230L, 0, 0), call(1230L, 1152, 0)), true, null),
            Arguments.of("warm both times and the write is reported once",
                List.of(call(1245L, 1240, 1240), call(1245L, 1240, 0)), false, true)
        );
    }

    @Test
    void whatIsConcludedNeverContradictsTheCountsAndNeverRestsOnACacheThatIsTooSmall() {
        Random random = new Random(20261026L);
        int readConcluded = 0;
        int writeConcluded = 0;
        int notContained = 0;
        for (int round = 0; round < 3000; round++) {
            long size = 800 + random.nextInt(900);
            boolean readIn = random.nextBoolean();
            boolean writeIn = random.nextBoolean();
            long read = random.nextInt(4) == 0 ? 0 : random.nextInt((int) size);
            long write = random.nextInt(4) == 0 ? 0 : random.nextInt((int) size - 1);
            if (readIn && writeIn && read + write > size) {
                write = size - read;
            }
            // A relay of the kind (readIn, writeIn) reports the cold call with the write and the warm one with the read.
            List<Call> calls = List.of(report(size, 0, write, readIn, writeIn), report(size, read, 0, readIn, writeIn));

            Finding finding = XpCacheEvidence.analyze(calls);

            if (finding.readInPrompt() != null) {
                assertThat(finding.readInPrompt()).as("%s", calls).isEqualTo(readIn);
                readConcluded++;
                notContained += finding.readInPrompt() ? 0 : 1;
            }
            if (finding.writeInPrompt() != null) {
                assertThat(finding.writeInPrompt()).as("%s", calls).isEqualTo(writeIn);
                writeConcluded++;
                notContained += finding.writeInPrompt() ? 0 : 1;
            }
        }
        // The run is not vacuous: it concludes often, and in both directions.
        assertThat(readConcluded).isGreaterThan(500);
        assertThat(writeConcluded).isGreaterThan(200);
        assertThat(notContained).isGreaterThan(300);
    }

    private static Call report(long size, long read, long write, boolean readIn, boolean writeIn) {
        return call(size - (readIn ? 0 : read) - (writeIn ? 0 : write), read, write);
    }

    // ---- the stored form ----

    private static final Member MEMBER = new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false,
        true, "NONE", 540, true, true);
    private static final MemberModel MODEL = new MemberModel("anthropic", "relay-1", "Claude-Sonnet-4.5", "relay/sonnet", 200_000, 8192,
        true, null, null);

    private static ProxyAccountEntity account(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("relay-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        return account;
    }

    @Test
    void theBasisIsTheRelayTheModelAndTheWayTheAnswerIsReadAndNothingTheAdministratorActsOn() {
        String basis = XpCacheEvidence.basis(MEMBER, MODEL, account("https://relay.example/v1"));

        assertThat(basis).hasSize(64).matches("[0-9a-f]+");
        // Acting on the evidence, or moving the tier and the limits, leaves it standing.
        Member acted = new Member("anthropic", "relay-1", "other", "OPENAI_CHAT", false, true, true, true, true, true, "EFFORT", 100, false, false);
        MemberModel limited = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "relay/sonnet", 1000, 1, false, Instant.EPOCH, "x");
        assertThat(XpCacheEvidence.basis(acted, limited, account("https://relay.example/v1"))).isEqualTo(basis);
        // Another relay, another name, another way of reading the answer: another basis.
        assertThat(XpCacheEvidence.basis(MEMBER, MODEL, account("https://other.example/v1"))).isNotEqualTo(basis);
        assertThat(XpCacheEvidence.basis(MEMBER, new MemberModel("anthropic", "relay-1", "Claude-Sonnet-4.5", "relay/sonnet-2", 1, 1, true, null, null),
            account("https://relay.example/v1"))).isNotEqualTo(basis);
        assertThat(XpCacheEvidence.basis(new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false,
            false, "NONE", 540, true, true), MODEL, account("https://relay.example/v1"))).isNotEqualTo(basis);
        assertThat(XpCacheEvidence.basis(MEMBER, new MemberModel("anthropic", "relay-1", "claude-haiku-4.5", "relay/sonnet", 1, 1, true, null, null),
            account("https://relay.example/v1"))).isNotEqualTo(basis);
    }

    @Test
    void theEvidenceIsStoredWithItsBasisAndReadBackAndCarriedOnlyForTheSameBasis() {
        Finding finding = XpCacheEvidence.analyze(List.of(call(3L, 0, 1240), call(3L, 1240, 0)));
        String summary = "{\"passed\":true,\"cache\":" + XpCacheEvidence.toJson(finding, "abc", Instant.parse("2026-10-26T08:00:00Z")) + "}";

        XpCacheEvidence.Stored stored = XpCacheEvidence.fromSummary(summary).orElseThrow();
        assertThat(stored.readInPrompt()).isFalse();
        assertThat(stored.writeInPrompt()).isFalse();
        assertThat(stored.basis()).isEqualTo("abc");
        assertThat(stored.observedAt()).isEqualTo(Instant.parse("2026-10-26T08:00:00Z"));

        // Only the evidence stays when a verification goes, and only for the basis it was seen for.
        assertThat(JSON.readTree(XpCacheEvidence.carried(summary, "abc")).propertyNames()).containsExactly("cache");
        assertThat(XpCacheEvidence.carried(summary, "another")).isNull();
        assertThat(XpCacheEvidence.carried("{\"passed\":true}", "abc")).isNull();
        assertThat(XpCacheEvidence.carried("{}", "abc")).isNull();
    }

    @Test
    void aNullIsStoredAsNullAndAnUnreadableSummaryHasNoEvidence() {
        Finding none = XpCacheEvidence.analyze(List.of(call(10L, 0, 0), call(10L, 0, 0)));
        String summary = "{\"cache\":" + XpCacheEvidence.toJson(none, "abc", Instant.EPOCH) + "}";

        XpCacheEvidence.Stored stored = XpCacheEvidence.fromSummary(summary).orElseThrow();
        assertThat(stored.readInPrompt()).isNull();
        assertThat(stored.writeInPrompt()).isNull();
        for (String unreadable : new String[] {null, "", "  ", "not json", "[]", "{\"cache\":5}", "{\"cache\":{}}", "{\"cache\":{\"basis\":1}}"}) {
            assertThat(XpCacheEvidence.fromSummary(unreadable)).as("%s", unreadable).isEmpty();
        }
    }

    @Test
    void anEarlierConclusionStandsWhereALaterProbeConcludesNothing() {
        Finding earlier = XpCacheEvidence.analyze(List.of(call(3L, 0, 1240), call(3L, 1240, 0)));
        JsonNode before = XpCacheEvidence.toJson(earlier, "abc", Instant.parse("2026-10-26T08:00:00Z"));
        Finding nothing = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1230L, 0, 0)));
        Finding readOnly = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1230L, 1024, 0)));

        // The probe saw nothing: both stand, and the time is the probe's.
        JsonNode kept = XpCacheEvidence.merged(before, nothing, "abc", Instant.parse("2026-10-27T08:00:00Z"));
        assertThat(kept.path("read").booleanValue()).isFalse();
        assertThat(kept.path("write").booleanValue()).isFalse();
        assertThat(kept.path("at").asString("")).isEqualTo("2026-10-27T08:00:00Z");
        // The probe concluded the read the other way: that replaces it, and the write it said nothing of stands.
        JsonNode changed = XpCacheEvidence.merged(before, readOnly, "abc", Instant.parse("2026-10-27T08:00:00Z"));
        assertThat(changed.path("read").booleanValue()).isTrue();
        assertThat(changed.path("write").booleanValue()).isFalse();
        // No probe at all: the evidence as it was.
        assertThat(XpCacheEvidence.merged(before, null, "abc", Instant.EPOCH)).isEqualTo(before);
        assertThat(XpCacheEvidence.merged(before, null, "abc", Instant.EPOCH)).isNotSameAs(before);
    }

    @Test
    void evidenceOfAnotherBasisIsNeverCarriedIntoANewConclusion() {
        Finding earlier = XpCacheEvidence.analyze(List.of(call(3L, 0, 1240), call(3L, 1240, 0)));
        JsonNode before = XpCacheEvidence.toJson(earlier, "abc", Instant.EPOCH);
        Finding nothing = XpCacheEvidence.analyze(List.of(call(1230L, 0, 0), call(1230L, 0, 0)));

        JsonNode other = XpCacheEvidence.merged(before, nothing, "another", Instant.EPOCH);
        assertThat(other.path("basis").asString("")).isEqualTo("another");
        assertThat(other.path("read").isNull()).isTrue();
        assertThat(other.path("write").isNull()).isTrue();
        assertThat(XpCacheEvidence.merged(before, null, "another", Instant.EPOCH)).isNull();
        assertThat(XpCacheEvidence.merged(null, null, "abc", Instant.EPOCH)).isNull();
        assertThat(XpCacheEvidence.merged(null, nothing, "abc", Instant.EPOCH).path("read").isNull()).isTrue();
    }

    @Test
    void theMessageOfAnUnprovenSettingNamesWhatIsNotShown() {
        assertThat(XpCacheEvidence.unprovenMessage("cache write")).contains("not to contain the cache write").contains("cache probe");
    }

    @Test
    void theKeyOfAModelIgnoresTheCaseOfItsId() {
        assertThat(XpCacheEvidence.key("relay-1", "Claude-Sonnet-4.5")).isEqualTo(XpCacheEvidence.key("relay-1", "claude-sonnet-4.5"));
        assertThat(XpCacheEvidence.key("relay-1", "m")).isNotEqualTo(XpCacheEvidence.key("relay-2", "m"));
    }
}
