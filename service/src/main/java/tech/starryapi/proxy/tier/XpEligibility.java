package tech.starryapi.proxy.tier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.platform.xproto.ChatCanonicalizer;
import tech.starryapi.platform.xproto.ChatWireSanitizer;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tech.starryapi.platform.xproto.FeaturePolicy;
import tech.starryapi.platform.xproto.FeaturePolicy.Verdict;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tech.starryapi.platform.xproto.StrictChatLint;
import tech.starryapi.proxy.tier.TierPlan.Relay;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierValidator;
import tools.jackson.databind.JsonNode;

/**
 * Decides which of the relays registered in a vendor's tiers may serve one request, and builds what each is sent (design 3,
 * step 5b). A relay is eligible when, for this request:
 *
 * <ol>
 *   <li>the registry offers its pair (source, entry, vendor) and the pair gate lets this key through: OFF lets none, KEYS
 *       the listed keys, ON everyone, but a pair that is only EXPERIMENTAL is never open to more than the listed keys;
 *   <li>the account is still an OpenAI-compatible API key;
 *   <li>the model asked for has an enabled mapping on the relay ({@link XpModelResolver}), and it is verified, with the
 *       fingerprint of what it is now;
 *   <li>the request has no feature the relay cannot carry and the native accounts of the route can ({@link FeaturePolicy},
 *       the route's baseline being that of its native cells);
 *   <li>the request, made into the relay's Chat body, passes the sanitizer, the canonicalizer and the strict lint;
 *   <li>it fits the window of the mapping, and is smaller than any prompt this node has seen the relay refuse for its
 *       length ({@link XpLearnedLimits}); the window is measured on the prompt alone, except that a relay whose mapping states
 *       a maximum output is sent the output limit that the maximum allows when the client wrote none, if the window holds that
 *       beside the prompt and this node has not seen the relay refuse a body as large with it (D15, {@link #evaluate}), and
 *       the 4,096 it is otherwise sent when it does not;
 *   <li>and the relay has not broken off an answer for this session a moment ago ({@link TierSessionBindings#relayExcluded}).
 * </ol>
 *
 * The conditions are met in this order and a relay is skipped for the first it fails (design 7.9, 7.6, 7.8; the window is
 * measured on the body of the fifth, and the exclusion is last so that a relay is reported as out for a moment only for a
 * request it would otherwise have been given: one that is over its window, or that it cannot be sent, is not held back by
 * what the session met on the relay, whatever it was). Every relay left out is recorded with its reason. A relay whose
 * account is not in the account cache (disabled, suspended, deleted) is not looked at: it is not a member of the tier
 * until it is back, as a native account that is not in the cache is not.
 *
 * <p>Members with the same settings are sent the same body, so it is built once per distinct {@link Settings}, and the
 * features are judged once per distinct settings too. Nothing here selects an account, reserves or charges; the walk of
 * the tiers picks among the eligible relays as it does among native accounts, and the dispatch each carries is what the
 * facade then sends.
 *
 * <p>What this node has learned of a relay (the window its refusals showed, the answers it broke off for the session) is
 * a reason to leave it out like the others, and a structural one: a tier whose relays are all left out for it is a tier
 * the request has nothing in, and not one it found unavailable, so that the tier below serving the request is no
 * downgrade of its session (design 4.7). The same holds for a tier of which only some relays are left out for what the
 * request is or for a moment (its features, its conversion, its size, an interruption), whatever the others do: they may be
 * busy or out of quota, but the relay that sat this request out would serve a smaller, plainer or later one of the same
 * session ({@link Found#requestBoundTiers}).
 */
final class XpEligibility {

    private static final Logger log = LoggerFactory.getLogger(XpEligibility.class);

    /** A relay that may serve the request, with the account as the cache holds it now. */
    record Eligible(Relay relay, ProxyAccountEntity account) {
    }

    /**
     * What was found for one request.
     *
     * @param byTier the eligible relays, by the id of their tier
     * @param skips the relays that were left out, with the reasons, in the order of the tiers
     * @param open whether the pair of some relay is offered and its gate lets the request through, even when no relay is
     *     in service for it: a tier of relays that are switched off is then a tier that cannot serve for now, and not a
     *     tier the request has nothing in
     * @param session what the request's session is called for the relays that broke off its answers, which the facade writes
     *     them under; null when no relay of the vendor is open to the request's key (a pair that is not offered or whose gate
     *     is shut), and when nothing tells the session from others
     * @param entry the entry of the request as the registry names it, which the skips are counted under; null when no relay
     *     was looked at
     */
    record Found(Map<String, List<Eligible>> byTier, List<XpSkip> skips, boolean open, String session, String entry) {

