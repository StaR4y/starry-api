package tech.starryapi.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AssistantDocsIndex {

    private static final int MAX_CHUNKS = 80;
    private static final int MAX_CHUNK_CHARS = 1600;
    private static final List<String> FILES = List.of(
        "README.md",
        "docs/API.md",
        "docs/ARCHITECTURE.md",
        "docs/CONFIGURATION.md",
        "docs/OPERATIONS.md",
        "docs/TROUBLESHOOTING.md",
        "docs/INSTALL_SERVER.md",
        "docs/PLUGINS.md",
        "docs/SUPPORT_TICKETS.md",
        "webui/README.md"
    );

    private final List<Chunk> chunks;

    public AssistantDocsIndex() {
        this(Path.of(System.getProperty("user.dir", ".")));
    }

    AssistantDocsIndex(Path root) {
        this.chunks = load(root);
    }

    public List<Chunk> retrieve(String query, int limit) {
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        record Scored(Chunk chunk, int score) {
        }
        return chunks.stream()
            .map(chunk -> new Scored(chunk, score(chunk, terms)))
            .filter(item -> item.score() > 0)
            .sorted(Comparator.comparingInt(Scored::score).reversed())
            .limit(Math.max(1, Math.min(limit, 6)))
            .map(Scored::chunk)
            .toList();
    }

    private static List<Chunk> load(Path root) {
        List<Chunk> loaded = new ArrayList<>();
        for (String relative : FILES) {
            Path file = root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                loaded.addAll(split(relative, text));
            } catch (IOException ignored) {
                // Skip unreadable files; chat still works from the system prompt.
            }
            if (loaded.size() >= MAX_CHUNKS) {
                break;
            }
        }
        return List.copyOf(loaded.subList(0, Math.min(loaded.size(), MAX_CHUNKS)));
    }

    static List<Chunk> split(String source, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.split("(?m)^#{1,3} ");
        List<Chunk> chunks = new ArrayList<>();
        for (String part : parts) {
            String body = part.trim();
            if (body.length() < 40) {
                continue;
            }
            String title = body.lines().findFirst().orElse(source);
            if (body.length() > MAX_CHUNK_CHARS) {
                body = body.substring(0, MAX_CHUNK_CHARS);
            }
            chunks.add(new Chunk(source, title.replace('#', ' ').trim(), body));
        }
        return chunks;
    }

    static List<String> terms(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String part : normalized.split("[\\s\\p{Punct}]+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        for (int index = 0; index < normalized.length() - 1; index++) {
            char left = normalized.charAt(index);
            char right = normalized.charAt(index + 1);
            if (isCjk(left) && isCjk(right)) {
                terms.add("" + left + right);
            }
        }
        return terms;
    }

    private static int score(Chunk chunk, List<String> terms) {
        String haystack = (chunk.title() + "\n" + chunk.body()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (chunk.title().toLowerCase(Locale.ROOT).contains(term)) {
                score += 3;
            }
            int from = 0;
            while (from < haystack.length()) {
                int found = haystack.indexOf(term, from);
                if (found < 0) {
                    break;
                }
                score += 1;
                from = found + term.length();
                if (score > 80) {
                    return score;
                }
            }
        }
        return score;
    }

    private static boolean isCjk(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    public record Chunk(String source, String title, String body) {
    }
}
