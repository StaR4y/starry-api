package tech.starryapi.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI-format relays for the tests of the owner's example, on one loopback port: {@code /<account>/v1/chat/completions}
 * is the endpoint of the account of that name. A relay answers what a scenario scripts for it (a stream or a JSON body as
 * the request asks, an error, an error inside a 200, a stream cut off), and otherwise like a good relay; every request
 * that came is kept, with the headers it got. The answers are authored from the documented Chat Completions shapes, never
 * recorded from a service.
 *
 * <p>What a relay reports for an answer is {@link #INPUT_TOKENS} in and {@link #OUTPUT_TOKENS} out, which the sources of
 * the scenarios all report, so that what each tier charges for a request is comparable: 0.0039 at the standard price of
 * the owner's example ($3 and $15 per million), 0.00234 at 0.6 of it.
 */
public final class OwnerExampleRelays implements AutoCloseable {

    public static final int INPUT_TOKENS = 800;
    public static final int OUTPUT_TOKENS = 100;

    /** One request a relay was asked, with the headers it got (lower-cased). */
    public record Seen(String account, JsonNode body, Map<String, String> headers) {
    }

    /** How a relay answers a request, once it has it. */
    @FunctionalInterface
    public interface Reply {

        void send(HttpExchange exchange, JsonNode request) throws IOException, InterruptedException;

        /** The answer a good relay gives: the text, as a stream or as a body as the request asks. */
        static Reply text(String text) {
            return (exchange, request) -> completion(exchange, request, text, null, "stop", INPUT_TOKENS, OUTPUT_TOKENS);
        }

        /** A good answer whose usage block claims more than the request could have used: a relay that over-reports. */
        static Reply textReporting(String text, long promptTokens, long completionTokens) {
            return (exchange, request) -> completion(exchange, request, text, null, "stop", promptTokens, completionTokens);
        }

        /** An answer that calls one tool, under the id the relay gives it (a relay may give every call the same one). */
        static Reply toolCall(String id, String name, String arguments) {
            ObjectNode call = new ObjectMapper().createObjectNode();
            call.put("id", id).put("type", "function").putObject("function").put("name", name).put("arguments", arguments);
            return (exchange, request) -> completion(exchange, request, "", call, "tool_calls", INPUT_TOKENS, OUTPUT_TOKENS);
        }

        /** An HTTP error: the status, and the body the relays of the OpenAI family write. */
        static Reply failure(int status, String code) {
            return (exchange, request) -> {
                byte[] body = ("{\"error\":{\"message\":\"the relay says " + code + " at relay.internal.example.com\",\"type\":\"" + code
                    + "\",\"code\":\"" + code + "\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            };
        }

        /** An error inside a 200, as the relays that answer 200 whatever happened write it. */
        static Reply inBand(String code, String message) {
            return (exchange, request) -> {
                byte[] body = ("{\"error\":{\"message\":\"" + message + "\",\"type\":\"new_api_error\",\"code\":\"" + code + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            };
        }

        /** A 200 stream that ends without a single event. */
        static Reply cutBeforeAnything() {
            return (exchange, request) -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
            };
        }

        /** A stream that starts to answer and ends without a finish reason or [DONE]. */
        static Reply cutAfterStart(String text) {
            return (exchange, request) -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                event(out, chunk("{\"role\":\"assistant\",\"content\":\"\"}", "null"));
                event(out, chunk("{\"content\":" + new ObjectMapper().writeValueAsString(text) + "}", "null"));
            };
        }

        /**
         * Says it has the request ({@code arrived}), waits until the scenario lets it go ({@code release}) and then answers
         * as {@code then} does: a relay that is busy with a long generation.
         */
        static Reply holding(CountDownLatch arrived, CountDownLatch release, Reply then) {
            return (exchange, request) -> {
                arrived.countDown();
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IOException("The scenario did not let the relay go");
                }
                then.send(exchange, request);
            };
        }

        private static String chunk(String delta, String finish) {
            return "{\"id\":\"chatcmpl-relay0001\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"relay-model\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + finish + "}]}";
        }

        private static void event(OutputStream out, String data) throws IOException {
            out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static void completion(HttpExchange exchange, JsonNode request, String text, ObjectNode call, String finish,
            long promptTokens, long completionTokens) throws IOException {
            String usage = "{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens
                + ",\"total_tokens\":" + (promptTokens + completionTokens) + "}";
            ObjectMapper mapper = new ObjectMapper();
            if (!request.path("stream").asBoolean(false)) {
                ObjectNode message = mapper.createObjectNode().put("role", "assistant");
                message.put("content", text);
                if (call != null) {
                    message.putArray("tool_calls").add(call);
                }
                String body = "{\"id\":\"chatcmpl-relay0001\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"relay-model\","
                    + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finish + "\"}],\"usage\":" + usage + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            event(out, chunk("{\"role\":\"assistant\",\"content\":\"\"}", "null"));
            if (!text.isEmpty()) {
                event(out, chunk("{\"content\":" + mapper.writeValueAsString(text) + "}", "null"));
            }
            if (call != null) {
                ObjectNode start = call.deepCopy();
                start.put("index", 0);
                String arguments = start.path("function").path("arguments").asString();
                ((ObjectNode) start.path("function")).put("arguments", "");
                event(out, chunk("{\"tool_calls\":[" + start + "]}", "null"));
                event(out, chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":" + mapper.writeValueAsString(arguments) + "}}]}", "null"));
            }
            event(out, chunk("{}", "\"" + finish + "\""));
            event(out, "{\"id\":\"chatcmpl-relay0001\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                + "\"model\":\"relay-model\",\"choices\":[],\"usage\":" + usage + "}");
            event(out, "[DONE]");
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "owner-example-relay");
        thread.setDaemon(true);
        return thread;
    });
    private final Deque<Reply> onceForAny = new ArrayDeque<>();
    private final Map<String, Deque<Reply>> once = new HashMap<>();
    private final Map<String, Reply> always = new HashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** @param onRequest told the name of the account of each request as it comes, before the relay answers it */
    public OwnerExampleRelays(Consumer<String> onRequest) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                handle(exchange, onRequest);
            } catch (Exception clientLeft) {
                // The gateway gave up on the exchange, or the scenario ended it.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /** The base URL of the account of that name, as a relay account is registered with it. */
    public String url(String account) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + account + "/v1";
    }

    public List<Seen> seen() {
        return seen;
    }

    /** The replies to the next requests of the account, one each. */
    public synchronized void next(String account, Reply... replies) {
        once.computeIfAbsent(account, ignored -> new ArrayDeque<>()).addAll(List.of(replies));
    }

    /** The replies to the next requests, whichever account they come to: for a pool that does not say which relay it asks first. */
    public synchronized void nextAny(Reply... replies) {
        onceForAny.addAll(List.of(replies));
    }

    /** The reply to every request of the account that no scripted reply is left for. */
    public synchronized void always(String account, Reply reply) {
        always.put(account, reply);
    }

    /** Back to answering like a good relay. */
    public synchronized void forget() {
        onceForAny.clear();
        once.clear();
        always.clear();
    }

    private synchronized Reply replyFor(String account) {
        if (!onceForAny.isEmpty()) {
            return onceForAny.poll();
        }
        Deque<Reply> queue = once.get(account);
        if (queue != null && !queue.isEmpty()) {
            return queue.poll();
        }
        return always.getOrDefault(account, Reply.text("from " + account));
    }

    private void handle(HttpExchange exchange, Consumer<String> onRequest) throws IOException, InterruptedException {
        String account = exchange.getRequestURI().getPath().split("/")[1];
        JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        seen.add(new Seen(account, request, headers));
        onRequest.accept(account);
        replyFor(account).send(exchange, request);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
