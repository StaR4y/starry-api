package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;

/**
 * A pair is listed in the registry only with every fixture of design 7.14 in the tree, whether it is EXPERIMENTAL or
 * VERIFIED. The first three tests are the check on the real tree and the registry; the others check the check, on trees
 * written for the purpose.
 */
class PairRegistryCompletenessTest {

    private static final PairKey MESSAGES_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic");
    private static final PairKey RESPONSES_OPENAI = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai");

    // ---- the registry and the tree of this release ----

    @Test
    void everyPairTheRegistryOffersHasAllTheRequiredFixtures() {
        assertThat(XprotoFixtures.completenessProblems(PairRegistry.defaults(), XprotoFixtures.classpathRoot())).isEmpty();
    }

    @Test
    void theFixtureTreeHasOnlyWhatTheLayoutAllows() {
        assertThat(XprotoFixtures.structureProblems(XprotoFixtures.classpathRoot())).isEmpty();
    }

    @Test
    void theFixturesHoldPlaceholdersOnly() {
        assertThat(XprotoFixtures.placeholderProblems(XprotoFixtures.classpathRoot())).isEmpty();
    }

    // ---- the catalog of design 7.14 ----

    @Test
    void theRequiredCasesHaveDistinctDirectoryNamesAndAreNeededBySomeEntry() {
        Set<String> ids = new HashSet<>();
        for (RequiredCase required : RequiredCase.values()) {
            assertThat(required.id()).matches("[a-z0-9]+(-[a-z0-9]+)*").doesNotStartWith(XprotoFixtures.EXTRA_PREFIX);
            assertThat(ids.add(required.id())).as(required.id()).isTrue();
            assertThat(RequiredCase.ofId(required.id())).isSameAs(required);
            assertThat(Entry.values()).anyMatch(required::appliesTo);
        }
        assertThat(RequiredCase.ofId("no-such-case")).isNull();
    }

    @Test
    void everyEntryNeedsTheCommonCasesAndOnlyTheCasesItHasAFormOf() {
        for (Entry entry : Entry.values()) {
            assertThat(List.of(RequiredCase.PLAIN_TEXT, RequiredCase.PARALLEL_TOOLS, RequiredCase.TOOL_LOOP_TWO_ROUNDS,
                RequiredCase.VISION_ON, RequiredCase.THINKING_EFFORT, RequiredCase.UPSTREAM_402, RequiredCase.UNICODE,
                RequiredCase.WHITELIST_PART_LEVEL)).allMatch(required -> required.appliesTo(entry));
        }
        assertThat(RequiredCase.STOP_SEQUENCES_UP_TO_4.appliesTo(Entry.RESPONSES)).isFalse();
        assertThat(RequiredCase.STOP_SEQUENCES_UP_TO_4.appliesTo(Entry.GEMINI)).isTrue();
        assertThat(RequiredCase.TOOL_SEARCH_TOOL.appliesTo(Entry.MESSAGES)).isTrue();
        assertThat(RequiredCase.TOOL_SEARCH_TOOL.appliesTo(Entry.CHAT)).isFalse();
        assertThat(RequiredCase.CACHE_CONTROL_PART_LEVEL.appliesTo(Entry.CHAT)).isTrue();
        assertThat(RequiredCase.CACHE_CONTROL_PART_LEVEL.appliesTo(Entry.GEMINI)).isFalse();
    }

