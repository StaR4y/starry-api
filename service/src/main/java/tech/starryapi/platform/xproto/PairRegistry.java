package tech.starryapi.platform.xproto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The cross-protocol pairs this code can serve, and how far each has come (design 7.3). A pair is a kind of source, the
 * endpoint a client's request came in through and the vendor whose model was asked: a Claude Code request ({@code
 * MESSAGES}, {@code anthropic}) answered by an OpenAI-format relay is one pair, a Codex request for a Claude model
 * ({@code RESPONSES}, {@code anthropic}) another. Native sources need no pair.
 *
 * <p>This is the first of two locks. The second is the pair gate an administrator sets per vendor and entry, which can
 * only be as open as the status here allows (the validator of the tier configuration compares them): a pair that is
 * not listed is {@link Status#NOT_OFFERED}, so a node that does not know a pair, an older one during a rolling upgrade
 * for instance, never serves it. {@link Status#EXPERIMENTAL} may be opened to listed API keys only; {@link
 * Status#VERIFIED} may be switched on for everyone.
 *
 * <p>A pair is listed in {@code OFFERED} below when its converter and every fixture of design 7.14 are in the tree, as
 * EXPERIMENTAL. {@code PairRegistryCompletenessTest} fails when a listed pair lacks a fixture, and {@code
 * CrossProtocolContractTest} replays the fixtures of every listed pair. Moving a pair to VERIFIED is a task of its own,
 * done on evidence from the status page. The converter and the feature policy of a pair join the registered value
 * together with the tasks that write them.
 */
public final class PairRegistry {

    /** How far a pair has come. The names are the ones the console and the gate validation use. */
    public enum Status {
        NOT_OFFERED, EXPERIMENTAL, VERIFIED
    }

    /**
     * The kind of source on the far side of a pair. The name is the directory the fixtures of its pairs are under, and
     * the protocol is what the source-tier member rows and the pair gates call it.
     */
    public enum SourceKind {
        /** An OpenAI-format relay reached over Chat Completions: the only kind with a client in this release. */
        OPENAI_CHAT_RELAY("OPENAI_CHAT");

        private final String sourceProtocol;

        SourceKind(String sourceProtocol) {
            this.sourceProtocol = sourceProtocol;
        }

        public String sourceProtocol() {
            return sourceProtocol;
        }

        public static Optional<SourceKind> ofProtocol(String sourceProtocol) {
            for (SourceKind kind : values()) {
                if (kind.sourceProtocol.equals(sourceProtocol)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** The endpoint a request came in through, spelled as the pair gates spell it. */
    public enum Entry {
        CHAT, MESSAGES, RESPONSES, GEMINI;

        public static Optional<Entry> ofName(String name) {
            for (Entry entry : values()) {
                if (entry.name().equals(name)) {
                    return Optional.of(entry);
                }
            }
            return Optional.empty();
        }
    }

    /** The vendors whose models a relay can be asked for: the ones whose own accounts are not OpenAI-format keys. */
    public static final List<String> VENDORS = List.of("anthropic", "openai", "google");

    public record PairKey(SourceKind kind, Entry entry, String vendor) {
        public PairKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(entry, "entry");
            if (!isVendor(vendor)) {
                throw new IllegalArgumentException("A relay is not asked for the models of vendor " + vendor);
            }
        }
    }

    /** One listed pair. Listing a pair as NOT_OFFERED is refused: leaving it out says that. */
    public record Registration(SourceKind kind, Entry entry, String vendor, Status status) {
        public Registration {
            Objects.requireNonNull(status, "status");
            if (status == Status.NOT_OFFERED) {
                throw new IllegalArgumentException("A pair that is not offered is left out of the registry, not listed");
            }
            // The record's fields are assigned after this block; the key checks the parts.
            new PairKey(kind, entry, vendor);
        }

        public PairKey pair() {
            return new PairKey(kind, entry, vendor);
        }
    }

    /**
     * What this release offers: the Chat Completions, Messages and Responses entries, for the models of the anthropic and
     * the openai vendors, the Gemini entry for those of the anthropic and the google vendors, and the Chat Completions entry
     * for the google ones, all on trial. They are the entries of the clients the fifth, the sixth and the seventh phase of
     * the plan are for (OpenCode, Claude Code, Codex, and the clients of the Gemini SDKs), and they stay EXPERIMENTAL, open to
     * the API keys an administrator lists, until the status page shows, for each, the evidence that
     * {@link tech.starryapi.sourcetier.XpPairStandard} asks for. The Gemini entry is the non-streaming {@code :generateContent}: the conversation loop of the Gemini
     * CLI calls {@code :streamGenerateContent}, which the gateway does not route until the streaming of the ninth phase, so
     * the CLI reaches the pairs with its utility requests only (a summary, the fallback of a web fetch: its web fetch request
     * declares {@code urlContext}, and a search request {@code googleSearch}, which no relay can do, so those are served by the
     * native sources) and the evidence for the Gemini pairs has to come from an SDK client until then. A Gemini client asking for a gpt model, Messages and Responses requests for a Gemini
     * model have no client to speak of and are left out.
     */
    private static final List<Registration> OFFERED = List.of(
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "google", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "openai", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "anthropic", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.GEMINI, "anthropic", Status.EXPERIMENTAL),
        new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.GEMINI, "google", Status.EXPERIMENTAL)
    );

    private static final PairRegistry DEFAULTS = new PairRegistry(OFFERED);

    private final Map<PairKey, Status> statuses;

    /** For tests that need pairs this release does not offer; the code asks {@link #defaults()}. */
    public PairRegistry(Collection<Registration> offered) {
        Map<PairKey, Status> statuses = new HashMap<>();
        for (Registration registration : offered) {
            if (statuses.put(registration.pair(), registration.status()) != null) {
                throw new IllegalArgumentException("The pair " + registration.pair() + " is listed twice");
            }
        }
        this.statuses = Map.copyOf(statuses);
    }

    public static PairRegistry defaults() {
        return DEFAULTS;
    }

    public Status status(PairKey pair) {
        return statuses.getOrDefault(pair, Status.NOT_OFFERED);
    }

    /**
     * The status of a pair named the way a pair gate names it. A name this code does not know, a protocol or an entry
     * a newer release added for instance, is not offered.
     */
    public Status status(String vendor, String sourceProtocol, String entryEndpoint) {
        Optional<SourceKind> kind = SourceKind.ofProtocol(sourceProtocol);
        Optional<Entry> entry = Entry.ofName(entryEndpoint);
        if (kind.isEmpty() || entry.isEmpty() || !isVendor(vendor)) {
            return Status.NOT_OFFERED;
        }
        return status(new PairKey(kind.get(), entry.get(), vendor));
    }

    private static boolean isVendor(String vendor) {
        return vendor != null && VENDORS.contains(vendor);
    }

    public boolean offered(PairKey pair) {
        return status(pair) != Status.NOT_OFFERED;
    }

    /** The listed pairs, in the order of {@link #allPairs()}. */
    public List<PairKey> offeredPairs() {
        return allPairs().stream().filter(this::offered).toList();
    }

    /** Every pair that can be named, listed or not: kind, then entry, then vendor. */
    public static List<PairKey> allPairs() {
        List<PairKey> pairs = new ArrayList<>();
        for (SourceKind kind : SourceKind.values()) {
            for (Entry entry : Entry.values()) {
                for (String vendor : VENDORS) {
                    pairs.add(new PairKey(kind, entry, vendor));
                }
            }
        }
        return List.copyOf(pairs);
    }
}
