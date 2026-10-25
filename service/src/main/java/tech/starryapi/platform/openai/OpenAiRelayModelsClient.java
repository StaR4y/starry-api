package tech.starryapi.platform.openai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.egress.AccountEgressTransport;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks an OpenAI-format relay which models it serves, {@code GET <base>/models} (design 7.4), so that the names of a
 * mapping can be picked from the relay's own list and the context window it states can be filled in. It is the
 * administrator's call and goes out over the account's own route, like every call to the relay.
 *
 * <p>What comes back is only what a mapping can use: ids that are a valid wire name (the ones the table accepts, see
 * {@code ck_source_tier_member_models_upstream_model}), each once, at most {@link #MAX_MODELS}, and the window of a
 * model where the relay states one. Relays do not agree on the field: OpenRouter and many others write {@code
 * context_length}, vLLM {@code max_model_len}, LiteLLM {@code max_input_tokens}, some {@code context_window}; the first
 * that holds a positive number that fits a 32-bit integer is taken, and none is a null. A body is read to 4 MB at most
 * and for 20 seconds at most. A failure is an {@link UpstreamException} whose message is the gateway's own words (an
 * error status goes through the {@link RelayErrorMapper}, which keeps the relay's text out of it).
 */
@Component
public class OpenAiRelayModelsClient {

    /** The most of the answer that is read; a list of several hundred models is some hundred kilobytes. */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    static final int MAX_MODELS = 2000;
    static final Duration TIME_LIMIT = Duration.ofSeconds(20);
    private static final int ERROR_BODY_LIMIT_BYTES = 64 * 1024;

    private static final Pattern WIRE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,254}");
    private static final List<String> CONTEXT_FIELDS = List.of(
        "context_length", "context_window", "max_context_length", "max_model_len", "max_context_tokens", "max_input_tokens",
        "inputTokenLimit"
    );

    /** A model of the relay: {@code contextLength} is the window it states, null when it states none. */
    public record RelayModel(String id, Integer contextLength) {
    }

    /** {@code truncated} says that the relay listed more models than {@link #MAX_MODELS}. */
    public record RelayModels(List<RelayModel> models, boolean truncated) {
    }

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final RelayErrorMapper errorMapper;

    public OpenAiRelayModelsClient(ObjectMapper objectMapper, WebClient upstreamWebClient) {
        this.objectMapper = objectMapper;
        this.webClient = upstreamWebClient;
        this.errorMapper = new RelayErrorMapper(objectMapper);
    }

    public Mono<RelayModels> list(ProxyAccountEntity account) {
        if (account == null) {
            throw new IllegalArgumentException("The source account is required");
        }
        return Mono.defer(() -> webClient.get()
            .uri(OpenAiEndpoints.openAiApi(account.getBaseUrl(), "/models"))
            .attribute(AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> {
                headers.setBearerAuth(account.getAccessToken());
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            })
            .exchangeToMono(response -> receive(account, response))
            .timeout(TIME_LIMIT, Mono.error(() -> new UpstreamException(502, "The source did not list its models within the time limit", false))));
    }

    private Mono<RelayModels> receive(ProxyAccountEntity account, ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (!status.is2xxSuccessful()) {
            String retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            return read(response, ERROR_BODY_LIMIT_BYTES, false).flatMap(body ->
                Mono.<RelayModels>error(errorMapper.fromResponse(status.value(), new String(body, StandardCharsets.UTF_8), retryAfter,
                    account.getAccountId(), account.getAccessToken())));
        }
        return read(response, MAX_BODY_BYTES, true).map(this::parse);
    }

    /** The body up to {@code limit} bytes; past it an error when {@code strict}, else what came is enough. */
    private Mono<byte[]> read(ClientResponse response, int limit, boolean strict) {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        boolean[] over = {false};
        return response.bodyToFlux(DataBuffer.class)
            .map(buffer -> {
                try {
                    byte[] piece = new byte[buffer.readableByteCount()];
                    buffer.read(piece);
                    return piece;
                } finally {
                    DataBufferUtils.release(buffer);
                }
            })
            .doOnNext(piece -> {
                int room = limit - read.size();
                read.write(piece, 0, Math.max(0, Math.min(piece.length, room)));
                over[0] |= piece.length > room;
            })
            .takeUntil(piece -> over[0])
            .then(Mono.defer(() -> over[0] && strict
                ? Mono.<byte[]>error(new UpstreamException(502, "The source's model list is larger than the size limit", false))
                : Mono.just(read.toByteArray())))
            .onErrorResume(error -> strict ? Mono.error(error) : Mono.just(read.toByteArray()));
    }

    private RelayModels parse(byte[] body) {
        return parse(objectMapper, body);
    }

    /** The models of a body; see the class description. */
    static RelayModels parse(ObjectMapper mapper, byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException notJson) {
            throw notAList();
        }
        JsonNode list = root == null ? null : root.isArray() ? root : root.isObject()
            ? firstArray(root, "data", "models") : null;
        if (list == null) {
            throw notAList();
        }
        Map<String, RelayModel> models = new LinkedHashMap<>();
        boolean truncated = false;
        for (JsonNode entry : list) {
            RelayModel model = model(entry);
            if (model == null || models.containsKey(model.id())) {
                continue;
            }
            if (models.size() >= MAX_MODELS) {
                truncated = true;
                break;
            }
            models.put(model.id(), model);
        }
        return new RelayModels(List.copyOf(new ArrayList<>(models.values())), truncated);
    }

    private static JsonNode firstArray(JsonNode object, String... names) {
        for (String name : names) {
            if (object.path(name).isArray()) {
                return object.path(name);
            }
        }
        return null;
    }

    private static RelayModel model(JsonNode entry) {
        String id;
        if (entry.isString()) {
            id = entry.asString("");
        } else if (entry.isObject()) {
            id = firstText(entry, "id", "name", "model");
        } else {
            return null;
        }
        id = id.strip();
        if (!WIRE_NAME.matcher(id).matches()) {
            return null;
        }
        return new RelayModel(id, entry.isObject() ? contextLength(entry) : null);
    }

    private static String firstText(JsonNode object, String... names) {
        for (String name : names) {
            if (object.path(name).isString() && !object.path(name).asString("").isBlank()) {
                return object.path(name).asString("");
            }
        }
        return "";
    }

    private static Integer contextLength(JsonNode entry) {
        for (String field : CONTEXT_FIELDS) {
            Integer length = count(entry.path(field));
            if (length != null) {
                return length;
            }
        }
        // OpenRouter also states the window of the provider that serves the model.
        return count(entry.path("top_provider").path("context_length"));
    }

    private static Integer count(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0) {
            return value.intValue();
        }
        return null;
    }

    private static UpstreamException notAList() {
        return new UpstreamException(502, "The source did not return a list of models; check the base URL of the account", false);
    }
}
