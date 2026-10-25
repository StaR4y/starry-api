package tech.starryapi.sourcetier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.ProxyAccountRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.egress.EgressException;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.openai.OpenAiRelayModelsClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.ChatWireSanitizer;
import tech.starryapi.platform.xproto.RelayNotSentException;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Reported;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.XpCacheEvidence.Call;
import tech.starryapi.sourcetier.XpProbes.Probe;
import tech.starryapi.sourcetier.XpProbes.Setup;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks that an OpenAI-format relay really does what a mapping says it does, before the mapping may serve a request
 * (design 7.4; "unverified mappings are not in a plan" is the planner's rule, and {@link
 * SourceTierValidator#isVerified} is the question it asks). It sends the probes of {@link XpProbes} to the relay with
 * the very client and parsers requests are served with, one mapping after the other, and stores what they vouch for.
 *
 * <ul>
 *   <li><b>What is stored.</b> A mapping that passes every probe gets {@code verified_at} and a {@code verified_hash},
 *       the fingerprint of everything the probes depended on: the member's settings, the catalog model, the relay's name
 *       for it, the account's provider and base URL ({@link SourceTierValidator#mappingFingerprint}). The mapping
 *       counts as verified exactly as long as the fingerprint of what is stored is that hash, so a change to any of
 *       those, an account that now points elsewhere included, ends the verification without anyone clearing it. A
 *       run that fails any probe takes the verification away: the relay no longer does what it did, or never did.
 *       The report of the run, in the gateway's words, goes to {@code verify_summary}.
 *   <li><b>Changes while it runs.</b> The probes take seconds and the administrator may edit the relay in the meantime.
 *       Each mapping is stored only if its fingerprint is still the one it was probed under, checked inside the lock
 *       of every configuration write ({@link SourceTierAdminService#recordVerification}); a mapping that moved on is
 *       reported as {@code stale} and nothing is stored for it.
 *   <li><b>The cache probe</b>, on request, asks the same prompt of about 1,200 tokens twice and lets {@link
 *       XpCacheEvidence} say what the relay's prompt count contains. It is advice and never decides {@code verified};
 *       its conclusion is stored as evidence, which is what lets a member be saved with "not contained".
 *   <li><b>Cost and abuse.</b> Every probe is paid for by the operator, so one account is verified once in 30 seconds
 *       (and never twice at the same time), at most eight mappings at a time, and a whole run is held to four minutes:
 *       a relay that answers every probe just before its own limit would otherwise keep one request, and the claim of
 *       the account, for most of an hour. A short question (at most 128 tokens of answer) is given 30 seconds, any other
 *       60, and what is left of the run when a probe comes is its limit if that is less. The probes that find no time
 *       are not asked and are reported as skipped, which is not passed. The throttle is the node's; on several
 *       nodes an account can be verified once per node in that time, which is a bound on the cost and not a guarantee.
 *       The list of a relay's models is a call of the same kind and is limited to one in five seconds per account.
 *   <li><b>What is shown.</b> Nothing the relay wrote: a failure is the status and the type the error mapper made of it,
 *       or one of the fixed texts of the accumulator and the judgements.
 * </ul>
 */
@Service
public class XpMappingVerifier {

    private static final Logger log = LoggerFactory.getLogger(XpMappingVerifier.class);

    static final String RATE_LIMITED = "SOURCE_VERIFY_RATE_LIMITED";
    static final String MEMBER_NOT_FOUND = "SOURCE_TIER_MEMBER_NOT_FOUND";
    static final String MODELS_FAILED = "SOURCE_UPSTREAM_MODELS_FAILED";

    static final Duration VERIFY_INTERVAL = Duration.ofSeconds(30);
    static final Duration MODELS_INTERVAL = Duration.ofSeconds(5);
    /** One probe is a short answer; a relay that needs longer than this is not one traffic can use. */
    static final Duration PROBE_LIMIT = Duration.ofSeconds(60);
    /** The limit of a probe that asks for at most {@link #SHORT_ANSWER_TOKENS} tokens: a word, a function call. */
    static final Duration SHORT_PROBE_LIMIT = Duration.ofSeconds(30);
    static final int SHORT_ANSWER_TOKENS = 128;
    /** The whole of a run, all mappings and the cache probes; a probe that finds less than {@link #MIN_PROBE_TIME} of it left is not asked. */
    static final Duration RUN_LIMIT = Duration.ofMinutes(4);
    static final Duration MIN_PROBE_TIME = Duration.ofSeconds(1);
    /** Between the two calls of the cache probe, so that a relay that stores its cache after the answer has done so. */
    static final Duration CACHE_PAUSE = Duration.ofSeconds(2);
    static final int MAX_MAPPINGS = 8;
    private static final String NOT_ASKED = "Not asked: the basic probe failed";
    private static final String OUT_OF_TIME = "Not asked: the verification ran out of time";

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private final SourceTierAdminService admin;
    private final ProxyAccountRepository accounts;
    private final OpenAiChatRelayClient client;
    private final OpenAiRelayModelsClient modelsClient;
    private final ModelCatalogProvider catalog;
    private final Clock clock;
    private final Duration cachePause;
    private final Duration runLimit;
    private final Duration shortProbeLimit;
    private final Throttle verifications;
    private final Throttle listings;

    @Autowired
    public XpMappingVerifier(
        SourceTierAdminService admin,
        ProxyAccountRepository accounts,
        OpenAiChatRelayClient client,
        OpenAiRelayModelsClient modelsClient,
        ModelCatalogProvider catalog
    ) {
        this(admin, accounts, client, modelsClient, catalog, Clock.systemUTC(), CACHE_PAUSE);
    }

    XpMappingVerifier(
        SourceTierAdminService admin,
        ProxyAccountRepository accounts,
        OpenAiChatRelayClient client,
        OpenAiRelayModelsClient modelsClient,
        ModelCatalogProvider catalog,
        Clock clock,
        Duration cachePause
    ) {
        this(admin, accounts, client, modelsClient, catalog, clock, cachePause, RUN_LIMIT, SHORT_PROBE_LIMIT);
    }

    XpMappingVerifier(
        SourceTierAdminService admin,
        ProxyAccountRepository accounts,
        OpenAiChatRelayClient client,
        OpenAiRelayModelsClient modelsClient,
        ModelCatalogProvider catalog,
        Clock clock,
        Duration cachePause,
        Duration runLimit,
        Duration shortProbeLimit
    ) {
        this.admin = admin;
        this.accounts = accounts;
        this.client = client;
        this.modelsClient = modelsClient;
        this.catalog = catalog;
        this.clock = clock;
        this.cachePause = cachePause;
        this.runLimit = runLimit;
        this.shortProbeLimit = shortProbeLimit;
        this.verifications = new Throttle(VERIFY_INTERVAL, clock);
        this.listings = new Throttle(MODELS_INTERVAL, clock);
    }

    // ---- verification ----

    /** Probes the mappings of a relay member and stores what they vouch for; see the class description. */
    public Mono<SourceTierDtos.VerifyResult> verify(String vendorId, String accountId, SourceTierDtos.VerifyMember request) {
        return Mono.defer(() -> {
            String vendor = SourceTierValidator.vendorId(vendorId);
            return Mono.zip(admin.storedSnapshot(), accounts.findByAccountId(accountId).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(loaded -> {
                    SourceTierSnapshot snapshot = loaded.getT1();
                    Member member = snapshot.member(vendor, accountId).filter(Member::crossProtocol)
                        .orElseThrow(() -> notFound(vendor, accountId));
                    ProxyAccountEntity account = loaded.getT2().orElseThrow(() -> notFound(vendor, accountId));
                    requireRelay(account);
                    List<MemberModel> models = select(snapshot, member, request);
                    boolean cacheProbe = request != null && Boolean.TRUE.equals(request.cacheProbe());
                    Throttle.Claim claim = verifications.claim(accountId);
                    // Deferred, so that a failure to even start still gives the claim back.
                    return Mono.defer(() -> run(vendor, member, account, models, cacheProbe)).doFinally(signal -> claim.release());
                });
        });
    }

    private Mono<SourceTierDtos.VerifyResult> run(
        String vendor,
        Member member,
        ProxyAccountEntity account,
        List<MemberModel> models,
        boolean cacheProbe
    ) {
        // Counted from here, in the clock that does not stand still while a relay thinks.
        long deadline = System.nanoTime() + runLimit.toNanos();
        return Flux.fromIterable(models)
            .concatMap(model -> probe(account, member, model, cacheProbe, deadline))
            .collectList()
            .flatMap(runs -> admin.recordVerification(vendor, account.getAccountId(), runs.stream().map(Run::probed).toList())
                .map(written -> result(vendor, account.getAccountId(), runs, written)));
    }

    /** The mappings to probe: the ones asked for, else the ones that are switched on. */
    private static List<MemberModel> select(SourceTierSnapshot snapshot, Member member, SourceTierDtos.VerifyMember request) {
        List<MemberModel> stored = snapshot.models(member.vendor(), member.accountId()).stream()
            .sorted(Comparator.comparing(MemberModel::modelId)).toList();
        List<MemberModel> chosen = new ArrayList<>();
        if (request != null && request.modelIds() != null && !request.modelIds().isEmpty()) {
            for (String id : request.modelIds()) {
                MemberModel model = stored.stream().filter(candidate -> candidate.modelId().equalsIgnoreCase(id == null ? "" : id.strip()))
                    .findFirst().orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, SourceTierValidator.MEMBER_INVALID,
                        "Account " + member.accountId() + " has no mapping for the model " + id));
                if (!chosen.contains(model)) {
                    chosen.add(model);
                }
            }
        } else {
            stored.stream().filter(MemberModel::enabled).forEach(chosen::add);
        }
        if (chosen.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SourceTierValidator.MEMBER_INVALID,
                "Account " + member.accountId() + " has no mapping that is switched on to verify");
        }
        if (chosen.size() > MAX_MAPPINGS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SourceTierValidator.MEMBER_INVALID, "Verify at most " + MAX_MAPPINGS
                + " mappings at a time; name the models to verify in modelIds");
        }
        return chosen;
    }

    private static void requireRelay(ProxyAccountEntity account) {
        if (!AccountPlatforms.isOpenAiCompatibleApiKey(account)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SourceTierValidator.MEMBER_INVALID,
                "Account " + account.getAccountId() + " is not an OpenAI-compatible API key, which is all a relay can be");
        }
        if (account.getAccessToken() == null || account.getAccessToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SourceTierValidator.MEMBER_INVALID,
                "Account " + account.getAccountId() + " has no API key to ask the relay with");
        }
    }

    private static ApiException notFound(String vendor, String accountId) {
        return new ApiException(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND,
            "Account " + accountId + " is not registered as a relay for " + vendor + " models");
    }

    /** What the probes of one mapping found. */
    record Run(
        MemberModel model,
        String fingerprint,
        List<SourceTierDtos.ProbeResult> probes,
        boolean passed,
        Instant at,
        XpCacheEvidence.Finding finding
    ) {

        SourceTierAdminService.Probed probed() {
            ObjectNode summary = FACTORY.objectNode();
            summary.put("passed", passed);
            summary.put("at", at.toString());
            ArrayNode list = summary.putArray("probes");
            for (SourceTierDtos.ProbeResult probe : probes) {
                ObjectNode entry = list.addObject();
                entry.put("name", probe.name());
                entry.put("status", probe.status());
                entry.put("message", probe.message());
                entry.put("latencyMs", probe.latencyMs());
                if (probe.promptTokens() != null) {
                    entry.put("promptTokens", probe.promptTokens());
                }
                if (probe.completionTokens() != null) {
                    entry.put("completionTokens", probe.completionTokens());
                }
            }
            return new SourceTierAdminService.Probed(model.modelId(), fingerprint, passed, at, summary, finding);
        }
    }

    private Mono<Run> probe(ProxyAccountEntity account, Member member, MemberModel model, boolean cacheProbe, long deadline) {
        return Mono.defer(() -> {
            String fingerprint = SourceTierValidator.mappingFingerprint(member, model, account);
            Setup setup = new Setup(member, model, ChatWireSanitizer.usesMaxCompletionTokens(
                catalog.targetFor(model.modelId().toLowerCase(Locale.ROOT)).orElse(model.modelId())));
            List<SourceTierDtos.ProbeResult> results = new ArrayList<>();
            AtomicBoolean answers = new AtomicBoolean(true);
            Mono<Void> required = Flux.fromIterable(XpProbes.required(setup)).concatMap(probe -> {
                if (!answers.get()) {
                    // The relay did not even answer a short question; the rest would only cost.
                    results.add(new SourceTierDtos.ProbeResult(probe.name(), "SKIPPED", NOT_ASKED, 0, null, null));
                    return Mono.empty();
                }
                return ask(account, probe, deadline).doOnNext(result -> {
                    results.add(result);
                    if (XpProbes.BASIC.equals(probe.name()) && !"PASSED".equals(result.status())) {
                        answers.set(false);
                    }
                }).then();
            }).then();
            Mono<Optional<XpCacheEvidence.Finding>> cache = Mono.defer(() -> {
                if (!cacheProbe) {
                    return Mono.just(Optional.<XpCacheEvidence.Finding>empty());
                }
                if (!answers.get()) {
                    results.add(new SourceTierDtos.ProbeResult(XpProbes.CACHE, "SKIPPED", NOT_ASKED, 0, null, null));
                    return Mono.just(Optional.<XpCacheEvidence.Finding>empty());
                }
                return askCache(account, setup, results, deadline).map(Optional::of).defaultIfEmpty(Optional.empty());
            });
            return required.then(cache).map(finding -> {
                // The cache probe is advice: whether the mapping serves depends on the probes that traffic needs.
                boolean passed = results.stream().filter(result -> !XpProbes.CACHE.equals(result.name()))
                    .allMatch(result -> "PASSED".equals(result.status()));
                return new Run(model, fingerprint, List.copyOf(results), passed, clock.instant(), finding.orElse(null));
            });
        });
    }

    /**
     * How long a question may take: its own limit, or what is left of the run if that is less; null when too little is
     * left for it to be asked at all.
     */
    private Duration timeFor(ObjectNode body, long deadline) {
        long tokens = Math.max(body.path("max_tokens").asLong(0), body.path("max_completion_tokens").asLong(0));
        Duration own = tokens > 0 && tokens <= SHORT_ANSWER_TOKENS ? shortProbeLimit : PROBE_LIMIT;
        Duration left = Duration.ofNanos(deadline - System.nanoTime());
        if (left.compareTo(MIN_PROBE_TIME) < 0) {
            return null;
        }
        return left.compareTo(own) < 0 ? left : own;
    }

    /** One probe: the request, the answer, and the judgement of it. */
    private Mono<SourceTierDtos.ProbeResult> ask(ProxyAccountEntity account, Probe probe, long deadline) {
        long started = System.nanoTime();
        Duration limit = timeFor(probe.body(), deadline);
        if (limit == null) {
            return Mono.just(new SourceTierDtos.ProbeResult(probe.name(), "SKIPPED", OUT_OF_TIME, 0, null, null));
        }
        return Mono.defer(() -> client.complete(account, probe.body(), limit))
            .map(completion -> {
                String problem = probe.judge().apply(completion);
                return result(probe.name(), problem == null ? "PASSED" : "FAILED", problem == null ? "ok" : problem, started, completion);
            })
            .onErrorResume(error -> Mono.just(result(probe.name(), "FAILED", describe(error), started, null)));
    }

    /** The cache probe: the same prompt twice. A finding is made only when both answers came. */
    private Mono<XpCacheEvidence.Finding> askCache(
        ProxyAccountEntity account,
        Setup setup,
        List<SourceTierDtos.ProbeResult> results,
        long deadline
    ) {
        ObjectNode body = XpProbes.cacheRequest(setup, UUID.randomUUID().toString());
        long started = System.nanoTime();
        List<Completion> answers = new ArrayList<>();
        if (timeFor(body, deadline) == null) {
            results.add(new SourceTierDtos.ProbeResult(XpProbes.CACHE, "SKIPPED", OUT_OF_TIME, 0, null, null));
            return Mono.empty();
        }
        // Each call is held to what is left of the run when it is made, the second one too, after the pause.
        return Mono.defer(() -> cacheCall(account, body, deadline)).doOnNext(answers::add)
            .then(Mono.delay(cachePause).then(Mono.defer(() -> cacheCall(account, body, deadline))).doOnNext(answers::add))
            .map(second -> {
                List<Call> calls = answers.stream().map(answer -> Call.of(XpProbes.reported(answer))).toList();
                XpCacheEvidence.Finding finding = XpCacheEvidence.analyze(calls);
                results.add(result(XpProbes.CACHE, "PASSED", finding.message() == null ? "ok" : finding.message(), started, second));
                return finding;
            })
            .onErrorResume(error -> {
                results.add(result(XpProbes.CACHE, "FAILED", describe(error), started, null));
                return Mono.empty();
            });
    }

    private Mono<Completion> cacheCall(ProxyAccountEntity account, ObjectNode body, long deadline) {
        Duration time = timeFor(body, deadline);
        return time == null
            ? Mono.error(new UpstreamException(502, "The verification ran out of time before the cache probe was done", true))
            : client.complete(account, body, time);
    }

    private static SourceTierDtos.ProbeResult result(String name, String status, String message, long startedNanos, Completion answer) {
        Reported usage = answer == null ? null : XpProbes.reported(answer);
        return new SourceTierDtos.ProbeResult(name, status, message, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
            usage == null || usage.prompt().isEmpty() ? null : usage.prompt().getAsLong(),
            usage == null || usage.output().isEmpty() ? null : usage.output().getAsLong());
    }

    /** What a failure of a probe is called: the gateway's own words only. */
    static String describe(Throwable error) {
        Throwable root = error;
        for (int depth = 0; root != null && depth < 16; depth++, root = root.getCause()) {
            if (root instanceof UpstreamException upstream) {
                return upstream.getMessage();
            }
            if (root instanceof RelayNotSentException) {
                return "The source could not be reached";
            }
            if (root instanceof EgressException egress && egress.failure() != null) {
                return egress.failure().message();
            }
            if (root.getCause() == root) {
                break;
            }
        }
        // Class names only: a transport message can hold an address or a proxy credential.
        return "The probe could not be completed (" + (error == null ? "unknown" : error.getClass().getSimpleName()) + ")";
    }

    private SourceTierDtos.VerifyResult result(String vendor, String accountId, List<Run> runs, Map<String, SourceTierAdminService.Recorded> written) {
        List<SourceTierDtos.ModelVerification> models = new ArrayList<>();
        boolean verified = true;
        for (Run run : runs) {
            SourceTierAdminService.Recorded recorded = written.get(XpCacheEvidence.key(accountId, run.model().modelId()));
            boolean modelVerified = recorded != null && recorded.verified();
            verified &= modelVerified;
            List<SourceTierDtos.ProbeResult> probes = new ArrayList<>(run.probes());
            if (recorded != null && run.passed() && recorded.unprovenCache() != null) {
                // Every probe passed, but the member bills the relay on a setting that nothing has shown for it.
                probes.add(new SourceTierDtos.ProbeResult(XpProbes.CACHE_EVIDENCE, "FAILED",
                    XpCacheEvidence.unprovenMessage(recorded.unprovenCache()), 0, null, null));
            }
            models.add(new SourceTierDtos.ModelVerification(run.model().modelId(), run.model().upstreamModel(), modelVerified, recorded == null,
                List.copyOf(probes), advice(run)));
        }
        log.info("Verified the mappings of a relay: vendor={} account={} mappings={} verified={}",
            vendor, accountId, runs.size(), verified);
        return new SourceTierDtos.VerifyResult(vendor, accountId, clock.instant(), verified, models);
    }

    private static SourceTierDtos.CacheAdvice advice(Run run) {
        XpCacheEvidence.Finding finding = run.finding();
        if (finding == null) {
            return null;
        }
        return new SourceTierDtos.CacheAdvice(finding.readInPrompt(), finding.writeInPrompt(), finding.readEvidence(),
            finding.writeEvidence(), finding.message(),
            finding.calls().stream().map(call -> new SourceTierDtos.CacheCall(call.prompt(), call.cacheRead(), call.cacheWrite())).toList());
    }

    // ---- the relay's models ----

    /** The models the relay of an account lists, for filling in a mapping. The account need not be a member yet. */
    public Mono<SourceTierDtos.UpstreamModels> upstreamModels(String vendorId, String accountId) {
        return Mono.defer(() -> {
            String vendor = SourceTierValidator.vendorId(vendorId);
            if (!SourceTierRuntimeInfo.CROSS_PROTOCOL_VENDORS.contains(vendor)) {
                throw SourceTierValidator.notApplicable(vendor);
            }
            return accounts.findByAccountId(accountId)
                .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND, "Account " + accountId + " does not exist")))
                .flatMap(account -> {
                    requireRelay(account);
                    Throttle.Claim claim = listings.claim(accountId);
                    return Mono.defer(() -> modelsClient.list(account)).doFinally(signal -> claim.release());
                })
                .map(listed -> new SourceTierDtos.UpstreamModels(
                    listed.models().stream().map(model -> new SourceTierDtos.UpstreamModel(model.id(), model.contextLength())).toList(),
                    listed.truncated()))
                .onErrorMap(error -> !(error instanceof ApiException), error -> new ApiException(HttpStatus.BAD_GATEWAY, MODELS_FAILED,
                    "The relay's list of models could not be read: " + describe(error)));
        });
    }

    // ---- limits ----

    /**
     * One call per key in an interval, counted from its start, and never two at once. {@link #claim} either returns the
     * claim, to be released when the call ends, or says how long to wait.
     */
    static final class Throttle {

        private record Entry(Instant startedAt, boolean running) {
        }

        private final Duration interval;
        private final Clock clock;
        private final Map<String, Entry> entries = new HashMap<>();

        Throttle(Duration interval, Clock clock) {
            this.interval = interval;
            this.clock = clock;
        }

        final class Claim {
            private final String key;
            private final Instant startedAt;

            private Claim(String key, Instant startedAt) {
                this.key = key;
                this.startedAt = startedAt;
            }

            void release() {
                synchronized (Throttle.this) {
                    entries.computeIfPresent(key, (ignored, entry) -> entry.startedAt().equals(startedAt) ? new Entry(startedAt, false) : entry);
                }
            }
        }

        synchronized Claim claim(String key) {
            Instant now = clock.instant();
            entries.values().removeIf(entry -> !entry.running() && !entry.startedAt().plus(interval).isAfter(now));
            Entry last = entries.get(key);
            if (last != null && (last.running() || last.startedAt().plus(interval).isAfter(now))) {
                Duration remaining = Duration.between(now, last.startedAt().plus(interval));
                long wait = remaining.isNegative() ? 1 : Math.max(1, (remaining.toMillis() + 999) / 1000);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, "Account " + key + " was probed a moment ago"
                    + (last.running() ? " and is still being probed" : "") + "; try again in " + wait + " seconds",
                    Map.of("retryAfterSeconds", wait));
            }
            entries.put(key, new Entry(now, true));
            return new Claim(key, now);
        }
    }
}
