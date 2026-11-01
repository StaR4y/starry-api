package tech.starryapi.platform.xproto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tools.jackson.databind.ObjectMapper;

/**
 * The fixture tree of the cross-protocol pairs and what is checked about it.
 *
 * <pre>
 * xproto/&lt;SourceKind&gt;/&lt;Entry&gt;/&lt;vendor&gt;/&lt;case&gt;/
 *     case.json       {"origin": "..."}: where the case comes from, a capture of a real client or why it is derived
 *     input/...       whatever the pair's harness reads: the client request, the relay's scripted answers
 *     expected/...    what the harness must produce, compared byte for byte
 * </pre>
 *
 * A case is named by a {@link RequiredCase#id()}, or by {@code extra-} and a name for a case of the pair's own. The
 * tree is bytes: it is not run through an editor's line-ending or trailing-newline habits (the repository marks it
 * {@code -text}), and a file that differs by one of them is a different file. Ids, keys and tokens in the files are
 * placeholders; {@link #placeholderProblems} looks for the ones that are not.
 */
final class XprotoFixtures {

    static final String PROVENANCE = "case.json";
    static final String INPUT = "input";
    static final String EXPECTED = "expected";
    static final String EXTRA_PREFIX = "extra-";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXCERPT_BYTES = 48;

    private XprotoFixtures() {
    }

    /** One fixture case on disk. */
    record Case(PairKey pair, String id, Path directory) {

        Map<String, byte[]> inputs() {
            return files(directory.resolve(INPUT));
        }

        Map<String, byte[]> expected() {
            return files(directory.resolve(EXPECTED));
        }

        byte[] input(String name) {
            return read(directory.resolve(INPUT).resolve(name));
        }
    }

    /**
     * The fixture tree of the test classpath. When nothing has been captured yet there is none, and the path returned
     * does not exist; everything here reads a missing tree as an empty one.
     */
    static Path classpathRoot() {
        URL url = XprotoFixtures.class.getClassLoader().getResource("xproto");
        if (url == null) {
            return Path.of("no-xproto-fixtures-captured-yet");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException(error);
        }
    }

    static Path directory(Path root, PairKey pair) {
        return root.resolve(pair.kind().name()).resolve(pair.entry().name()).resolve(pair.vendor());
    }

    /** The cases of a pair, by name. */
    static List<Case> cases(Path root, PairKey pair) {
        return subdirectories(directory(root, pair)).stream()
            .map(directory -> new Case(pair, directory.getFileName().toString(), directory))
            .toList();
    }

    // ---- what the tree has to look like ----

    /** Directories and files that are not part of the layout, for the whole tree and whether or not the pair is offered. */
    static List<String> structureProblems(Path root) {
        List<String> problems = new ArrayList<>();
        for (Path kindDirectory : children(root)) {
            String kindName = name(kindDirectory);
            Optional<SourceKind> kind = Arrays.stream(SourceKind.values()).filter(value -> value.name().equals(kindName)).findFirst();
            if (kind.isEmpty() || !Files.isDirectory(kindDirectory)) {
                problems.add(relative(root, kindDirectory) + " is not a source kind directory");
                continue;
            }
            for (Path entryDirectory : children(kindDirectory)) {
                Optional<Entry> entry = Entry.ofName(name(entryDirectory));
                if (entry.isEmpty() || !Files.isDirectory(entryDirectory)) {
                    problems.add(relative(root, entryDirectory) + " is not an entry directory");
                    continue;
                }
                for (Path vendorDirectory : children(entryDirectory)) {
                    if (!PairRegistry.VENDORS.contains(name(vendorDirectory)) || !Files.isDirectory(vendorDirectory)) {
                        problems.add(relative(root, vendorDirectory) + " is not a vendor directory");
                        continue;
                    }
                    caseProblems(root, vendorDirectory, problems);
                }
            }
        }
        return problems;
    }

    private static void caseProblems(Path root, Path vendorDirectory, List<String> problems) {
        for (Path caseDirectory : children(vendorDirectory)) {
            String id = name(caseDirectory);
            if (!Files.isDirectory(caseDirectory) || (RequiredCase.ofId(id) == null && !id.startsWith(EXTRA_PREFIX))) {
                problems.add(relative(root, caseDirectory) + " is neither a required case of design 7.14 nor named "
                    + EXTRA_PREFIX + "...");
                continue;
            }
            for (Path part : children(caseDirectory)) {
                boolean known = (Files.isRegularFile(part) && name(part).equals(PROVENANCE))
                    || (Files.isDirectory(part) && (name(part).equals(INPUT) || name(part).equals(EXPECTED)));
                if (!known) {
                    problems.add(relative(root, part) + " is not one of " + PROVENANCE + ", " + INPUT + "/, " + EXPECTED + "/");
                }
            }
        }
    }

    /** What an offered pair lacks: a case of design 7.14, its provenance, its input or its expected output. */
    static List<String> completenessProblems(PairRegistry registry, Path root) {
        List<String> problems = new ArrayList<>();
        for (PairKey pair : registry.offeredPairs()) {
            Path pairDirectory = directory(root, pair);
            if (!Files.isDirectory(pairDirectory)) {
                problems.add(relative(root, pairDirectory) + " is missing (" + registry.status(pair) + " " + describe(pair) + ")");
                continue;
            }
            for (RequiredCase required : RequiredCase.values()) {
                if (!required.appliesTo(pair.entry())) {
                    continue;
                }
                Path caseDirectory = pairDirectory.resolve(required.id());
                String where = relative(root, caseDirectory);
                if (!Files.isDirectory(caseDirectory)) {
                    problems.add(where + " is missing");
                    continue;
                }
                if (provenance(caseDirectory.resolve(PROVENANCE)).isBlank()) {
                    problems.add(where + "/" + PROVENANCE + " has no origin");
                }
                if (files(caseDirectory.resolve(INPUT)).isEmpty()) {
                    problems.add(where + "/" + INPUT + " has no file");
                }
                if (files(caseDirectory.resolve(EXPECTED)).isEmpty()) {
                    problems.add(where + "/" + EXPECTED + " has no file");
                }
            }
        }
        return problems;
    }