        /** A request no relay takes part in. */
        static final Found NONE = new Found(Map.of(), List.of(), false, null, null);

        List<Relay> relays(String tierId) {
            return byTier.getOrDefault(tierId, List.of()).stream().map(Eligible::relay).toList();
        }

        /**
         * The feature a request that no tier can serve has to do without, if a relay of the tiers was left out for one
         * (design 3, step 6): the request is then refused with a name for it, rather than with "no account".
         */
        String hardFeature(Collection<Tier> tiers) {
            Set<String> ids = tiers.stream().map(Tier::tierId).collect(Collectors.toSet());
            return skips.stream()
                .filter(skip -> skip.reason() == XpSkip.Reason.FEATURE && ids.contains(skip.tierId()))
                .map(XpSkip::firstFeature)
                .findFirst()
                .orElse(null);
        }

        /**
         * Whether a request that no tier can serve has a relay in these tiers that was left out because the request
         * continues a response chain that this node could not expand (design 4.8): the request is then refused for the
         * chain, which the client must not take out of the request as it would a feature, but continue from the history it
         * holds.
         */
        boolean chainRefused(Collection<Tier> tiers) {
            Set<String> ids = tiers.stream().map(Tier::tierId).collect(Collectors.toSet());
            String chain = Feature.PREVIOUS_RESPONSE_ID.label();
            return skips.stream().anyMatch(skip -> ids.contains(skip.tierId()) && skip.leftOutFor(chain));
        }

        /**
         * Whether a request that no tier can serve has a relay in these tiers that is only left out for a moment (it broke
         * off an answer for the session): the tier can serve it again shortly, so it is neither a tier that has nothing for
         * this request nor one to refuse the request for.
         */
        boolean temporarilyOut(Collection<Tier> tiers) {
            Set<String> ids = tiers.stream().map(Tier::tierId).collect(Collectors.toSet());
            return skips.stream().anyMatch(skip -> skip.reason().temporary() && ids.contains(skip.tierId()));
        }

        /**
         * Whether a request that no tier can serve has a relay in these tiers that was left out because the request is
         * larger than its window, or than a prompt it refused (design 3, step 6 and 7.9: the estimated input over the limit is
         * a feature no relay carries). It is told what a relay that refuses the prompt at dispatch would have told it.
         */
        boolean contextRefused(Collection<Tier> tiers) {
            Set<String> ids = tiers.stream().map(Tier::tierId).collect(Collectors.toSet());
            return skips.stream().anyMatch(skip -> skip.reason() == XpSkip.Reason.CONTEXT && ids.contains(skip.tierId()));
        }

        /**
         * The ids of the tiers in which a relay was left out for something about this request or this moment
         * ({@link XpSkip.Reason#ofTheRequest}): a session that the tier below serves is not one the tier pushed out when the
         * request, and not the tier, is why a member did not take part (design 0, 4.7).
         */
        Set<String> requestBoundTiers() {
            return skips.stream().filter(skip -> skip.reason().ofTheRequest()).map(XpSkip::tierId).collect(Collectors.toSet());
        }
    }

    /** The body of one set of settings, and whether it can be sent. */
    private record Built(Wire wire, List<String> problems, long estimatedInput) {

        /** The output the body asks the relay for; 0 for a body that names none and for one that could not be made. */
        long outputLimit() {
            return wire == null ? 0 : TierDispatch.outputLimitOf(wire.body());
        }
    }

    /** What a body is built under: the settings, and the output limit it names instead of the planned one (0 for the planned). */
    private record BodyKey(Settings settings, long output) {
    }

    /**
     * The output limits a relay may be sent for a request whose limit the gateway chose (D15, {@link
     * TierEntryFacts#gatewayOutput}), as its mapping decides them.
     *
     * @param narrow the limit the prompt is judged with, the most that a relay was sent before D11 (4,096, or the maximum
     *     output of the mapping if that is less); 0 for a request whose limit is the client's or the entry's own, which
     *     has one body, the planned one
     * @param wide the limit the relay is sent when the window holds it beside the prompt and this node has not seen the
     *     relay refuse a body as large with it (the maximum output of the mapping, as far as the gateway's own default
     *     for the request goes); the same as {@code narrow} when there is no such limit
     */
    private record Outputs(long narrow, long wide) {

