package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.platform.xproto.XprotoFixtures.Case;

/**
 * The contract of every pair the registry offers: each case of its fixture directory, run by the pair's harness, must
 * produce the files of the case's {@code expected} directory byte for byte, no more and no fewer. The fixtures are the
 * contract; a converter change that moves one byte of what a relay is sent or a client is answered changes a fixture on
 * purpose, in the same commit, or the build fails.
 *
 * <p>What a harness does is the pair's own: for a pair that is served it takes the client request and the relay's
 * scripted answers from {@code input/} and returns what the gateway sent the relay, what it answered the client and what
 * it billed. The harness of every pair the release offers is {@link XpPairHarnesses}'s: the gateway's own entry, planner,
 * dispatcher and relay client against a relay that answers as the case says. The expected directories are rewritten, on
 * purpose, by {@link XprotoFixtureRecorder}.
 */
class CrossProtocolContractTest {

    /** Runs one case of a pair and returns the files that case's {@code expected} directory holds, by relative path. */
    @FunctionalInterface
    interface PairHarness {
        Map<String, byte[]> run(Case fixture) throws Exception;
    }

    /** The harnesses of the pairs this release offers: the gateway's own code, played against a relay that answers as the case says. */
    private static final Map<PairKey, PairHarness> HARNESSES = XpPairHarnesses.forTheOfferedPairs();

    private static final PairKey MESSAGES_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic");
    private static final PairKey CHAT_OPENAI = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai");

    /** What is wrong with the pairs {@code registry} offers: no harness, a harness that fails, or bytes that differ. */
    static List<String> failures(Path root, PairRegistry registry, Map<PairKey, PairHarness> harnesses) {
        List<String> failures = new ArrayList<>();
        for (PairKey pair : registry.offeredPairs()) {
            PairHarness harness = harnesses.get(pair);
            if (harness == null) {
                failures.add(pair.kind() + "/" + pair.entry() + "/" + pair.vendor() + " is offered but nothing replays its fixtures");
                continue;
            }
            for (Case fixture : XprotoFixtures.cases(root, pair)) {
                failures.addAll(failures(fixture, harness));
            }
        }
        return failures;
    }

    private static List<String> failures(Case fixture, PairHarness harness) {
        String where = fixture.pair().entry() + "/" + fixture.pair().vendor() + "/" + fixture.id();
        Map<String, byte[]> expected = fixture.expected();
        if (expected.isEmpty()) {
            return List.of(where + " expects nothing");
        }
        Map<String, byte[]> produced;
        try {
            produced = harness.run(fixture);
        } catch (Exception | AssertionError error) {
            return List.of(where + " failed to run: " + error);
        }
        List<String> failures = new ArrayList<>();
        expected.forEach((name, bytes) -> {
            byte[] actual = produced.get(name);
            if (actual == null) {
                failures.add(where + "/" + name + " was not produced");
            } else {
                XprotoFixtures.difference(where + "/" + name, bytes, actual).ifPresent(failures::add);
            }
        });
        produced.keySet().stream().filter(name -> !expected.containsKey(name))
            .forEach(name -> failures.add(where + "/" + name + " was produced but the case does not expect it"));
        return failures;
    }

    // ---- the contract of this release ----

    @Test
    void everyPairTheRegistryOffersProducesItsFixtures() {
        assertThat(failures(XprotoFixtures.classpathRoot(), PairRegistry.defaults(), HARNESSES)).isEmpty();
    }

    // ---- the harness of the framework, on trees written for the purpose ----

    @Test
    void aHarnessThatProducesTheExpectedBytesPassesEveryCase(@TempDir Path root) {
        PairRegistry registry = registry(Status.EXPERIMENTAL);
        XprotoFixtures.writeCompleteTree(root, registry);
        List<String> ran = new ArrayList<>();

        List<String> failures = failures(root, registry, Map.of(
            MESSAGES_ANTHROPIC, echo(ran),
            CHAT_OPENAI, echo(ran)));

        assertThat(failures).isEmpty();
        long cases = registry.offeredPairs().stream()
            .mapToLong(pair -> Arrays.stream(RequiredCase.values()).filter(required -> required.appliesTo(pair.entry())).count())
            .sum();
        assertThat(ran).hasSize((int) cases).doesNotHaveDuplicates();
        assertThat(ran).contains("MESSAGES/anthropic/plain-text", "CHAT/openai/unicode");
    }