    private static String provenance(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return JSON.readTree(read(file)).path("origin").asString("").strip();
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    private static final Pattern KEY_OR_TOKEN = Pattern.compile(
        "(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{16,}|(?i:\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,})");
    /** Not a word boundary: a real id is often glued to a name by an underscore, as in {@code account_<id>}. */
    private static final Pattern UUID_TEXT = Pattern.compile(
        "(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPEATED_DIGIT_UUID = Pattern.compile("(?i)([0-9a-f])\\1{7}-(\\1{4}-){3}\\1{12}");

    /**
     * The values in the tree that look like a live key, token or id: an {@code sk-} key, a bearer token, or a UUID
     * other than one of a single repeated digit (the nil UUID is the placeholder). A capture has its ids replaced
     * before it is committed.
     */
    static List<String> placeholderProblems(Path root) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files(root).entrySet()) {
            String text = new String(file.getValue(), StandardCharsets.UTF_8);
            Matcher secrets = KEY_OR_TOKEN.matcher(text);
            while (secrets.find()) {
                problems.add(file.getKey() + " holds " + abbreviated(secrets.group()) + ", which is not a placeholder");
            }
            Matcher uuids = UUID_TEXT.matcher(text);
            while (uuids.find()) {
                if (!REPEATED_DIGIT_UUID.matcher(uuids.group()).matches()) {
                    problems.add(file.getKey() + " holds the id " + uuids.group() + ", which is not a placeholder");
                }
            }
        }
        return problems;
    }

    // ---- bytes ----

    /**
     * Where two files differ, or empty when they are the same bytes. A trailing newline, a carriage return or a
     * different space is a difference.
     */
    static Optional<String> difference(String name, byte[] expected, byte[] actual) {
        int at = Arrays.mismatch(expected, actual);
        if (at < 0) {
            return Optional.empty();
        }
        int line = 1;
        int column = 1;
        for (int i = 0; i < at; i++) {
            if (expected[i] == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return Optional.of(name + " differs at byte " + at + " (line " + line + ", column " + column + "): expected "
            + excerpt(expected, at) + " but was " + excerpt(actual, at) + " (" + expected.length + " bytes expected, "
            + actual.length + " produced)");
    }

    private static String excerpt(byte[] bytes, int at) {
        if (at >= bytes.length) {
            return "the end of the file";
        }
        String text = new String(bytes, at, Math.min(EXCERPT_BYTES, bytes.length - at), StandardCharsets.UTF_8);
        StringBuilder visible = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\n' -> visible.append("\\n");
                case '\r' -> visible.append("\\r");
                case '\t' -> visible.append("\\t");
                case '"' -> visible.append("\\\"");
                default -> visible.append(c < ' ' ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return visible.append('"').toString();
    }

    // ---- writing a tree, for the tests of this framework ----

    /** Writes one case: {@code input} and {@code expected} map file names to their text. */
    static void writeCase(
        Path root,
        PairKey pair,
        String id,
        String origin,
        Map<String, String> input,
        Map<String, String> expected
    ) {
        Path caseDirectory = directory(root, pair).resolve(id);
        if (origin != null) {
            write(caseDirectory.resolve(PROVENANCE), JSON.createObjectNode().put("origin", origin).toString());
        }
        input.forEach((name, text) -> write(caseDirectory.resolve(INPUT).resolve(name), text));
        expected.forEach((name, text) -> write(caseDirectory.resolve(EXPECTED).resolve(name), text));
    }

    /**
     * Writes every case an offered pair of {@code registry} needs. The expected file of a case is its input, so the
     * pair's harness is the one that returns the input as it is.
     */
    static void writeCompleteTree(Path root, PairRegistry registry) {
        for (PairKey pair : registry.offeredPairs()) {
            for (RequiredCase required : RequiredCase.values()) {
                if (required.appliesTo(pair.entry())) {
                    String text = "{\"case\":\"" + required.id() + "\"}\n";
                    writeCase(root, pair, required.id(), "derived for the framework test",
                        Map.of("request.json", text), Map.of("client-response.json", text));
                }
            }
        }
    }

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    // ---- files ----

    /** The regular files below {@code directory}, by their path inside it with '/', in name order. */
    static Map<String, byte[]> files(Path directory) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).sorted().forEach(file ->
                files.put(directory.relativize(file).toString().replace('\\', '/'), read(file)));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return files;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static List<Path> subdirectories(Path directory) {
        return children(directory).stream().filter(Files::isDirectory).toList();
    }

    private static List<Path> children(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.sorted().toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String name(Path path) {
        return path.getFileName().toString();
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String describe(PairKey pair) {
        return pair.kind() + " for " + pair.entry() + " requests of " + pair.vendor();
    }

    private static String abbreviated(String value) {
        return value.length() <= 24 ? value : value.substring(0, 24) + "...";
    }
}