    // ---- the check, on trees written for the purpose ----

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"EXPERIMENTAL", "VERIFIED"})
    void aCompleteTreeSatisfiesAPairOfEitherStatus(Status status, @TempDir Path root) {
        PairRegistry registry = registry(status);
        XprotoFixtures.writeCompleteTree(root, registry);

        assertThat(XprotoFixtures.completenessProblems(registry, root)).isEmpty();
        assertThat(XprotoFixtures.structureProblems(root)).isEmpty();
        assertThat(XprotoFixtures.placeholderProblems(root)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"EXPERIMENTAL", "VERIFIED"})
    void aMissingCaseIsReportedForAPairOfEitherStatus(Status status, @TempDir Path root) throws IOException {
        PairRegistry registry = registry(status);
        XprotoFixtures.writeCompleteTree(root, registry);
        deleteTree(XprotoFixtures.directory(root, MESSAGES_ANTHROPIC).resolve("tool-search-tool"));

        assertThat(XprotoFixtures.completenessProblems(registry, root))
            .containsExactly("OPENAI_CHAT_RELAY/MESSAGES/anthropic/tool-search-tool is missing");
    }

    @Test
    void everyMissingCaseIsReported(@TempDir Path root) throws IOException {
        PairRegistry registry = registry(Status.EXPERIMENTAL);
        XprotoFixtures.writeCompleteTree(root, registry);
        deleteTree(XprotoFixtures.directory(root, RESPONSES_OPENAI).resolve("unicode"));
        deleteTree(XprotoFixtures.directory(root, RESPONSES_OPENAI).resolve("upstream-402"));
        deleteTree(XprotoFixtures.directory(root, MESSAGES_ANTHROPIC).resolve("thinking-none"));

        assertThat(XprotoFixtures.completenessProblems(registry, root)).containsExactlyInAnyOrder(
            "OPENAI_CHAT_RELAY/RESPONSES/openai/unicode is missing",
            "OPENAI_CHAT_RELAY/RESPONSES/openai/upstream-402 is missing",
            "OPENAI_CHAT_RELAY/MESSAGES/anthropic/thinking-none is missing");
    }

    @Test
    void aPairWithoutAnyFixtureIsReportedOnce(@TempDir Path root) {
        assertThat(XprotoFixtures.completenessProblems(registry(Status.EXPERIMENTAL), root)).containsExactlyInAnyOrder(
            "OPENAI_CHAT_RELAY/MESSAGES/anthropic is missing (EXPERIMENTAL OPENAI_CHAT_RELAY for MESSAGES requests of anthropic)",
            "OPENAI_CHAT_RELAY/RESPONSES/openai is missing (EXPERIMENTAL OPENAI_CHAT_RELAY for RESPONSES requests of openai)");
    }

    @Test
    void aCaseWithoutProvenanceInputOrExpectedOutputIsReported(@TempDir Path root) throws IOException {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai", Status.EXPERIMENTAL)));
        XprotoFixtures.writeCompleteTree(root, registry);
        Path pair = XprotoFixtures.directory(root, RESPONSES_OPENAI);
        // No case.json, one that is not JSON, one without an origin, and one with a blank origin.
        Files.delete(pair.resolve("plain-text").resolve("case.json"));
        XprotoFixtures.write(pair.resolve("system-string").resolve("case.json"), "not json");
        XprotoFixtures.write(pair.resolve("system-array").resolve("case.json"), "{\"client\":\"codex\"}");
        XprotoFixtures.write(pair.resolve("multi-turn").resolve("case.json"), "{\"origin\":\"  \"}");
        // A case with no input, and one with no expected file.
        deleteTree(pair.resolve("single-tool").resolve("input"));
        deleteTree(pair.resolve("parallel-tools").resolve("expected"));

        assertThat(XprotoFixtures.completenessProblems(registry, root)).containsExactlyInAnyOrder(
            "OPENAI_CHAT_RELAY/RESPONSES/openai/plain-text/case.json has no origin",
            "OPENAI_CHAT_RELAY/RESPONSES/openai/system-string/case.json has no origin",
            "OPENAI_CHAT_RELAY/RESPONSES/openai/system-array/case.json has no origin",
            "OPENAI_CHAT_RELAY/RESPONSES/openai/multi-turn/case.json has no origin",
            "OPENAI_CHAT_RELAY/RESPONSES/openai/single-tool/input has no file",
            "OPENAI_CHAT_RELAY/RESPONSES/openai/parallel-tools/expected has no file");
    }

    @Test
    void aPairThatIsNotOfferedNeedsNoFixtures(@TempDir Path root) {
        assertThat(XprotoFixtures.completenessProblems(new PairRegistry(List.of()), root)).isEmpty();
        assertThat(XprotoFixtures.completenessProblems(registry(Status.EXPERIMENTAL), root)).hasSize(2);
    }

    @Test
    void anEntryIsOnlyAskedForTheCasesItHas(@TempDir Path root) {
        PairRegistry registry = registry(Status.EXPERIMENTAL);
        XprotoFixtures.writeCompleteTree(root, registry);

        // Responses has no stop sequences and Messages does: the complete tree has the second's and not the first's.
        assertThat(XprotoFixtures.directory(root, RESPONSES_OPENAI).resolve("stop-sequences-over-4")).doesNotExist();
        assertThat(XprotoFixtures.directory(root, MESSAGES_ANTHROPIC).resolve("stop-sequences-over-4")).exists();
        assertThat(XprotoFixtures.completenessProblems(registry, root)).isEmpty();
    }

    // ---- the layout ----

    @Test
    void aTreeOfNothingButTheLayoutHasNoStructureProblem(@TempDir Path root) {
        XprotoFixtures.writeCompleteTree(root, registry(Status.EXPERIMENTAL));
        XprotoFixtures.writeCase(root, MESSAGES_ANTHROPIC, "extra-claude-code-compaction", "capture",
            Map.of("request.json", "{}"), Map.of("client-response.json", "{}"));
        // Fixtures of a pair that is not offered yet are allowed: they are written before the pair is listed.
        XprotoFixtures.writeCase(root, new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.GEMINI, "google"), "plain-text",
            "capture", Map.of("request.json", "{}"), Map.of("client-response.json", "{}"));

        assertThat(XprotoFixtures.structureProblems(root)).isEmpty();
    }

    @Test
    void whatIsNotPartOfTheLayoutIsReported(@TempDir Path root) {
        XprotoFixtures.writeCase(root, MESSAGES_ANTHROPIC, "plain-text", "capture", Map.of("request.json", "{}"), Map.of("client-response.json", "{}"));
        XprotoFixtures.write(root.resolve("OPENAI_RESPONSES_RELAY").resolve("CHAT").resolve("anthropic").resolve("plain-text").resolve("case.json"), "{}");
        XprotoFixtures.write(root.resolve("OPENAI_CHAT_RELAY").resolve("BATCH").resolve("anthropic").resolve("plain-text").resolve("case.json"), "{}");
        XprotoFixtures.write(root.resolve("OPENAI_CHAT_RELAY").resolve("CHAT").resolve("deepseek").resolve("plain-text").resolve("case.json"), "{}");
        XprotoFixtures.write(root.resolve("OPENAI_CHAT_RELAY").resolve("CHAT").resolve("anthropic").resolve("plain-txt").resolve("case.json"), "{}");
        XprotoFixtures.write(root.resolve("OPENAI_CHAT_RELAY").resolve("CHAT").resolve("anthropic").resolve("plain-text").resolve("notes.txt"), "x");
        XprotoFixtures.write(root.resolve("OPENAI_CHAT_RELAY").resolve("CHAT").resolve("anthropic").resolve("notes.txt"), "x");
        XprotoFixtures.write(root.resolve("README.md"), "x");

        assertThat(XprotoFixtures.structureProblems(root)).containsExactlyInAnyOrder(
            "OPENAI_RESPONSES_RELAY is not a source kind directory",
            "OPENAI_CHAT_RELAY/BATCH is not an entry directory",
            "OPENAI_CHAT_RELAY/CHAT/deepseek is not a vendor directory",
            "OPENAI_CHAT_RELAY/CHAT/anthropic/plain-txt is neither a required case of design 7.14 nor named extra-...",
            "OPENAI_CHAT_RELAY/CHAT/anthropic/notes.txt is neither a required case of design 7.14 nor named extra-...",
            "OPENAI_CHAT_RELAY/CHAT/anthropic/plain-text/notes.txt is not one of case.json, input/, expected/",
            "README.md is not a source kind directory");
    }

    // ---- placeholders ----

    @Test
    void aLiveKeyTokenOrIdIsReportedAndAPlaceholderIsNot(@TempDir Path root) {
        XprotoFixtures.write(root.resolve("a.json"), """
            {"user_id":"user_x_account_00000000-0000-0000-0000-000000000000_session_AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA",
             "session":"11111111-1111-1111-1111-111111111111",
             "authorization":"Bearer <redacted>",
             "key":"sk-XXXX", "short":"Bearer abc"}
            """);
        assertThat(XprotoFixtures.placeholderProblems(root)).isEmpty();

        XprotoFixtures.write(root.resolve("b.json"), """
            {"session":"3f6c2b1e-9d0a-4c55-8f7e-2a1b3c4d5e6f",
             "user_id":"user_x_account_9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D_session_1"}
            """);
        XprotoFixtures.write(root.resolve("nested").resolve("c.sse"), """
            data: {"authorization":"Bearer abcdef0123456789abcdef"}
            data: {"key":"sk-ant-api03-abcdefghijklmnop"}
            """);

        assertThat(XprotoFixtures.placeholderProblems(root)).containsExactlyInAnyOrder(
            "b.json holds the id 3f6c2b1e-9d0a-4c55-8f7e-2a1b3c4d5e6f, which is not a placeholder",
            "b.json holds the id 9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D, which is not a placeholder",
            "nested/c.sse holds Bearer abcdef0123456789a..., which is not a placeholder",
            "nested/c.sse holds sk-ant-api03-abcdefghijk..., which is not a placeholder");
    }

    // ---- the repository keeps the bytes ----

    @Test
    void theRepositoryDoesNotRewriteTheLineEndingsOfTheFixtures() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve(".gitattributes"))) {
            directory = directory.getParent();
        }
        Assumptions.assumeTrue(directory != null, "run outside the repository");

        assertThat(Files.readAllLines(directory.resolve(".gitattributes")))
            .contains("service/src/test/resources/xproto/** -text");
    }

    private static PairRegistry registry(Status status) {
        return new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", status),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai", status)));
    }

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