        static Outputs of(TierPlanRequest request, TierEntryFacts facts, MemberModel mapping) {
            if (!facts.gatewayOutput()) {
                return new Outputs(0, 0);
            }
            long wide = RequestBounds.relayOutputTokens(RequestBounds.outputTokens(request.upstreamRequest()), mapping.maxOutputTokens());
            return new Outputs(Math.min(wide, RequestBounds.FALLBACK_OUTPUT_TOKENS), wide);
        }

        boolean widerThanBefore() {
            return wide > narrow;
        }
    }

    /**
     * The Chat body the relays of one request are given a body from, made when the first relay gets as far as being
     * built for and shared by the rest: for the Chat entry it is the planned body, and for the others it is the conversion
     * of it ({@link TierEntryFacts#chatBody}): the Responses one made of the planned body, the Messages and Gemini ones the
     * body their converters made of the original at the entry, under the output limit of the planned one. A conversion that
     * fails fails every relay's body the same way.
     */
    private static final class ChatSource {

        private final TierPlanRequest request;
        private final TierEntryFacts facts;
        private final Map<Long, JsonNode> sized = new HashMap<>();
        private JsonNode body;
        private RuntimeException failure;

        ChatSource(TierPlanRequest request, TierEntryFacts facts) {
            this.request = request;
            this.facts = facts;
        }

        /** The Chat body with the output limit {@code output} where that is more than 0, and as planned otherwise. */
        JsonNode get(long output) {
            JsonNode planned = planned();
            return output > 0 ? sized.computeIfAbsent(output, limit -> RequestBounds.withOutputLimit(planned, limit)) : planned;
        }

        private JsonNode planned() {
            if (failure != null) {
                throw failure;
            }
            if (body == null) {
                try {
                    body = facts.chatBody(request.upstreamRequest());
                } catch (RuntimeException broken) {
                    failure = broken;
                    throw broken;
                }
            }
            return body;
        }
    }

    private final PairRegistry pairs;
    private final AccountPoolService accountPool;
    private final XpModelResolver models;
    private final TierSessionBindings sessions;
    private final XpLearnedLimits learnedLimits;

    XpEligibility(
        PairRegistry pairs,
        AccountPoolService accountPool,
        XpModelResolver models,
        TierSessionBindings sessions,
        XpLearnedLimits learnedLimits
    ) {
        this.pairs = Objects.requireNonNull(pairs, "pairs");
        this.accountPool = Objects.requireNonNull(accountPool, "accountPool");
        this.models = Objects.requireNonNull(models, "models");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.learnedLimits = Objects.requireNonNull(learnedLimits, "learnedLimits");
    }

