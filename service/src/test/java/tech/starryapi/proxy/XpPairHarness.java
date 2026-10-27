package tech.starryapi.proxy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.ResponsesChatConverter;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSelectorParser;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.XpLearnedLimits;
import tech.starryapi.proxy.tier.XpSkipCounts;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.sourcetier.SourceTierValidator;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plays one case of the fixtures of a cross-protocol pair (design 7.14) through the real code of the gateway and says
 * what happened, as files. It is what {@code CrossProtocolContractTest} runs for a pair of the registry: the facade with
 * its real planner, dispatcher, relay client and response factories, the relay a local socket that answers as the case's
 * script says, and everything outside the gateway's own code (the account pool, the billing store, Kiro, Anthropic and
 * OpenAI) a stand-in that records what it was asked.
 *
 * <p>The input of a case is the conversation as a client has it:
 *
 * <pre>
 * request.json            the request of the first turn, as the client sent it (a capture, or derived from one)
 * relay.json              what the relay answers the first turn: {status, contentType?, headers?, body?, abort?, mustNotReach?},
 *                         where body names a file beside it; a list is the answers to successive requests of the turn
 * member.json             the relay member's settings (vision, jsonSchema, priorityTier, cacheControlPassthrough, upstreamStream,
 *                         reasoningMode, maxDurationSeconds, usageCacheReadInPrompt, usageCacheWriteInPrompt) and the window
 *                         and image cap of its mappings, where they are not the defaults: no vision, schema, priority tier or
 *                         cache pass-through, a stream, no reasoning, 540 seconds, both caches counted in the prompt, a window
 *                         of 200,000 tokens (400,000 for a gpt model) and 8192 tokens per image
 * gate.json               the pair's gate, {state, keys}, where it is not KEYS and the key of the requests
 * native.json             {accounts: false} where the tier below the relay has no account in service
 * turns/N/follow-up.json  the messages the client adds for turn N (N >= 2) after the answer of turn N-1, with "${tool.I}"
 *                         for the id of the I-th tool call the client was given (the items it adds, for the Responses entry)
 * turns/N/request.json    or the whole request of turn N, where a conversation is not the one the answers made; with
 *                         "${response.id}" for the id of the response that answered turn N-1 (a Responses client that
 *                         continues a chain by {@code previous_response_id} and sends only what is new)
 * turns/N/relay.json      what the relay answers turn N
 * </pre>
 *
 * and the output, for the first turn and under {@code turns/N/} for the others:
 *
 * <pre>
 * relay-request.json      what the relay was sent (absent when it was not asked)
 * client-response.json    what the client was answered
 * billing.json            what was reserved, charged or released
 * route.json              the relays left out of the plan, and every attempt with what the request log says of it
 * </pre>
 *
 * Ids the gateway makes at random (the id of a message, a response or an item, the time it was created) are replaced by
 * placeholders; the ids of the tool calls it mints come from a generator with a fixed seed, so a case always sees the
 * same ones. A case that has a file nothing reads, or a turn the vendor's tiers do not plan, is refused rather than
 * played.
 *
 * <p>The Responses entry is played as Codex uses it: it sends its whole history every turn ({@code store: false}), each
 * turn adding the items of the answer as it replays them (the message and the calls without their ids, the reasoning
 * item without its id) and whatever the follow-up says. The requests carry the headers of Codex that the gateway reads
 * ({@code session-id}, {@code originator}), and a gpt model asked for by Codex is served by Codex accounts, so the native
 * tier of the openai vendor has one.
 *
 * <p><b>Where the inputs come from.</b> The requests are what Claude Code 2.1.168, OpenCode 1.18.16 and Codex 0.142.5
 * send, captured by running the installed clients against a local server that answered what a scenario said (a tool call,
 * then an answer), each in a sandbox that allowed loopback only, with a throwaway home and configuration directory and
 * dummy keys. Every id, key and path in a capture is a placeholder, the system prompts and the long tool descriptions are
 * shortened, and the tool list is cut to the tools a case uses (one case keeps all of Claude Code's); a case that is not
 * a capture says in its {@code case.json} what it was derived from. The relay's answers are authored from the documented Chat
 * Completions shapes, never recorded from a service. The expected files are what this harness produced when the case was
 * added, read through and accepted; {@code XprotoFixtureRecorder} rewrites them when the behavior is changed on purpose.
 */
public final class XpPairHarness {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_ID = "key-1";
    private static final String RELAY_KEY = "sk-relay-0000";
    private static final Pattern RANDOM_ID = Pattern.compile("(msg_|chatcmpl-)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** The ids of the items and the responses of the Responses API, which the factory makes from a UUID with the dashes left out. */
    private static final Pattern RANDOM_ITEM_ID = Pattern.compile("(resp_|msg_|fc_|ctc_|rs_)[0-9a-f]{32}");
    private static final Pattern CREATED = Pattern.compile("(\"created(?:_at)?\":\\s*)\\d+");
    private static final Pattern TOOL_PLACEHOLDER = Pattern.compile("\\$\\{tool\\.(\\d+)}");
    private static final String RESPONSE_PLACEHOLDER = "${response.id}";
    /** The request headers a relay may be sent, and no others: the client's own are never forwarded (design 7.12). */
    private static final Set<String> RELAY_HEADERS = Set.of(
        "accept", "accept-encoding", "authorization", "connection", "content-length", "content-type", "host", "user-agent");

    /** $3/M in, $15/M out, $0.30/M cache read, $3.75/M cache write: the prices of the owner's example (design 5.5). */
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));
    private static final PlatformUsage NATIVE_USAGE = new PlatformUsage(500, 20, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult NATIVE_ANSWER = new PlatformCompletionResult("from the native tier", NATIVE_USAGE);

    /** The one client of relays for all the plays: it keeps the connections and the threads of the HTTP stack, which a play need not start again. */
    private static final OpenAiChatRelayClient RELAY_CLIENT = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build());

    private XpPairHarness() {
    }

    /**
     * @param pair the pair under test
     * @param caseId the name of the case, for the messages
     * @param input the files of the case's {@code input} directory, by path
     * @return the files of the case's {@code expected} directory, by path
     */
    public static Map<String, byte[]> run(PairKey pair, String caseId, Map<String, byte[]> input) throws Exception {
        try (Play play = new Play(pair, caseId, input)) {
            return play.run();
        }
    }

    /** The files of a case's input, noting which of them were read: a file nothing reads is a mistake of the case. */
    private static final class Inputs {

        private final Map<String, byte[]> files;
        private final Set<String> read = new HashSet<>();

        Inputs(Map<String, byte[]> files) {
            this.files = files;
        }

        boolean has(String name) {
            return files.containsKey(name);
        }

        byte[] get(String name) {
            read.add(name);
            return files.get(name);
        }

        Set<String> names() {
            return files.keySet();
        }

        List<String> unread() {
            return files.keySet().stream().filter(name -> !read.contains(name)).sorted().toList();
        }
    }

    // ---- the relay's side ----

    /** One answer of the relay, as a case scripts it. */
    private record Script(int status, String contentType, Map<String, String> headers, byte[] body, boolean abort, int chunkBytes,
                          List<String> mustNotReach) {
    }

    /** One request the relay received. */
    private record Seen(String path, Map<String, String> headers, byte[] body) {
    }

    /** A relay on a loopback port that answers the requests it gets from the script, in order, over HTTP/1.1 written by hand. */
    private static final class Relay implements AutoCloseable {

        private final ServerSocket server;
        private final Thread acceptor;
        private final ConcurrentLinkedQueue<Script> scripts = new ConcurrentLinkedQueue<>();
        private final List<Seen> seen = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        Relay() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            acceptor = Thread.ofPlatform().daemon().name("xp-pair-relay").start(() -> {
                while (!closed) {
                    try {
                        Socket socket = server.accept();
                        Thread.ofPlatform().daemon().start(() -> serve(socket));
                    } catch (IOException stopped) {
                        return;
                    }
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        void expect(List<Script> answers) {
            scripts.addAll(answers);
        }

        List<Seen> seen() {
            return seen;
        }

        private void serve(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int state = 0;
                while (state < 4) {
                    int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    head.write(b);
                    state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1
                        : b == '\r' ? 1 : 0;
                }
                String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
                Map<String, String> headers = new TreeMap<>();
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
                    }
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                byte[] body = in.readNBytes(length);
                String path = lines[0].split(" ")[1];
                seen.add(new Seen(path, headers, body));
                Script script = scripts.poll();
                OutputStream out = socket.getOutputStream();
                if (script == null) {
                    byte[] none = "{\"error\":{\"message\":\"no answer scripted\"}}".getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: " + none.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(none);
                    out.flush();
                    return;
                }
                answer(out, script);
            } catch (IOException | RuntimeException clientLeft) {
                // The gateway gave up on the exchange, or the case ended it.
            }
        }

        private static void answer(OutputStream out, Script script) throws IOException {
            boolean chunked = script.contentType().startsWith("text/event-stream");
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(script.status()).append(' ').append(reason(script.status())).append("\r\n");
            head.append("Content-Type: ").append(script.contentType()).append("\r\n");
            script.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            if (chunked) {
                head.append("Transfer-Encoding: chunked\r\n");
            } else {
                // A body that is cut off is announced longer than it is, so the client finds the connection closed in it.
                head.append("Content-Length: ").append(script.body().length + (script.abort() ? 1000 : 0)).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (chunked) {
                // The body in pieces of chunkBytes bytes (all of it in one when that is 0), each its own chunk on the wire and
                // flushed, so that a character of several bytes can arrive in two.
                int piece = script.chunkBytes() > 0 ? script.chunkBytes() : Math.max(1, script.body().length);
                for (int from = 0; from < script.body().length; from += piece) {
                    int size = Math.min(piece, script.body().length - from);
                    out.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(script.body(), from, size);
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    if (script.chunkBytes() > 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!script.abort()) {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
            } else {
                out.write(script.body());
            }
            out.flush();
        }

        private static String reason(int status) {
            return switch (status) {
                case 200 -> "OK";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 402 -> "Payment Required";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 408 -> "Request Timeout";
                case 413 -> "Payload Too Large";
                case 422 -> "Unprocessable Entity";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "Status";
            };
        }

        @Override
        public void close() {
            closed = true;
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing a socket that is closing.
            }
        }
    }

    // ---- one play ----

    /** What the stand-ins were asked during a turn. */
    private record Reserved(BigDecimal credits) {
    }

    private record Settled(BigDecimal credits, long input, long output, String tier, int level, BigDecimal factor) {
    }

    private record Released(boolean definitive, String reason) {
    }

    private record Native(String account, List<String> toolIds) {
    }

    /** What the client got: a status, headers and a body or the events of a stream. */
    private record Reply(int status, Map<String, String> headers, JsonNode body, List<ObjectNode> events, String code, String message) {
    }

    private static final class Play implements AutoCloseable {

        private final PairKey pair;
        private final String caseId;
        private final Inputs input;
        private final Relay relay;
        private final String vendor;
        private final ObjectNode memberSettings;

        private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
        private final AccountPoolService accountPool = mock(AccountPoolService.class);
        private final AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
        private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
        private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
        private final ModelPricingService pricing = mock(ModelPricingService.class);
        private final AccountSettingsService accountSettings = mock(AccountSettingsService.class);
        private final ModelMappingService modelMapping = mock(ModelMappingService.class);
        private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
        private final AnthropicUpstreamClient anthropicClient = mock(AnthropicUpstreamClient.class);
        private final KiroUpstreamClient kiroClient = mock(KiroUpstreamClient.class);
        private final OpenAiUpstreamClient openAiClient = mock(OpenAiUpstreamClient.class);
        private final OpenAiResponseOwnerStore ownerStore = mock(OpenAiResponseOwnerStore.class);
        private final StatsService statsService = mock(StatsService.class);
        private final DisabledAccountScheduledTestService scheduledTests = mock(DisabledAccountScheduledTestService.class);
        private final AtomicLong clock = new AtomicLong();
        private final TierSessionBindings bindings = new TierSessionBindings(new ProxyProperties(), clock::get);
        private final XpLearnedLimits learnedLimits = new XpLearnedLimits(clock::get);
        private final XpSkipCounts skipCounts = new XpSkipCounts();
        private final ApiKeyPrincipal principal = new ApiKeyPrincipal(KEY_ID, "test", null, BigDecimal.ZERO);

        private final List<ProxyAccountEntity> cached = new ArrayList<>();
        private final List<TierPlan> plans = new CopyOnWriteArrayList<>();
        private final List<StatsService.RequestLog> logs = new CopyOnWriteArrayList<>();
        private final List<Reserved> reserved = new CopyOnWriteArrayList<>();
        private final List<Settled> settled = new CopyOnWriteArrayList<>();
        private final List<Released> released = new CopyOnWriteArrayList<>();
        private final List<Native> natives = new CopyOnWriteArrayList<>();
        private final ProxyFacade facade;

        Play(PairKey pair, String caseId, Map<String, byte[]> input) throws IOException {
            this.pair = pair;
            this.caseId = caseId;
            this.input = new Inputs(input);
            this.relay = new Relay();
            this.vendor = pair.vendor();
            this.memberSettings = this.input.has("member.json") ? (ObjectNode) JSON.readTree(this.input.get("member.json")) : JSON.createObjectNode();
            stubCollaborators();
            this.facade = facade();
        }

        // ---- the world around the gateway ----

        private ProxyAccountEntity relayAccount() {
            return account("relay-1", "OpenAI-compatible", "api-key", relay.url());
        }

        private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
            ProxyAccountEntity account = new ProxyAccountEntity();
            account.setAccountId(accountId);
            account.setProvider(provider);
            account.setAuthMethod(authMethod);
            account.setBaseUrl(baseUrl);
            account.setEnabled(true);
            return account;
        }

        private void stubCollaborators() {
            ProxyAccountEntity relayAccount = relayAccount();
            relayAccount.setAccessToken(RELAY_KEY);
            ProxyAccountEntity anthropic = account("anthropic-1", "Anthropic", "api-key", null);
            anthropic.setAccessToken("sk-ant-0000");
            ProxyAccountEntity kiro = account("kiro-1", "BuilderId", "idc", null);
            kiro.setAccessToken("kiro-token");
            kiro.setRegion("us-east-1");
            ProxyAccountEntity openai = account("openai-1", "OpenAI", "api-key", null);
            openai.setAccessToken("sk-openai-0000");
            ProxyAccountEntity codex = account("codex-1", "OpenAI", "codex", null);
            codex.setAccessToken("codex-token");
            cached.add(relayAccount);
            // The tier below the relay has the accounts of every native route unless the case says there are none in service.
            if (!input.has("native.json") || JSON.readTree(input.get("native.json")).path("accounts").asBoolean(true)) {
                cached.addAll(List.of(anthropic, kiro, openai));
                // Codex asks for a gpt model of the Codex accounts (its originator header says it is Codex), and only the
                // Responses entry is Codex, so the accounts of the other entries are the ones they always were.
                if (pair.entry() == Entry.RESPONSES) {
                    cached.add(codex);
                }
            }

            when(payments.config()).thenReturn(Mono.just(paymentConfig()));
            when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
            when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
            when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
                reserved.add(new Reserved(invocation.getArgument(2)));
                return Mono.empty();
            });
            when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
            ApiKeyService.DispatchLease lease = new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59));
            when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenReturn(Mono.just(lease));
            when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(lease));
            when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenAnswer(invocation -> {
                released.add(new Released(invocation.getArgument(2), invocation.getArgument(3)));
                return Mono.empty();
            });
            when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
                settled.add(new Settled(invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4), null, 0, null));
                return Mono.empty();
            });
            when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    settled.add(new Settled(invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4),
                        invocation.getArgument(5), invocation.getArgument(6), invocation.getArgument(7)));
                    return Mono.empty();
                });
            when(rateLimiter.check(KEY_ID)).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
            when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountSettings.routingPolicy()).thenReturn(Mono.just(RoutingPolicySettings.defaults()));
            when(statsService.log(any())).thenAnswer(invocation -> {
                logs.add(invocation.getArgument(0));
                return Mono.empty();
            });
            when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
            when(ownerStore.findOwner(anyString())).thenReturn(Mono.empty());
            when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
            when(accountPool.recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
                any(AccountPoolRequestContext.class))).thenReturn(Mono.empty());
            when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
                Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
                return cached.stream().filter(filter).toList();
            });
            when(accountPool.isUsable(any())).thenReturn(true);
            when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
                Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
                Optional<ProxyAccountEntity> found = cached.stream().filter(filter).findFirst();
                return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
            });
            when(accountPool.hasSelectableAccount(any())).thenAnswer(invocation -> {
                Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
                return Mono.just(cached.stream().anyMatch(filter));
            });
            when(accountPool.refreshCacheIfStale()).thenReturn(Mono.empty());
            when(admissionStore.acquire(any())).thenAnswer(invocation ->
                Mono.just(new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600))));
            when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
            when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
            when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
            when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
            when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
                ProxyAccountEntity account = invocation.getArgument(0);
                natives.add(new Native(account.getAccountId(), toolIds(invocation.getArgument(1))));
                return Mono.just(NATIVE_ANSWER);
            });
            when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
                ProxyAccountEntity account = invocation.getArgument(0);
                natives.add(new Native(account.getAccountId(), toolIds(invocation.getArgument(1))));
                return Mono.just(NATIVE_ANSWER);
            });
            when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
                ProxyAccountEntity account = invocation.getArgument(0);
                natives.add(new Native(account.getAccountId(), toolIds(invocation.getArgument(1))));
                return Mono.just(NATIVE_ANSWER);
            });
            when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> {
                ProxyAccountEntity account = invocation.getArgument(0);
                natives.add(new Native(account.getAccountId(), toolIds(invocation.getArgument(1))));
                return Mono.just(NATIVE_ANSWER);
            });
            when(registry.snapshot()).thenReturn(snapshot());
        }

        /** The ids a request carries for its tool calls, in the order it carries them: what a native tier is sent of them. */
        private static List<String> toolIds(JsonNode payload) {
            List<String> ids = new ArrayList<>();
            collectToolIds(payload, ids);
            return ids;
        }

        private static void collectToolIds(JsonNode node, List<String> ids) {
            if (node.isObject()) {
                node.properties().forEach(field -> {
                    boolean id = (field.getKey().equals("toolUseId") || field.getKey().equals("tool_call_id") || field.getKey().equals("call_id"))
                        && field.getValue().isString();
                    if (id) {
                        ids.add(field.getValue().asString());
                    } else if (field.getKey().equals("tool_calls") && field.getValue().isArray()) {
                        field.getValue().forEach(call -> ids.add(call.path("id").asString("")));
                    } else {
                        collectToolIds(field.getValue(), ids);
                    }
                });
            } else if (node.isArray()) {
                node.forEach(child -> collectToolIds(child, ids));
            }
        }

        private static PaymentConfig paymentConfig() {
            return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
                List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
                "popup", false, new BigDecimal("0.10"), 10, null, null);
        }

        // ---- the tiers: the owner's example, the relay first and a native tier below ----

        private Member member() {
            ObjectNode s = memberSettings;
            return new Member(vendor, "relay-1", "relay", "OPENAI_CHAT", true,
                s.path("vision").asBoolean(false), s.path("jsonSchema").asBoolean(false), s.path("priorityTier").asBoolean(false),
                s.path("cacheControlPassthrough").asBoolean(false), s.path("upstreamStream").asBoolean(true),
                s.path("reasoningMode").asString("NONE"), s.path("maxDurationSeconds").asInt(540),
                s.path("usageCacheReadInPrompt").asBoolean(true), s.path("usageCacheWriteInPrompt").asBoolean(true));
        }

        private SourceTierSnapshot snapshot() {
            Member member = member();
            ProxyAccountEntity account = relayAccount();
            List<MemberModel> models = new ArrayList<>();
            int window = memberSettings.path("maxContextTokens").asInt(vendor.equals("openai") ? 400_000 : 200_000);
            int imageCap = memberSettings.path("imageTokenCap").asInt(8192);
            Map<String, String> wire = vendor.equals("openai")
                ? Map.of("gpt-5.5", "relay-gpt")
                : Map.of("claude-sonnet-4.5", "relay-sonnet", "claude-opus-4.5", "relay-opus", "claude-haiku-4.5", "relay-haiku");
            wire.forEach((modelId, upstream) -> {
                MemberModel unverified = new MemberModel(vendor, "relay-1", modelId, upstream, window, imageCap, true, null, null);
                models.add(new MemberModel(vendor, "relay-1", modelId, upstream, window, imageCap, true,
                    Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, account)));
            });
            Set<AccountAllocationCategory> lower = vendor.equals("openai")
                ? EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY, AccountAllocationCategory.CODEX)
                : EnumSet.of(AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH);
            return SourceTierSnapshot.of(7L,
                List.of(new VendorConfig(vendor, true, false, false, true, 16, true, 7L)),
                List.of(
                    new Tier(vendor, "relay", 1, "relay", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true, true, null),
                    new Tier(vendor, "native", 2, "native", new BigDecimal("0.6"), lower, true, true, null)),
                List.of(member), models,
                List.of(gate()));
        }

        /** The gate of the pair: open to the key of the requests, as an administrator opens a pair on trial, unless the case says otherwise. */
        private PairGate gate() {
            JsonNode gate = input.has("gate.json") ? JSON.readTree(input.get("gate.json")) : JSON.createObjectNode();
            List<String> keys = new ArrayList<>();
            if (gate.has("keys")) {
                gate.path("keys").forEach(key -> keys.add(key.asString()));
            } else {
                keys.add(KEY_ID);
            }
            return new PairGate(vendor, "OPENAI_CHAT", pair.entry().name(), gate.path("state").asString("KEYS"), keys);
        }

        private ProxyFacade facade() {
            // The registry of this release, not one made for the test: what a pair is offered as is part of what is played.
            DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog,
                accountPool, payments, pricing, bindings, true, true, PairRegistry.defaults(), skipCounts, learnedLimits);
            TierRuntime.Planner recording = new TierRuntime.Planner() {
                @Override
                public Mono<TierPlan> plan(TierPlanRequest request) {
                    return planner.plan(request).doOnNext(plans::add);
                }

                @Override
                public TierSelectorParser.Stripped stripSelector(String model, Function<String, TierRuntime.Destination> destination) {
                    return planner.stripSelector(model, destination);
                }

                @Override
                public boolean wantsEntryFacts(TierEntry entry) {
                    return planner.wantsEntryFacts(entry);
                }
            };
            XpDispatcher dispatcher = new XpDispatcher(RELAY_CLIENT, new SplittableRandom(20261026L));
            return new ProxyFacade(
                apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings, scheduledTests, modelCatalog,
                modelMapping, rateLimiter, new KiroPayloadFactory(JSON, modelCatalog), kiroClient, openAiClient,
                new OpenAiRequestAdapter(JSON, modelCatalog), new OpenAiResponseFactory(JSON), new OpenAiResponseStateStore(JSON),
                new AnthropicRequestAdapter(JSON, modelCatalog), new AnthropicResponseFactory(JSON), anthropicClient,
                new GeminiRequestAdapter(JSON), new GeminiResponseFactory(JSON), payments, pricing, statsService, admissionStore,
                ownerStore, new TierRuntime(recording, bindings, learnedLimits), dispatcher, new ResponsesChatConverter(JSON));
        }

        // ---- the conversation ----

        Map<String, byte[]> run() throws Exception {
            Map<String, byte[]> out = new LinkedHashMap<>();
            ObjectNode request = (ObjectNode) JSON.readTree(input.get("request.json"));
            Reply previous = null;
            ObjectNode previousRequest = null;
            int turns = turnCount();
            for (int turn = 1; turn <= turns; turn++) {
                String prefix = turn == 1 ? "" : "turns/" + turn + "/";
                if (turn > 1) {
                    request = nextRequest(previousRequest, previous, prefix, turn);
                }
                relay.expect(scripts(prefix));
                int firstRelay = relay.seen().size();
                int firstPlan = plans.size();
                int firstLog = logs.size();
                int firstReserved = reserved.size();
                int firstSettled = settled.size();
                int firstReleased = released.size();
                int firstNative = natives.size();
                Reply reply;
                try {
                    reply = send(request);
                } catch (RuntimeException failure) {
                    // A request that is not planned over tiers is served as it always was, which this play has no accounts for.
                    if (plans.size() == firstPlan || !plans.getLast().tiered()) {
                        throw new AssertionError(caseId + ": turn " + turn + " was not planned over the tiers of the vendor", failure);
                    }
                    throw failure;
                }
                if (plans.size() == firstPlan || !plans.getLast().tiered()) {
                    throw new AssertionError(caseId + ": turn " + turn + " was not planned over the tiers of the vendor");
                }
                List<Seen> asked = relay.seen().subList(firstRelay, relay.seen().size());
                if (asked.size() > 1) {
                    throw new AssertionError(caseId + ": the relay was asked " + asked.size() + " times in turn " + turn);
                }
                if (!asked.isEmpty()) {
                    out.put(prefix + "relay-request.json", bytes(pretty(relayRequest(asked.getFirst()))));
                }
                ObjectNode replyJson = replyJson(reply);
                String replyText = pretty(replyJson);
                out.put(prefix + "client-response.json", bytes(replyText));
                ObjectNode route = route(plans.subList(firstPlan, plans.size()), logs.subList(firstLog, logs.size()),
                    natives.subList(firstNative, natives.size()));
                String routeText = pretty(route);
                out.put(prefix + "route.json", bytes(routeText));
                out.put(prefix + "billing.json", bytes(pretty(billing(reserved.subList(firstReserved, reserved.size()),
                    settled.subList(firstSettled, settled.size()), released.subList(firstReleased, released.size())))));
                assertNothingOfTheRelayReached(prefix, replyText, routeText);
                previousRequest = request;
                previous = reply;
            }
            if (!input.unread().isEmpty()) {
                throw new AssertionError(caseId + ": nothing reads " + input.unread());
            }
            return out;
        }

        private int turnCount() {
            int turns = 1;
            while (true) {
                String prefix = "turns/" + (turns + 1) + "/";
                if (input.names().stream().noneMatch(name -> name.startsWith(prefix))) {
                    return turns;
                }
                turns++;
            }
        }

        // ---- placeholders for the next turns ----

        private ObjectNode nextRequest(ObjectNode previousRequest, Reply previous, String prefix, int turn) {
            if (input.has(prefix + "request.json")) {
                String whole = new String(input.get(prefix + "request.json"), StandardCharsets.UTF_8);
                if (whole.contains(RESPONSE_PLACEHOLDER)) {
                    String id = previous == null || previous.status() != 200 ? null : responseId(previous);
                    if (id == null) {
                        throw new IllegalStateException(caseId + ": " + prefix + "request.json continues the response of turn "
                            + (turn - 1) + ", which was not answered with one");
                    }
                    whole = whole.replace(RESPONSE_PLACEHOLDER, id);
                }
                return (ObjectNode) JSON.readTree(whole);
            }
            if (previous == null || previous.status() != 200) {
                throw new IllegalStateException(caseId + ": turn " + (turn - 1) + " was not answered, so turn " + turn + " has no history");
            }
            ObjectNode request = previousRequest.deepCopy();
            // A Responses client sends its history as the items of the input; the others as messages.
            ArrayNode messages = request.withArray(pair.entry() == Entry.RESPONSES ? "input" : "messages");
            List<String> toolIds = new ArrayList<>();
            if (pair.entry() == Entry.RESPONSES) {
                for (JsonNode item : replayedItems(previous)) {
                    messages.add(item);
                    if (item.path("type").asString("").endsWith("_call")) {
                        toolIds.add(item.path("call_id").asString());
                    }
                }
            } else if (pair.entry() == Entry.MESSAGES) {
                ArrayNode content = assistantBlocks(previous);
                content.forEach(block -> {
                    if ("tool_use".equals(block.path("type").asString())) {
                        toolIds.add(block.path("id").asString());
                    }
                });
                // The client moves its cache mark to the end of what it sends; the earlier ones are taken off.
                messages.forEach(message -> stripCacheControl(message.path("content")));
                if (!content.isEmpty()) {
                    messages.addObject().put("role", "assistant").set("content", content);
                }
            } else {
                ObjectNode message = assistantMessage(previous);
                for (JsonNode call : message.path("tool_calls")) {
                    toolIds.add(call.path("id").asString());
                }
                messages.add(message);
            }
            byte[] followUp = input.get(prefix + "follow-up.json");
            if (followUp == null) {
                throw new IllegalStateException(caseId + ": " + prefix + " has neither a request nor a follow-up");
            }
            String text = new String(followUp, StandardCharsets.UTF_8);
            Matcher matcher = TOOL_PLACEHOLDER.matcher(text);
            StringBuilder substituted = new StringBuilder();
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                if (index >= toolIds.size()) {
                    throw new IllegalStateException(caseId + ": " + prefix + "follow-up.json names tool call " + index
                        + " but the answer had " + toolIds.size());
                }
                matcher.appendReplacement(substituted, Matcher.quoteReplacement(toolIds.get(index)));
            }
            matcher.appendTail(substituted);
            JsonNode added = JSON.readTree(substituted.toString());
            added.forEach(messages::add);
            return request;
        }

        /** The id of the response a Responses client was answered with: the body's, or the one the last event of the stream carries. */
        private String responseId(Reply reply) {
            JsonNode response = finalResponse(reply);
            return response == null || !response.path("id").isString() ? null : response.path("id").asString();
        }

        /** The response the answer is, or the one the last event of the stream carries; null when there is none. */
        private static JsonNode finalResponse(Reply reply) {
            if (reply.body() != null) {
                return reply.body();
            }
            JsonNode response = null;
            if (reply.events() != null) {
                for (ObjectNode event : reply.events()) {
                    String type = event.path("data").path("type").asString("");
                    if ((type.equals("response.completed") || type.equals("response.incomplete")) && event.path("data").path("response").isObject()) {
                        response = event.path("data").path("response");
                    }
                }
            }
            return response;
        }

        /**
         * The items of an answer as Codex puts them back into its history: what a client keeps of each item, which is not
         * the id nor the status the API gave it (the status of a custom tool call it keeps), a message of the model as its
         * text parts, a reasoning item with the fields it has.
         */
        private ArrayNode replayedItems(Reply reply) {
            ArrayNode items = JSON.createArrayNode();
            JsonNode response = finalResponse(reply);
            if (response == null) {
                return items;
            }
            for (JsonNode item : response.path("output")) {
                ObjectNode replayed = JSON.createObjectNode();
                switch (item.path("type").asString("")) {
                    case "message" -> {
                        replayed.put("type", "message").put("role", item.path("role").asString("assistant"));
                        ArrayNode content = replayed.putArray("content");
                        for (JsonNode part : item.path("content")) {
                            content.addObject().put("type", part.path("type").asString("output_text")).put("text", part.path("text").asString(""));
                        }
                    }
                    case "function_call" -> replayed.put("type", "function_call").put("name", item.path("name").asString(""))
                        .put("arguments", item.path("arguments").asString("")).put("call_id", item.path("call_id").asString(""));
                    case "custom_tool_call" -> replayed.put("type", "custom_tool_call").put("status", item.path("status").asString("completed"))
                        .put("call_id", item.path("call_id").asString("")).put("name", item.path("name").asString(""))
                        .put("input", item.path("input").asString(""));
                    case "reasoning" -> {
                        replayed.put("type", "reasoning");
                        replayed.set("summary", item.has("summary") ? item.path("summary").deepCopy() : JSON.createArrayNode());
                        replayed.set("content", item.path("content").isArray() ? item.path("content").deepCopy() : JSON.nullNode());
                        replayed.set("encrypted_content", item.path("encrypted_content").isString() ? item.path("encrypted_content") : JSON.nullNode());
                    }
                    default -> {
                        continue;
                    }
                }
                items.add(replayed);
            }
            return items;
        }

        private static void stripCacheControl(JsonNode content) {
            if (content.isArray()) {
                content.forEach(block -> {
                    if (block instanceof ObjectNode object) {
                        object.remove("cache_control");
                        stripCacheControl(object.path("content"));
                    }
                });
            }
        }

        /** The content of the assistant's answer to a Messages client, as the client assembles it from what it was given. */
        private ArrayNode assistantBlocks(Reply reply) {
            if (reply.body() != null) {
                return (ArrayNode) reply.body().path("content").deepCopy();
            }
            ArrayNode blocks = JSON.createArrayNode();
            Map<Integer, StringBuilder> partial = new LinkedHashMap<>();
            for (ObjectNode event : reply.events()) {
                JsonNode data = event.path("data");
                switch (data.path("type").asString("")) {
                    case "content_block_start" -> {
                        ObjectNode block = (ObjectNode) data.path("content_block").deepCopy();
                        blocks.add(block);
                        partial.put(data.path("index").asInt(), new StringBuilder());
                    }
                    case "content_block_delta" -> {
                        ObjectNode block = (ObjectNode) blocks.get(data.path("index").asInt());
                        JsonNode delta = data.path("delta");
                        if ("text_delta".equals(delta.path("type").asString())) {
                            block.put("text", block.path("text").asString("") + delta.path("text").asString());
                        } else if ("input_json_delta".equals(delta.path("type").asString())) {
                            partial.get(data.path("index").asInt()).append(delta.path("partial_json").asString());
                        }
                    }
                    case "content_block_stop" -> {
                        int index = data.path("index").asInt();
                        ObjectNode block = (ObjectNode) blocks.get(index);
                        if ("tool_use".equals(block.path("type").asString())) {
                            String json = partial.get(index).toString();
                            block.set("input", json.isBlank() ? JSON.createObjectNode() : JSON.readTree(json));
                        }
                    }
                    default -> {
                        // message_start, message_delta, message_stop and pings say nothing of the content.
                    }
                }
            }
            return blocks;
        }

        /** The assistant message of a Chat client, as it assembles it from the answer or from the chunks of a stream. */
        private ObjectNode assistantMessage(Reply reply) {
            if (reply.body() != null) {
                return (ObjectNode) reply.body().path("choices").path(0).path("message").deepCopy();
            }
            ObjectNode message = JSON.createObjectNode();
            message.put("role", "assistant");
            StringBuilder text = new StringBuilder();
            Map<Integer, ObjectNode> calls = new TreeMap<>();
            for (ObjectNode event : reply.events()) {
                JsonNode delta = event.path("data").path("choices").path(0).path("delta");
                if (delta.path("content").isString()) {
                    text.append(delta.path("content").asString());
                }
                for (JsonNode call : delta.path("tool_calls")) {
                    ObjectNode target = calls.computeIfAbsent(call.path("index").asInt(0), index -> {
                        ObjectNode created = JSON.createObjectNode();
                        created.put("id", "");
                        created.put("type", "function");
                        created.putObject("function").put("name", "").put("arguments", "");
                        return created;
                    });
                    if (call.path("id").isString()) {
                        target.put("id", call.path("id").asString());
                    }
                    ObjectNode function = (ObjectNode) target.path("function");
                    if (call.path("function").path("name").isString()) {
                        function.put("name", call.path("function").path("name").asString());
                    }
                    if (call.path("function").path("arguments").isString()) {
                        function.put("arguments", function.path("arguments").asString() + call.path("function").path("arguments").asString());
                    }
                }
            }
            if (text.isEmpty() && !calls.isEmpty()) {
                message.putNull("content");
            } else {
                message.put("content", text.toString());
            }
            if (!calls.isEmpty()) {
                ArrayNode array = message.putArray("tool_calls");
                calls.values().forEach(array::add);
            }
            return message;
        }

        // ---- the relay's answers ----

        private List<Script> scripts(String prefix) throws IOException {
            byte[] bytes = input.get(prefix + "relay.json");
            if (bytes == null) {
                return List.of();
            }
            JsonNode node = JSON.readTree(bytes);
            List<Script> scripts = new ArrayList<>();
            for (JsonNode one : node.isArray() ? node : List.of(node)) {
                byte[] body = new byte[0];
                String contentType = one.path("contentType").asString("");
                if (one.path("body").isString()) {
                    String file = one.path("body").asString();
                    body = input.get(prefix + file);
                    if (body == null) {
                        throw new IllegalStateException(caseId + ": " + prefix + "relay.json names the body " + file + ", which is not there");
                    }
                    if (contentType.isEmpty()) {
                        contentType = file.endsWith(".sse") ? "text/event-stream" : file.endsWith(".html") ? "text/html"
                            : file.endsWith(".txt") ? "text/plain" : "application/json";
                    }
                }
                Map<String, String> headers = new LinkedHashMap<>();
                one.path("headers").properties().forEach(field -> headers.put(field.getKey(), field.getValue().asString()));
                List<String> secrets = new ArrayList<>();
                one.path("mustNotReach").forEach(secret -> secrets.add(secret.asString()));
                scripts.add(new Script(one.path("status").asInt(200), contentType.isEmpty() ? "application/json" : contentType,
                    headers, body, one.path("abort").asBoolean(false), one.path("chunkBytes").asInt(0), secrets));
            }
            return scripts;
        }

        // ---- sending ----

        private Reply send(ObjectNode request) {
            String path = switch (pair.entry()) {
                case MESSAGES -> "/v1/messages";
                case RESPONSES -> "/v1/responses";
                default -> "/v1/chat/completions";
            };
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post(path);
            switch (pair.entry()) {
                case MESSAGES -> builder.header("x-claude-code-session-id", "11111111-1111-1111-1111-111111111111");
                case RESPONSES -> builder.header("session-id", "11111111-1111-1111-1111-111111111111")
                    .header("originator", "codex_exec").header("user-agent", "codex_exec/0.142.5 (Mac OS 27.0.0; arm64) dumb");
                default -> builder.header("x-session-id", "ses_00000000000000000000000000");
            }
            MockServerWebExchange exchange = MockServerWebExchange.from(builder);
            Mono<ResponseEntity<?>> call = switch (pair.entry()) {
                case MESSAGES -> facade.messages(request, exchange);
                case RESPONSES -> facade.responses(request, exchange);
                default -> facade.chat(request, exchange);
            };
            try {
                ResponseEntity<?> response = call.block(Duration.ofSeconds(60));
                return reply(response);
            } catch (ApiException failure) {
                return new Reply(failure.getStatus().value(), Map.of(), null, null, failure.getCode(), failure.getMessage());
            } catch (RuntimeException failure) {
                Throwable cause = failure.getCause() instanceof ApiException api ? api : failure;
                if (cause instanceof ApiException api) {
                    return new Reply(api.getStatus().value(), Map.of(), null, null, api.getCode(), api.getMessage());
                }
                throw failure;
            }
        }

        private Reply reply(ResponseEntity<?> response) {
            Map<String, String> headers = new LinkedHashMap<>();
            HttpHeaders http = response.getHeaders();
            http.forEach((name, values) -> headers.put(name, String.join(",", values)));
            Object body = response.getBody();
            if (body instanceof JsonNode json) {
                return new Reply(response.getStatusCode().value(), headers, json, null, null, null);
            }
            if (body instanceof Flux<?> flux) {
                List<ObjectNode> events = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<ServerSentEvent<String>> collected = ((Flux<ServerSentEvent<String>>) flux).collectList().block(Duration.ofSeconds(60));
                for (ServerSentEvent<String> event : collected) {
                    ObjectNode node = JSON.createObjectNode();
                    if (event.event() != null) {
                        node.put("event", event.event());
                    }
                    String data = event.data();
                    if (data != null) {
                        try {
                            node.set("data", JSON.readTree(data));
                        } catch (RuntimeException notJson) {
                            node.put("data", data);
                        }
                    }
                    events.add(node);
                }
                return new Reply(response.getStatusCode().value(), headers, null, events, null, null);
            }
            throw new IllegalStateException("The facade answered with a body of an unexpected kind: " + (body == null ? null : body.getClass()));
        }

        // ---- what is written ----

        private ObjectNode relayRequest(Seen seen) {
            ObjectNode node = JSON.createObjectNode();
            node.put("path", seen.path());
            ObjectNode headers = node.putObject("headers");
            for (Map.Entry<String, String> header : seen.headers().entrySet()) {
                if (!RELAY_HEADERS.contains(header.getKey())) {
                    throw new AssertionError(caseId + ": the relay was sent the header " + header.getKey());
                }
            }
            for (String name : List.of("accept", "authorization", "content-type")) {
                String value = seen.headers().get(name);
                if (value != null) {
                    headers.put(name, name.equals("authorization") ? "Bearer <relay key>" : value);
                }
            }
            if (!("Bearer " + RELAY_KEY).equals(seen.headers().get("authorization"))) {
                throw new AssertionError(caseId + ": the relay was not sent its own key");
            }
            node.set("body", JSON.readTree(seen.body()));
            return node;
        }

        private ObjectNode replyJson(Reply reply) {
            ObjectNode node = JSON.createObjectNode();
            node.put("status", reply.status());
            if (!reply.headers().isEmpty()) {
                ObjectNode headers = node.putObject("headers");
                reply.headers().forEach(headers::put);
            }
            if (reply.body() != null) {
                node.set("body", reply.body());
            } else if (reply.events() != null) {
                ArrayNode events = node.putArray("events");
                reply.events().forEach(events::add);
            } else {
                ObjectNode error = node.putObject("error");
                error.put("code", reply.code());
                error.put("message", reply.message());
            }
            return node;
        }

        private ObjectNode billing(List<Reserved> reserved, List<Settled> settled, List<Released> released) {
            ObjectNode node = JSON.createObjectNode();
            if (reserved.size() > 1 || settled.size() > 1 || released.size() > 1) {
                throw new AssertionError(caseId + ": a turn reserved " + reserved.size() + ", settled " + settled.size()
                    + " and released " + released.size() + " times");
            }
            node.put("reserved", reserved.isEmpty() ? null : reserved.getFirst().credits().stripTrailingZeros().toPlainString());
            if (settled.isEmpty()) {
                node.putNull("settled");
            } else {
                Settled s = settled.getFirst();
                ObjectNode charge = node.putObject("settled");
                charge.put("credits", s.credits().stripTrailingZeros().toPlainString());
                charge.put("inputTokens", s.input());
                charge.put("outputTokens", s.output());
                charge.put("tier", s.tier());
                charge.put("level", s.level());
                charge.put("priceFactor", s.factor() == null ? null : s.factor().stripTrailingZeros().toPlainString());
            }
            if (released.isEmpty()) {
                node.putNull("released");
            } else {
                ObjectNode release = node.putObject("released");
                release.put("definitive", released.getFirst().definitive());
                release.put("reason", released.getFirst().reason());
            }
            return node;
        }

        private ObjectNode route(List<TierPlan> plans, List<StatsService.RequestLog> logs, List<Native> natives) {
            ObjectNode node = JSON.createObjectNode();
            ArrayNode skips = node.putArray("relaysLeftOut");
            for (TierPlan plan : plans) {
                plan.xpSkips().forEach(skip -> {
                    ObjectNode one = skips.addObject();
                    one.put("tier", skip.tierId());
                    one.put("account", skip.accountId());
                    one.put("reason", skip.reason().name());
                    one.put("detail", skip.detail());
                });
            }
            ArrayNode attempts = node.putArray("attempts");
            for (StatsService.RequestLog log : logs) {
                ObjectNode one = attempts.addObject();
                one.put("account", log.accountId());
                one.put("tier", log.sourceTierRef());
                one.put("dispatch", log.sourceDispatch() == null ? "NATIVE" : log.sourceDispatch());
                one.put("wireModel", log.wireModel());
                one.put("success", log.success());
                one.put("status", log.statusCode());
                one.put("inputTokens", log.inputTokens());
                one.put("outputTokens", log.outputTokens());
                one.put("usageSource", log.usageSource());
                one.put("stopReason", log.stopReason());
                one.put("degraded", log.degradedFeatures());
                one.put("error", log.errorMessage());
                natives.stream().filter(n -> n.account().equals(log.accountId())).findFirst()
                    .ifPresent(n -> {
                        ArrayNode ids = one.putArray("nativeToolIds");
                        n.toolIds().forEach(ids::add);
                    });
            }
            return node;
        }

        /**
         * What the client was answered holds nothing of the relay: not its key, its address, the names it was asked for, nor
         * what the case says it leaked. What the user reads in their request log (the errors of the attempts) holds neither
         * its key nor the words the case says it leaked; the name the relay was asked for is in the log on purpose, which the
         * administrator reads.
         */
        private void assertNothingOfTheRelayReached(String prefix, String reply, String route) {
            List<String> hidden = new ArrayList<>(List.of(RELAY_KEY));
            for (String candidate : List.of("", "turns/2/", "turns/3/", "turns/4/")) {
                try {
                    for (Script script : scripts(candidate)) {
                        hidden.addAll(script.mustNotReach());
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Scripts that do not parse fail where they are used.
                }
            }
            List<String> clientOnly = List.of("127.0.0.1", "relay-sonnet", "relay-opus", "relay-haiku", "relay-gpt");
            String where = prefix.isEmpty() ? "the first turn" : prefix;
            for (String secret : hidden) {
                if (reply.contains(secret) || route.contains(secret)) {
                    throw new AssertionError(caseId + ": the answer or the log of " + where + " holds " + secret);
                }
            }
            for (String secret : clientOnly) {
                if (reply.contains(secret)) {
                    throw new AssertionError(caseId + ": the client's answer of " + where + " holds " + secret);
                }
            }
        }

        // ---- text ----

        private static byte[] bytes(String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }

        /** The JSON of a node, two-space indented with {@code \n} line ends on every platform, ids made at random replaced. */
        static String pretty(JsonNode node) {
            StringBuilder out = new StringBuilder();
            write(out, node, 0);
            out.append('\n');
            String text = out.toString();
            text = RANDOM_ID.matcher(text).replaceAll("$1<id>");
            text = RANDOM_ITEM_ID.matcher(text).replaceAll("$1<id>");
            return CREATED.matcher(text).replaceAll("$10");
        }

        private static void write(StringBuilder out, JsonNode node, int depth) {
            write(out, node, depth, false);
        }

        /** @param oneLine whether the elements of an array are written each on a line of its own, without indentation inside */
        private static void write(StringBuilder out, JsonNode node, int depth, boolean oneLine) {
            if (node.isObject()) {
                if (node.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                boolean first = true;
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    if (!first) {
                        out.append(",\n");
                    }
                    first = false;
                    out.append("  ".repeat(depth + 1)).append(JSON.writeValueAsString(field.getKey())).append(": ");
                    // The events of a stream are many and small: one to a line keeps the files readable and short.
                    write(out, field.getValue(), depth + 1, field.getKey().equals("events"));
                }
                out.append('\n').append("  ".repeat(depth)).append('}');
            } else if (node.isArray()) {
                if (node.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) {
                        out.append(",\n");
                    }
                    out.append("  ".repeat(depth + 1));
                    if (oneLine) {
                        out.append(JSON.writeValueAsString(node.get(i)));
                    } else {
                        write(out, node.get(i), depth + 1, false);
                    }
                }
                out.append('\n').append("  ".repeat(depth)).append(']');
            } else {
                out.append(JSON.writeValueAsString(node));
            }
        }

        private static String pretty(ObjectNode node) {
            return pretty((JsonNode) node);
        }

        @Override
        public void close() {
            relay.close();
        }
    }
}
