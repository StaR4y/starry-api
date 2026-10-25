package tech.starryapi.sourcetier;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.JacksonConfig;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Reported;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the cache probe of a relay's verification saw, and what may be concluded from it (design 5.8, 7.4): whether the
 * relay's {@code prompt_tokens} already contain the tokens it read from its cache and the tokens it wrote to it. The
 * switches of a member say it, billing depends on it, and a switch set wrongly to "contained" can only bill too little
 * while one set wrongly to "not contained" bills the cache twice, so "not contained" is stored only on this evidence.
 *
 * <p>The probe sends the same prompt of about 1,200 tokens twice, and each answer reports a prompt count {@code p}, a
 * cache read {@code r} and a cache write {@code w}. The prompt is one prompt, so its size {@code t} is the same in both
 * calls; what each switch says about {@code t} is {@code p} plus the count the prompt does not contain. Of the four
 * combinations of the two switches, one is {@link Verdict#CONSISTENT} when
 *
 * <ul>
 *   <li>no count the prompt is said to contain is larger than the prompt (a cache read of 1,024 tokens cannot be in a
 *       prompt of 76), and
 *   <li>the sizes {@code t} of the two calls agree, to a hundredth or four tokens;
 * </ul>
 *
 * and {@link Verdict#REJECTED} when it breaks the first or the sizes differ by more than three times that margin.
 * A switch is concluded only when the combinations that say one thing are consistent and every combination that says
 * the other is rejected: a relay that cached nothing proves nothing about either (all four are consistent), and
 * figures that fit no combination, or fit it only barely, prove nothing either. No conclusion is a null.
 *
 * <p>What is stored with the evidence is the relay and the model it was seen for ({@link #basis}); evidence for another
 * base URL, another wire name or another way of reading the answer is not evidence for this one.
 */
final class XpCacheEvidence {

    private static final ObjectMapper MAPPER = JacksonConfig.newMapper();

    private static final double RELATIVE_MARGIN = 0.01;
    private static final long ABSOLUTE_MARGIN = 4;
    /** The field of {@code verify_summary} the evidence is kept in. */
    static final String FIELD = "cache";

    private XpCacheEvidence() {
    }

    /** What one answer reported: {@code prompt} is null when the relay reported none. */
    record Call(Long prompt, long cacheRead, long cacheWrite) {

        /** The counts of an answer's usage; a prompt made of cache hits and misses is the prompt, as the normalizer reads it. */
        static Call of(Reported reported) {
            long read = reported.cacheRead().orElse(0);
            long write = reported.cacheWrite().orElse(0);
            OptionalLong prompt = reported.prompt();
            Long reportedPrompt = prompt.isPresent() && prompt.getAsLong() > 0 ? prompt.getAsLong()
                : read > 0 || reported.cacheMiss().orElse(0) > 0 ? Long.valueOf(read + reported.cacheMiss().orElse(0)) : null;
            return new Call(reportedPrompt, read, write);
        }
    }

    enum Verdict {
        CONSISTENT, AMBIGUOUS, REJECTED
    }

    /**
     * The conclusion of one probe. {@code readInPrompt} and {@code writeInPrompt} are null where nothing is concluded,
     * and {@code message} then says why; the evidence texts are the numbers a conclusion rests on.
     */
    record Finding(
        Boolean readInPrompt,
        Boolean writeInPrompt,
        String readEvidence,
        String writeEvidence,
        String message,
        List<Call> calls
    ) {

        Finding {
            calls = List.copyOf(calls);
        }
    }

    /** The conclusion drawn from the answers of the probe, in the order they were asked. */
    static Finding analyze(List<Call> calls) {
        if (calls.size() < 2 || calls.stream().anyMatch(call -> call.prompt() == null || call.prompt() <= 0)) {
            return new Finding(null, null, null, null, "The relay did not report the prompt tokens of both calls", calls);
        }
        if (calls.stream().allMatch(call -> call.cacheRead() == 0 && call.cacheWrite() == 0)) {
            return new Finding(null, null, null, null,
                "The relay reported no cached tokens: it does not cache a prompt of this size, or does not say so", calls);
        }
        Verdict[][] verdicts = new Verdict[2][2];
        for (int read = 0; read < 2; read++) {
            for (int write = 0; write < 2; write++) {
                verdicts[read][write] = verdict(calls, read == 1, write == 1);
            }
        }
        Boolean read = concluded(verdicts, true);
        Boolean write = concluded(verdicts, false);
        String message = read == null && write == null
            ? "The counts do not settle it: " + (everyVerdictIs(verdicts, Verdict.REJECTED)
                ? "they fit no reading of the prompt, so the relay counts its prompt differently from call to call"
                : "the cached counts are too small to tell whether the prompt contains them")
            : null;
        return new Finding(read, write,
            read == null ? null : evidence("cache read", read, calls, true),
            write == null ? null : evidence("cache write", write, calls, false),
            message, calls);
    }

    /** The reading where the prompt contains the cache read ({@code readIn}) and the cache write ({@code writeIn}). */
    private static Verdict verdict(List<Call> calls, boolean readIn, boolean writeIn) {
        long smallest = Long.MAX_VALUE;
        long largest = Long.MIN_VALUE;
        for (Call call : calls) {
            long contained = (readIn ? call.cacheRead() : 0) + (writeIn ? call.cacheWrite() : 0);
            if (contained > call.prompt()) {
                return Verdict.REJECTED;
            }
            long size = size(call, readIn, writeIn);
            smallest = Math.min(smallest, size);
            largest = Math.max(largest, size);
        }
        long margin = Math.max(ABSOLUTE_MARGIN, (long) Math.ceil(largest * RELATIVE_MARGIN));
        long spread = largest - smallest;
        return spread <= margin ? Verdict.CONSISTENT : spread <= 3 * margin ? Verdict.AMBIGUOUS : Verdict.REJECTED;
    }

    private static long size(Call call, boolean readIn, boolean writeIn) {
        return call.prompt() + (readIn ? 0 : call.cacheRead()) + (writeIn ? 0 : call.cacheWrite());
    }

    /** The value of one switch that the verdicts settle, or null. */
    private static Boolean concluded(Verdict[][] verdicts, boolean read) {
        for (boolean value : new boolean[] {true, false}) {
            boolean supported = false;
            boolean otherRejected = true;
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    boolean saysValue = ((read ? i : j) == 1) == value;
                    Verdict verdict = verdicts[i][j];
                    supported |= saysValue && verdict == Verdict.CONSISTENT;
                    otherRejected &= saysValue || verdict == Verdict.REJECTED;
                }
            }
            if (supported && otherRejected) {
                return value;
            }
        }
        return null;
    }

    private static boolean everyVerdictIs(Verdict[][] verdicts, Verdict wanted) {
        for (Verdict[] row : verdicts) {
            for (Verdict verdict : row) {
                if (verdict != wanted) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The numbers a conclusion rests on, in words. The reading of the other switch is the one the numbers allow. */
    private static String evidence(String name, boolean contained, List<Call> calls, boolean read) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            Call call = calls.get(i);
            text.append(i == 0 ? "" : "; ").append("call ").append(i + 1).append(": prompt ").append(call.prompt())
                .append(", ").append(name).append(' ').append(read ? call.cacheRead() : call.cacheWrite());
        }
        text.append(contained
            ? ". The same prompt is the same size in both calls without adding the " + name + ", so the prompt contains it."
            : ". The same prompt is the same size in both calls only with the " + name + " added, so the prompt does not contain it.");
        return text.toString();
    }

    // ---- what is stored ----

    /** The key a model of an account is looked up by (the table compares the model id without regard to case). */
    static String key(String accountId, String modelId) {
        return accountId + '\u0000' + modelId.toLowerCase(Locale.ROOT);
    }

    /** The evidence as it is kept: what was concluded, for which relay and model, and when. */
    record Stored(Boolean readInPrompt, Boolean writeInPrompt, String basis, Instant observedAt) {
    }

    /**
     * Identifies what the evidence is evidence about: the catalog model, the relay's name for it, the way the answer is
     * read (stream or JSON) and where the account sends it. The tier, the limits and the caliber switches themselves are
     * not part of it, so the evidence stays when an administrator acts on it.
     */
    static String basis(Member member, MemberModel model, ProxyAccountEntity account) {
        StringBuilder canonical = new StringBuilder("tier-cache-evidence-v1;");
        SourceTierValidator.field(canonical, model.modelId().toLowerCase(Locale.ROOT));
        SourceTierValidator.field(canonical, model.upstreamModel());
        SourceTierValidator.field(canonical, member.upstreamStream());
        SourceTierValidator.field(canonical, account == null ? null : account.getProvider());
        SourceTierValidator.field(canonical, account == null ? null : account.getBaseUrl());
        return Hashing.sha256(canonical.toString());
    }

    /** The {@code cache} member of a summary: the finding with its basis, ready to be merged into {@code verify_summary}. */
    static ObjectNode toJson(Finding finding, String basis, Instant observedAt) {
        ObjectNode node = MAPPER.createObjectNode();
        putFlag(node, "read", finding.readInPrompt());
        putFlag(node, "write", finding.writeInPrompt());
        node.put("basis", basis);
        node.put("at", observedAt.toString());
        var calls = node.putArray("calls");
        for (Call call : finding.calls()) {
            ObjectNode entry = calls.addObject();
            if (call.prompt() != null) {
                entry.put("prompt", call.prompt());
            }
            entry.put("read", call.cacheRead());
            entry.put("write", call.cacheWrite());
        }
        return node;
    }

    private static void putFlag(ObjectNode node, String name, Boolean value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    /** The evidence in a {@code verify_summary}, or empty when there is none or it cannot be read. */
    static Optional<Stored> fromSummary(String summary) {
        return read(cacheOf(summary));
    }

    /** The evidence in a {@code cache} node, or empty when it is none or cannot be read. */
    static Optional<Stored> read(JsonNode cache) {
        if (cache == null || !cache.path("basis").isString()) {
            return Optional.empty();
        }
        Instant at = null;
        try {
            at = cache.path("at").isString() ? Instant.parse(cache.path("at").asString("")) : null;
        } catch (RuntimeException unreadable) {
            // The time is for the console; evidence without it still stands.
        }
        return Optional.of(new Stored(flag(cache.path("read")), flag(cache.path("write")), cache.path("basis").asString(""), at));
    }

    /**
     * The evidence after a run: what the probe concluded, and, for a switch it concluded nothing about, what an earlier
     * probe concluded about the same relay and name. Evidence is replaced by a conclusion and not by the lack of one: a
     * probe whose second call did not find the prompt in the relay's cache (the relay answered from another node, the
     * cache had not been written yet) shows nothing, and does not take back what was shown. A run without a cache probe
     * keeps the evidence as it was. Null when there is none.
     *
     * @param earlier the {@code cache} node stored before, or null; evidence about another basis is not carried
     * @param fresh   what the cache probe of this run concluded, or null when it was not run or could not be answered
     */
    static ObjectNode merged(JsonNode earlier, Finding fresh, String basis, Instant at) {
        JsonNode kept = earlier != null && earlier.isObject() && basis.equals(earlier.path("basis").asString("")) ? earlier : null;
        if (fresh == null) {
            return kept == null ? null : (ObjectNode) kept.deepCopy();
        }
        ObjectNode node = toJson(fresh, basis, at);
        if (kept != null) {
            for (String name : new String[] {"read", "write"}) {
                if (node.path(name).isNull() && kept.path(name).isBoolean()) {
                    node.set(name, kept.get(name));
                }
            }
        }
        return node;
    }

    /** What the gateway says of a "not contained" setting that no evidence shows; {@code unproven} is {@link SourceTierValidator#unprovenCache}. */
    static String unprovenMessage(String unproven) {
        return "The prompt tokens are set not to contain the " + unproven + ", which no cache probe has shown for this relay and model;"
            + " verify with the cache probe, or set it to contained";
    }

    private static Boolean flag(JsonNode node) {
        return node.isBoolean() ? node.booleanValue() : null;
    }

    /**
     * The summary that is left of {@code summary} when only the cache evidence can stay: a summary holding just the
     * evidence of the {@code basis} given, or null when the evidence is of another basis or there is none. A mapping
     * whose verification is gone keeps this much.
     */
    static String carried(String summary, String basis) {
        JsonNode cache = cacheOf(summary);
        if (cache == null || !basis.equals(cache.path("basis").asString(""))) {
            return null;
        }
        ObjectNode kept = MAPPER.createObjectNode();
        kept.set(FIELD, cache);
        return kept.toString();
    }

    /** The evidence node of a summary as stored text (for merging into a new summary), or null. */
    static JsonNode cacheOf(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(summary);
            JsonNode cache = root == null ? null : root.get(FIELD);
            return cache != null && cache.isObject() ? cache : null;
        } catch (JacksonException unreadable) {
            return null;
        }
    }
}