    /**
     * @param modelKey the model as tiers see it, which a session without a name of its own is told by
     * @param units the cells of the route's native accounts, which give the baseline of the policy
     * @param enabled the vendor's enabled tiers; the relays of a tier that is switched off are no one's
     */
    Found evaluate(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        String vendor,
        String modelKey,
        List<PoolCell> units,
        List<Tier> enabled
    ) {
        TierEntryFacts facts = request.entryFacts();
        if (facts == null || !snapshot.hasCrossProtocolMembers() || !PairRegistry.VENDORS.contains(vendor)) {
            return Found.NONE;
        }
        Detected detected = facts.detected();
        if (request.entry() != null && !request.entry().name().equals(detected.entry().name())) {
            return Found.NONE;
        }
        // The request is read for the entry because some vendor has a gate open for it; this vendor's relays are looked at
        // only if one of its own gates is, so that a gate that is off or missing is no reason to count a relay as left out.
        if (!snapshot.hasOpenRelayFor(vendor, detected.entry().name())) {
            return Found.NONE;
        }
        Map<String, Tier> tiers = enabled.stream().collect(Collectors.toMap(Tier::tierId, Function.identity()));
        List<Member> members = snapshot.members(vendor).stream()
            .filter(member -> member.crossProtocol() && tiers.containsKey(member.tierId()))
            .sorted(Comparator.comparingInt((Member member) -> tiers.get(member.tierId()).level()).thenComparing(Member::accountId))
            .toList();
        if (members.isEmpty()) {
            return Found.NONE;
        }
        Set<String> ids = members.stream().map(Member::accountId).collect(Collectors.toSet());
        Map<String, ProxyAccountEntity> cached = accountPool.cachedAccounts(account -> ids.contains(account.getAccountId())).stream()
            .collect(Collectors.toMap(ProxyAccountEntity::getAccountId, Function.identity(), (first, second) -> first));
        String model = DefaultSourceTierPlanner.normalize(request.upstreamModel());
        FeaturePolicy.Baseline baseline = FeaturePolicy.Baseline.of(units);
        Map<Settings, Verdict> verdicts = new HashMap<>();
        Map<BodyKey, Built> built = new HashMap<>();
        ChatSource chat = new ChatSource(request, facts);
        Map<String, List<Eligible>> eligible = new LinkedHashMap<>();
        List<XpSkip> skips = new ArrayList<>();
        // What the relays that broke off this session's answers are kept from it by; the same name the facade writes them under.
        String session = TierSessionKeys.resolve(request.keyId(), modelKey, request.sessionName(), request.upstreamRequest())
            .orElse(null);
        boolean open = false;
        for (Member member : members) {
            Optional<SourceKind> kind = SourceKind.ofProtocol(member.protocol());
            if (kind.isEmpty()) {
                // A protocol that a newer release added: nothing of this release's to say about it.
                continue;
            }
            PairKey pair = new PairKey(kind.get(), detected.entry(), vendor);
            Status status = pairs.status(pair);
            if (status == Status.NOT_OFFERED) {
                continue;
            }
            boolean letsThrough = gateLetsThrough(snapshot, vendor, pair, status, request.keyId());
            open |= letsThrough;
            ProxyAccountEntity account = cached.get(member.accountId());
            if (account == null) {
                // Not in service: not a member of the tier until it is back, so there is nothing to say about it either.
                continue;
            }
            String tierId = member.tierId();
            if (!letsThrough) {
                skips.add(new XpSkip(tierId, member.accountId(), XpSkip.Reason.GATE, ""));
                continue;
            }
            if (!PoolCell.XP_OPENAI_CHAT.predicate().test(account)) {
                skips.add(new XpSkip(tierId, member.accountId(), XpSkip.Reason.NOT_A_RELAY, ""));
                continue;
            }
            MemberModel mapping = models.find(snapshot.models(vendor, member.accountId()), vendor, model)
                .filter(MemberModel::enabled)
                .orElse(null);
            if (mapping == null) {
                skips.add(new XpSkip(tierId, member.accountId(), XpSkip.Reason.NO_MAPPING, model));
                continue;
            }
            if (!SourceTierValidator.isVerified(member, mapping, account)) {
                skips.add(new XpSkip(tierId, member.accountId(), XpSkip.Reason.UNVERIFIED, mapping.modelId()));
                continue;
            }
            Settings settings = settings(member, mapping, vendor, request.fastMode());
            Verdict verdict = verdicts.computeIfAbsent(settings, ignored -> FeaturePolicy.judge(detected, baseline, settings));
            if (!verdict.allowed()) {
                skips.add(new XpSkip(tierId, member.accountId(), XpSkip.Reason.FEATURE,
                    verdict.hard().stream().map(Feature::label).distinct().collect(Collectors.joining(","))));
                continue;
            }
            // The prompt is judged on the body the relay was sent before D11 (the 4,096 that was never counted besides it, as
            // far as its mapping allows no more); a relay is left out for what that body is left out for, as it was.
            Outputs outputs = Outputs.of(request, facts, mapping);
            Built body = built.computeIfAbsent(new BodyKey(settings, outputs.narrow()),
                ignored -> build(chat, facts, settings, outputs.narrow()));
            XpSkip unfit = unfit(member, mapping, body, 0);
            if (unfit != null) {
                skips.add(unfit);
                continue;
            }
            long beyondFallback = 0;
            if (outputs.widerThanBefore()) {
                // D15: the maximum its mapping states, if the window holds it beside the prompt and this node has not seen the
                // relay refuse a body as large with it; if not, the relay is sent the request it was sent before D11, which
                // its prompt fits.
                long extra = outputs.wide() - outputs.narrow();
                Built wide = built.computeIfAbsent(new BodyKey(settings, outputs.wide()),
                    ignored -> build(chat, facts, settings, outputs.wide()));
                if (unfit(member, mapping, wide, extra) == null && wide.estimatedInput() + outputs.wide() <= mapping.maxContextTokens()) {
                    body = wide;
                    beyondFallback = extra;
                }
            }
            // Last, so that the relay is out for a moment only for a request it would be given: the tier it sits in is then
            // one that can serve this request again shortly, and not one the request has nothing in.
            if (sessions.relayExcluded(vendor, session, member.accountId())) {
                skips.add(new XpSkip(tierId, member.accountId(), XpSkip.Reason.INTERRUPTED, ""));
                continue;
            }
            Set<String> degraded = new LinkedHashSet<>(verdict.soft().stream().map(Feature::label).toList());
            degraded.addAll(body.wire().degraded());
            TierDispatch dispatch = TierDispatch.openAiChat(pair, mapping.modelId(), mapping.upstreamModel(), member,
                body.wire().body(), List.copyOf(degraded));
            eligible.computeIfAbsent(tierId, ignored -> new ArrayList<>())
                .add(new Eligible(new Relay(dispatch, mapping, body.estimatedInput(), beyondFallback), account));
        }
        // Whatever the relays did this turn, or were left out for: the session is the one a relay is kept from, and what the
        // next turn of a response chain is the session of. A relay that is not open to the key is nothing of the session's.
        return new Found(eligible, List.copyOf(skips), open, open ? session : null, detected.entry().name());
    }

