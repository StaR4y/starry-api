package tech.starryapi.sourcetier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An OpenAI-format relay on the loopback address that answers the probes of a verification the way a good relay does,
 * and can be set to fail each of them, one way at a time. It tells the probes apart by their content: a tool message at
 * the end is the second round of the loop, a request that offers the function is the tool call, an image part is the
 * vision probe, a long user message is the cache probe, anything else is the short question. The answer is JSON or an
 * event stream as the request asks, and the usage follows {@link #cache}. What is asked of it is kept in {@link #requests}.
 */
public final class RelaySimulator implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How the relay counts its cache, for the cache probe. */
    public enum Cache {
        /** Caches nothing, or says nothing of it. */
        NONE,
        /** Anthropic's way: the first call reports a cache write, the second a read, and the prompt is what is left. */
        NOT_IN_PROMPT,
        /** OpenAI's way: the prompt is the whole prompt, the second call reports the read as a part of it. */
        READ_IN_PROMPT
    }

    /** One request the relay was asked. */
    public record Seen(String method, String path, String authorization, JsonNode body) {
    }

    public volatile boolean finishReason = true;
    public volatile boolean usage = true;
    public volatile boolean callsTheFunction = true;
    public volatile boolean validArguments = true;
    public volatile boolean answersAfterTheResult = true;
    public volatile boolean usageInStreams = true;
    public volatile Cache cache = Cache.NONE;
    /** When not 0, every request is answered with this status and an error body. */
    public volatile int errorStatus;
    /** The code of the error the relay answers with when it fails. */
    public volatile String errorCode = "server_error";
    /** The models the relay does not serve (404 model_not_found); compared as written. */
    public final Set<String> unknownModels = new HashSet<>();
    /** The delay before every answer, in milliseconds. */
    public volatile long delayMillis;
    /** From this request to the chat completions endpoint on (1 is the first), every one is answered with a 500. */
    public volatile int failFromChat = Integer.MAX_VALUE;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    private final Set<String> seenPrompts = new HashSet<>();
    private volatile String modelsBody = "{\"data\":[]}";

    public RelaySimulator() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (Exception clientLeft) {
                // The test ended the exchange, or the client did; a mistake of this class shows as the client's broken exchange.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Seen> requests() {
        return requests;
    }

    /** The requests to the chat completions endpoint. */
    public List<Seen> chats() {
        return requests.stream().filter(seen -> seen.path().endsWith("/chat/completions")).toList();
    }

    public void listsModels(String body) {
        this.modelsBody = body;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- the relay ----

    private void handle(HttpExchange exchange) throws Exception {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        JsonNode request = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
        requests.add(new Seen(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), request));
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        if (errorStatus != 0 || (path.endsWith("/chat/completions") && chats().size() >= failFromChat)) {
            json(exchange, errorStatus != 0 ? errorStatus : 500, "{\"error\":{\"code\":\"" + errorCode
                + "\",\"message\":\"the relay is broken at relay.internal.example.com\"}}");
            return;
        }
        if (path.endsWith("/models")) {
            json(exchange, 200, modelsBody);
            return;
        }
        if (unknownModels.contains(request.path("model").asString(""))) {
            json(exchange, 404, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"no such model at relay.internal.example.com\"}}");
            return;
        }
        answer(exchange, request);
    }

    private void answer(HttpExchange exchange, JsonNode request) throws IOException {
        JsonNode messages = request.path("messages");
        JsonNode last = messages.path(messages.size() - 1);
        String prompt = lastUserText(messages);
        boolean stream = request.path("stream").asBoolean(false);
        boolean includeUsage = request.path("stream_options").path("include_usage").asBoolean(false);
        String content;
        ObjectNode call = null;
        ObjectNode usageNode = JSON.createObjectNode();
        if ("tool".equals(last.path("role").asString(""))) {
            content = answersAfterTheResult ? "It is 21 degrees and sunny in Paris." : "";
            usageNode.put("prompt_tokens", 60).put("completion_tokens", 12);
        } else if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            content = callsTheFunction ? "" : "I cannot call functions.";
            if (callsTheFunction) {
                call = JSON.createObjectNode();
                call.put("id", "call_0");
                call.put("type", "function");
                call.putObject("function").put("name", "get_weather").put("arguments", validArguments ? "{\"city\":\"Paris\"}" : "{\"city\":");
            }
            usageNode.put("prompt_tokens", 80).put("completion_tokens", 15);
        } else if (hasImage(messages)) {
            content = "A single pixel.";
            usageNode.put("prompt_tokens", 25).put("completion_tokens", 4);
        } else if (prompt.length() > 4000) {
            content = "ok";
            cacheUsage(usageNode, prompt);
        } else {
            content = "ok";
            usageNode.put("prompt_tokens", 12).put("completion_tokens", 2);
        }
        String reason = call != null ? "tool_calls" : "stop";
        if (stream) {
            events(exchange, content, call, reason, includeUsage && usageInStreams && usage ? usageNode : null);
        } else {
            ObjectNode body = JSON.createObjectNode();
            body.put("id", "chatcmpl-1");
            ObjectNode choice = body.putArray("choices").addObject();
            choice.put("index", 0);
            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");
            message.put("content", content);
            if (call != null) {
                message.putArray("tool_calls").add(call);
            }
            if (finishReason) {
                choice.put("finish_reason", reason);
            }
            if (usage) {
                body.set("usage", usageNode);
            }
            json(exchange, 200, body.toString());
        }
    }

    /** What the relay reports for the long prompt: the first time it is seen it is a write, the second a read. */
    private void cacheUsage(ObjectNode usageNode, String prompt) {
        int total = (int) (prompt.length() / 4.5);
        boolean again;
        synchronized (seenPrompts) {
            again = !seenPrompts.add(prompt.substring(0, 120));
        }
        usageNode.put("completion_tokens", 2);
        switch (cache) {
            case NONE -> usageNode.put("prompt_tokens", total);
            case NOT_IN_PROMPT -> {
                usageNode.put("prompt_tokens", 5);
                usageNode.put(again ? "cache_read_input_tokens" : "cache_creation_input_tokens", total - 5);
            }
            case READ_IN_PROMPT -> {
                usageNode.put("prompt_tokens", total);
                if (again) {
                    usageNode.putObject("prompt_tokens_details").put("cached_tokens", 1024);
                }
            }
        }
    }

    private static String lastUserText(JsonNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("user".equals(message.path("role").asString("")) && message.path("content").isString()) {
                return message.path("content").asString("");
            }
        }
        return "";
    }

    private static boolean hasImage(JsonNode messages) {
        for (JsonNode message : messages) {
            if (message.path("content").isArray()) {
                for (JsonNode part : message.path("content")) {
                    if ("image_url".equals(part.path("type").asString(""))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void events(HttpExchange exchange, String content, ObjectNode call, String reason, ObjectNode usageNode) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        event(out, chunk(delta -> delta.put("role", "assistant").put("content", ""), null));
        if (!content.isEmpty()) {
            event(out, chunk(delta -> delta.put("content", content), null));
        }
        if (call != null) {
            String arguments = call.path("function").path("arguments").asString("");
            int cut = arguments.length() / 2;
            event(out, chunk(delta -> {
                ObjectNode part = delta.putArray("tool_calls").addObject();
                part.put("index", 0).put("id", call.path("id").asString("")).put("type", "function");
                part.putObject("function").put("name", "get_weather").put("arguments", arguments.substring(0, cut));
            }, null));
            event(out, chunk(delta -> delta.putArray("tool_calls").addObject().put("index", 0)
                .putObject("function").put("arguments", arguments.substring(cut)), null));
        }
        ObjectNode finish = chunk(delta -> { }, finishReason ? reason : null);
        event(out, finish);
        if (usageNode != null) {
            ObjectNode usageChunk = JSON.createObjectNode();
            usageChunk.putArray("choices");
            usageChunk.set("usage", usageNode);
            event(out, usageChunk);
        }
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static ObjectNode chunk(java.util.function.Consumer<ObjectNode> delta, String finishReason) {
        ObjectNode chunk = JSON.createObjectNode();
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        delta.accept(choice.putObject("delta"));
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }
        return chunk;
    }

    private static void event(OutputStream out, JsonNode data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
