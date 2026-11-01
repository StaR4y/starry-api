package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tech.starryapi.platform.xproto.CrossProtocolContractTest.PairHarness;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.XprotoFixtures.Case;

/**
 * Rewrites the {@code expected/} directory of fixture cases from what the harness of their pair produces now. It is how a
 * change to the converter that moves a byte of what a relay is sent or a client is answered is made on purpose: run it,
 * read the diff of the fixtures as the diff of the behavior, and commit both together. It does nothing unless
 * {@code XPROTO_RECORD} names the fixture tree to rewrite (the source one, {@code service/src/test/resources/xproto}, not the
 * copy on the test classpath); {@code XPROTO_ONLY} may be a regular expression that the path {@code ENTRY/vendor/case} of a
 * case has to match.
 *
 * <pre>
 * XPROTO_RECORD=$PWD/service/src/test/resources/xproto ./gradlew :service:cleanTest :service:test --tests '*XprotoFixtureRecorder' -PskipWebuiBuild=true
 * </pre>
 *
 * ({@code cleanTest} because Gradle does not know the variable is an input of the test and would call it up to date.)
 * The cases that cannot be played are listed and the others are rewritten; what a case reads is in {@code input/} and is
 * never touched.
 */
class XprotoFixtureRecorder {

    @Test
    @EnabledIfEnvironmentVariable(named = "XPROTO_RECORD", matches = ".+")
    void rewriteTheExpectedDirectories() throws Exception {
        Path root = Path.of(System.getenv("XPROTO_RECORD"));
        String only = System.getenv("XPROTO_ONLY");
        Pattern filter = only == null || only.isBlank() ? null : Pattern.compile(only);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<PairKey, PairHarness> harness : XpPairHarnesses.forTheOfferedPairs().entrySet()) {
            PairKey pair = harness.getKey();
            for (Case fixture : XprotoFixtures.cases(root, pair)) {
                if (filter != null && !filter.matcher(pair.entry() + "/" + pair.vendor() + "/" + fixture.id()).find()) {
                    continue;
                }
                Map<String, byte[]> produced;
                try {
                    produced = harness.getValue().run(fixture);
                } catch (Exception | AssertionError failure) {
                    failures.add(pair.entry() + "/" + pair.vendor() + "/" + fixture.id() + ": " + failure);
                    continue;
                }
                Path expected = fixture.directory().resolve(XprotoFixtures.EXPECTED);
                delete(expected);
                for (Map.Entry<String, byte[]> file : produced.entrySet()) {
                    Path target = expected.resolve(file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.write(target, file.getValue());
                }
            }
        }
        assertThat(failures).as("the cases the harness could not play").isEmpty();
    }

    private static void delete(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