    /**
     * Whether a relay that is out of service (disabled or suspended, so in no account cache and not looked at by {@link
     * #evaluate}) would take part in the request if it were back: every condition of the class description but the last.
     * The pair is offered and its gate lets the key through, the account is still a relay, the model has an enabled mapping
     * that is verified for the account as it is, the request carries nothing the relay cannot, and the request, made into
     * its body, passes the lint and fits the window and what this node learned of it. What the session met on the relay is
     * not asked: the relay is out of service for longer than that. A request the relay would not be given whatever its state
     * is no reason to hold a pin to its tier, as it is none when the relay is in service.
     */
    boolean couldServeWhenBack(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        String vendor,
        List<PoolCell> units,
        Member member,
        ProxyAccountEntity account
    ) {
        TierEntryFacts facts = request.entryFacts();
        Optional<SourceKind> kind = SourceKind.ofProtocol(member.protocol());
        if (facts == null || kind.isEmpty() || account == null || !PoolCell.XP_OPENAI_CHAT.predicate().test(account)) {
            return false;
        }
        Detected detected = facts.detected();
        PairKey pair = new PairKey(kind.get(), detected.entry(), vendor);
        Status status = pairs.status(pair);
        if (status == Status.NOT_OFFERED || !gateLetsThrough(snapshot, vendor, pair, status, request.keyId())) {
            return false;
        }
        MemberModel mapping = models.find(snapshot.models(vendor, member.accountId()), vendor,
                DefaultSourceTierPlanner.normalize(request.upstreamModel()))
            .filter(MemberModel::enabled)
            .orElse(null);
        if (mapping == null || !SourceTierValidator.isVerified(member, mapping, account)) {
            return false;
        }
        Settings settings = settings(member, mapping, vendor, request.fastMode());
        return FeaturePolicy.judge(detected, FeaturePolicy.Baseline.of(units), settings).allowed()
            && unfit(member, mapping, build(new ChatSource(request, facts), facts, settings,
                Outputs.of(request, facts, mapping).narrow()), 0) == null;
    }

    /**
     * Why the body made for the relay cannot be sent to it, if it cannot: the lint failed, the request is over the window of
     * the mapping, or a relay that refused a prompt of this size for its length is not asked the same again (design 7.8),
     * nor a body that asks for as much output as one it refused with a prompt as large (the client's own included, {@link
     * XpLearnedLimits#learnWide}). The one place these are decided, for a relay in service and for one that is out of it.
     *
     * @param beyondFallback how many tokens of output the body asks for beyond the fallback, which is added to its prompt
     *     where it is compared with what the relay refused, since a refusal is learned in the terms of the body with the
     *     fallback output ({@link Relay#taughtByRefusal}); 0 for the body that is judged beside the fallback, and for a body
     *     with a limit the client wrote, whatever output it asks for: it is compared on its prompt alone
     */
    private XpSkip unfit(Member member, MemberModel mapping, Built body, long beyondFallback) {
        if (!body.problems().isEmpty()) {
            return new XpSkip(member.tierId(), member.accountId(), XpSkip.Reason.CONVERSION, body.problems().getFirst());
        }
        if (body.estimatedInput() > mapping.maxContextTokens()) {
            return new XpSkip(member.tierId(), member.accountId(), XpSkip.Reason.CONTEXT,
                "about " + body.estimatedInput() + " tokens, the window is " + mapping.maxContextTokens());
        }
        OptionalLong refused = learnedLimits.refusedFrom(mapping);
        if (refused.isPresent() && body.estimatedInput() + beyondFallback >= refused.getAsLong()) {
            return new XpSkip(member.tierId(), member.accountId(), XpSkip.Reason.CONTEXT,
                "about " + body.estimatedInput() + " tokens, a prompt of " + refused.getAsLong() + " was refused for its length");
        }
        // A body that asks for as much output as one that was refused, with a prompt as large, is refused again: the value above
        // is in the terms of the body with the 4,096 output, in which a limit the client wrote (which has no extra to add) is
        // not told from the body that was refused.
        if (learnedLimits.refusedAsMuch(mapping, body.estimatedInput(), body.outputLimit())) {
            return new XpSkip(member.tierId(), member.accountId(), XpSkip.Reason.CONTEXT,
                "about " + body.estimatedInput() + " tokens and an output of " + body.outputLimit()
                    + ", a body with as much of both was refused for its length");
        }
        return null;
    }