    @Test
    void everyCaseOfAnOfferedPairRunsAndOnlyOfferedPairsRun(@TempDir Path root) {
        PairRegistry offeredOnlyMessages = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.VERIFIED)));
        XprotoFixtures.writeCompleteTree(root, registry(Status.EXPERIMENTAL));
        XprotoFixtures.writeCase(root, MESSAGES_ANTHROPIC, "extra-claude-code-compaction", "capture",
            Map.of("request.json", "{}\n"), Map.of("client-response.json", "{}\n"));
        List<String> ran = new ArrayList<>();

        assertThat(failures(root, offeredOnlyMessages, Map.of(MESSAGES_ANTHROPIC, echo(ran)))).isEmpty();

        // The extra case runs with the required ones; the cases of the Chat pair, which is not offered here, do not.
        assertThat(ran).contains("MESSAGES/anthropic/extra-claude-code-compaction")
            .allMatch(name -> name.startsWith("MESSAGES/anthropic/"));
    }

    @Test
    void aTrailingNewlineIsADifference(@TempDir Path root) {
        PairRegistry registry = registry(Status.EXPERIMENTAL);
        XprotoFixtures.writeCompleteTree(root, registry);

        List<String> failures = failures(root, registry, Map.of(
            MESSAGES_ANTHROPIC, fixture -> Map.of("client-response.json", withoutTrailingNewline(fixture.input("request.json"))),
            CHAT_OPENAI, echo(new ArrayList<>())));

        assertThat(failures).hasSize((int) Arrays.stream(RequiredCase.values())
            .filter(required -> required.appliesTo(Entry.MESSAGES)).count());
        assertThat(failures).allMatch(failure -> failure.startsWith("MESSAGES/anthropic/") && failure.contains("client-response.json differs at byte"));
        assertThat(failures.get(0)).contains("the end of the file").contains("\"\\n\"");
    }

    @Test
    void carriageReturnsAreNotLineFeeds(@TempDir Path root) {
        PairRegistry registry = registry(Status.EXPERIMENTAL);
        XprotoFixtures.writeCompleteTree(root, registry);

        List<String> failures = failures(root, registry, Map.of(
            MESSAGES_ANTHROPIC, fixture -> Map.of("client-response.json",
                new String(fixture.input("request.json"), StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)),
            CHAT_OPENAI, echo(new ArrayList<>())));

        assertThat(failures).isNotEmpty().allMatch(failure -> failure.contains("expected \"\\n\" but was \"\\r\\n\""));
    }

    @Test
    void aFileThatIsNotProducedOrNotExpectedIsAFailure(@TempDir Path root) {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", Status.EXPERIMENTAL)));
        XprotoFixtures.writeCase(root, CHAT_OPENAI, "plain-text", "capture", Map.of("request.json", "{}\n"),
            Map.of("client-response.json", "{}\n", "relay-request.json", "{}\n"));

        assertThat(failures(root, registry, Map.of(CHAT_OPENAI, fixture ->
            Map.of("client-response.json", fixture.input("request.json"))))).containsExactly(
            "CHAT/openai/plain-text/relay-request.json was not produced");
        assertThat(failures(root, registry, Map.of(CHAT_OPENAI, fixture -> Map.of(
            "client-response.json", fixture.input("request.json"),
            "relay-request.json", fixture.input("request.json"),
            "billing.json", fixture.input("request.json"))))).containsExactly(
            "CHAT/openai/plain-text/billing.json was produced but the case does not expect it");
    }

    @Test
    void aFileInASubdirectoryIsComparedByItsPath(@TempDir Path root) {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", Status.EXPERIMENTAL)));
        XprotoFixtures.writeCase(root, CHAT_OPENAI, "plain-text", "capture", Map.of("turns/1/request.json", "a"),
            Map.of("turns/1/client-response.json", "a", "turns/2/client-response.json", "b"));

        assertThat(failures(root, registry, Map.of(CHAT_OPENAI, fixture -> Map.of(
            "turns/1/client-response.json", fixture.input("turns/1/request.json"),
            "turns/2/client-response.json", "b".getBytes(StandardCharsets.UTF_8))))).isEmpty();
        assertThat(fixtureFiles(root, "input")).containsExactly("turns/1/request.json");
        assertThat(fixtureFiles(root, "expected")).containsExactly("turns/1/client-response.json", "turns/2/client-response.json");
    }

    @Test
    void aCaseThatExpectsNothingIsAFailureNotAPass(@TempDir Path root) {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", Status.EXPERIMENTAL)));
        XprotoFixtures.writeCase(root, CHAT_OPENAI, "plain-text", "capture", Map.of("request.json", "{}"), Map.of());

        assertThat(failures(root, registry, Map.of(CHAT_OPENAI, fixture -> Map.of()))).containsExactly("CHAT/openai/plain-text expects nothing");
    }

    @Test
    void aHarnessThatFailsFailsOnlyItsCase(@TempDir Path root) {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", Status.EXPERIMENTAL)));
        XprotoFixtures.writeCase(root, CHAT_OPENAI, "plain-text", "capture", Map.of("request.json", "{}\n"), Map.of("client-response.json", "{}\n"));
        XprotoFixtures.writeCase(root, CHAT_OPENAI, "unicode", "capture", Map.of("request.json", "{}\n"), Map.of("client-response.json", "{}\n"));

        List<String> failures = failures(root, registry, Map.of(CHAT_OPENAI, fixture -> {
            if (fixture.id().equals("plain-text")) {
                throw new IllegalStateException("the converter refused the request");
            }
            if (fixture.id().equals("unicode")) {
                throw new AssertionError("an assertion in the harness");
            }
            return Map.of();
        }));

        assertThat(failures).containsExactly(
            "CHAT/openai/plain-text failed to run: java.lang.IllegalStateException: the converter refused the request",
            "CHAT/openai/unicode failed to run: java.lang.AssertionError: an assertion in the harness");
    }

    @Test
    void anOfferedPairWithoutAHarnessIsAFailure(@TempDir Path root) {
        PairRegistry registry = registry(Status.VERIFIED);
        XprotoFixtures.writeCompleteTree(root, registry);

        assertThat(failures(root, registry, Map.of(MESSAGES_ANTHROPIC, echo(new ArrayList<>())))).containsExactly(
            "OPENAI_CHAT_RELAY/CHAT/openai is offered but nothing replays its fixtures");
    }

    @Test
    void theByteWhereTwoFilesDifferIsNamedWithItsLineAndColumn() {
        byte[] expected = "{\n  \"a\": 1,\n  \"b\": \"é\"\n}\n".getBytes(StandardCharsets.UTF_8);
        byte[] actual = "{\n  \"a\": 1,\n  \"b\": \"e\"\n}\n".getBytes(StandardCharsets.UTF_8);

        assertThat(XprotoFixtures.difference("a.json", expected, actual)).hasValueSatisfying(message -> assertThat(message)
            .startsWith("a.json differs at byte 20 (line 3, column 9):")
            .contains("(" + expected.length + " bytes expected, " + actual.length + " produced)"));
        assertThat(XprotoFixtures.difference("a.json", expected, expected.clone())).isEmpty();
        assertThat(XprotoFixtures.difference("a.json", new byte[0], new byte[0])).isEmpty();
        assertThat(XprotoFixtures.difference("a.json", new byte[0], new byte[] {'x'})).hasValueSatisfying(message ->
            assertThat(message).contains("expected the end of the file but was \"x\""));
        assertThat(XprotoFixtures.difference("a.json", "ab".getBytes(StandardCharsets.UTF_8), "a".getBytes(StandardCharsets.UTF_8)))
            .hasValueSatisfying(message -> assertThat(message).contains("expected \"b\" but was the end of the file"));
    }

    @Test
    void aTabAndAControlByteAreShownAsEscapes() {
        assertThat(XprotoFixtures.difference("a", "x\ty".getBytes(StandardCharsets.UTF_8), "x\u0001y".getBytes(StandardCharsets.UTF_8)))
            .hasValueSatisfying(message -> assertThat(message).contains("expected \"\\ty\" but was \"\\u0001y\""));
    }

    private static PairHarness echo(List<String> ran) {
        return fixture -> {
            ran.add(fixture.pair().entry() + "/" + fixture.pair().vendor() + "/" + fixture.id());
            return Map.of("client-response.json", fixture.input("request.json"));
        };
    }

    private static byte[] withoutTrailingNewline(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length - 1);
    }

    private static List<String> fixtureFiles(Path root, String directory) {
        return List.copyOf(XprotoFixtures.files(
            XprotoFixtures.directory(root, CHAT_OPENAI).resolve("plain-text").resolve(directory)).keySet());
    }

    private static PairRegistry registry(Status status) {
        return new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", status),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", status)));
    }
}