    /**
     * The registry is the code's lock and the gate the administrator's (design 7.3): a gate that is ON opens a pair that
     * is VERIFIED to every key, and one that is only EXPERIMENTAL to the keys it lists, as KEYS does. No gate row is OFF.
     */
    private static boolean gateLetsThrough(SourceTierSnapshot snapshot, String vendor, PairKey pair, Status status, String keyId) {
        PairGate gate = snapshot.gates(vendor).stream()
            .filter(candidate -> candidate.sourceProtocol().equals(pair.kind().sourceProtocol())
                && candidate.entryEndpoint().equals(pair.entry().name()))
            .findFirst()
            .orElse(null);
        if (gate == null) {
            return false;
        }
        boolean listed = gate.lists(keyId);
        if ("ON".equals(gate.state())) {
            return status == Status.VERIFIED || listed;
        }
        return "KEYS".equals(gate.state()) && listed;
    }

    /**
     * The settings the body is built from and the features are judged by: what the member is set to, what the request is
     * billed as, and what the model's vendor needs (design 7.6). The name of the token limit follows the catalog model of
     * the mapping (the GPT-5 family and the o-series only take {@code max_completion_tokens}), not the relay's own name for
     * it and not the Claude model the catalog sends a gpt id to on the Kiro route; the verification of the mapping asks the
     * same way, so what it vouched for is the body that is sent.
     */
    private Settings settings(Member member, MemberModel mapping, String vendor, boolean billedFastMode) {
        return new Settings(
            member.vision(),
            member.jsonSchema(),
            member.priorityTier(),
            member.cacheControlPassthrough(),
            member.upstreamStream(),
            "EFFORT".equals(member.reasoningMode()),
            billedFastMode,
            ChatWireSanitizer.usesMaxCompletionTokens(mapping.modelId()),
            ChatWireSanitizer.dropsReasoningOnContinuation(vendor),
            ChatWireSanitizer.needsToolsForToolHistory(vendor),
            ChatWireSanitizer.carriesThoughtSignatures(vendor),
            ChatWireSanitizer.claudeSampling(vendor)
        );
    }

    /**
     * The body for one set of settings, made from the request as it is reserved (the Responses entry's body is converted
     * from that one) and from what the original body says about reasoning and tool search. The output limit is the one of the
     * request, unless {@code output} says what a relay is given for a limit the gateway chose (D15, {@link Outputs}). A body
     * that cannot be made, or does not pass the lint, is a conversion this member cannot serve; a defect in the conversion
     * costs the request its relays, not the request. (The names of the functions of a Gemini request are held to what the
     * upstreams of the other vendors take by the policy, as a feature, and not here: they are the client's, not a defect.)
     */
    private static Built build(ChatSource chat, TierEntryFacts facts, Settings settings, long output) {
        try {
            Wire wire = ChatCanonicalizer.canonicalize(
                ChatWireSanitizer.sanitize(chat.get(output), facts.entryDigest(), settings, null), settings);
            List<String> problems = StrictChatLint.problems(wire.body());
            return new Built(wire, problems, problems.isEmpty() ? RelayUsageNormalizer.estimateInputTokens(wire.body()) : 0);
        } catch (RuntimeException failure) {
            log.warn("The conversion of a request for a relay failed: {}", failure.toString());
            return new Built(null, List.of("the conversion failed: " + failure.getClass().getSimpleName()), 0);
        }
    }
}
