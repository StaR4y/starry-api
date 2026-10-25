package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tech.starryapi.platform.xproto.FeaturePolicy.Baseline;
import tech.starryapi.platform.xproto.FeaturePolicy.Treatment;
import tech.starryapi.platform.xproto.FeaturePolicy.Verdict;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The policy names, from the original request, what the sanitizer leaves out of the body it builds (design 7.9 and
 * 7.6 say the two use the same words), and the request log joins the two lists. They must not disagree: what the
 * policy carries the sanitizer must not have degraded, and what the sanitizer degraded the policy must have named. For
 * the features both can see (image, audio, file, cache control, response schema, the two reasoning requests and a tool
 * choice of a kind a relay has no word for) the two agree both ways on Chat requests; on Messages requests the
 * conversion flattens all of them but reasoning before the sanitizer sees anything, so there the reasoning request is
 * the one they share.
 *
 * <p>Every request below is tried with every combination of a member's switches and both baselines. Images are in user
 * messages: the sanitizer replaces an image in any other role whatever the member, which no real client sends.
 */
class FeaturePolicySanitizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AnthropicRequestAdapter ADAPTER = new AnthropicRequestAdapter(JSON, new DefaultModelCatalogProvider());

    /** The labels the sanitizer records, which the policy has to use for the same things. */
    private static final Set<Feature> SHARED = EnumSet.of(
        Feature.IMAGE, Feature.AUDIO, Feature.FILE, Feature.CACHE_CONTROL, Feature.JSON_SCHEMA, Feature.THINKING,
        Feature.REASONING_EFFORT, Feature.TOOL_SEARCH, Feature.TOOL_CHOICE
    );

    private record Sample(String name, Entry entry, String body) {
    }

    private static final String IMAGE_PART = "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,iVBORw0KGgo=\"}}";
    private static final String SCHEMA = "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"s\",\"schema\":{\"type\":\"object\"}}}";
    private static final String CACHED_TEXT = "{\"type\":\"text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}";
    private static final String TOOL_CALL = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}";

    private static String chat(String extra, String... messages) {
        return "{\"model\":\"gpt-5\"" + (extra.isEmpty() ? "" : "," + extra) + ",\"messages\":[" + String.join(",", messages) + "]}";
    }

    private static String user(String content) {
        return "{\"role\":\"user\",\"content\":" + content + "}";
    }

    private static String anthropic(String extra, String... messages) {
        return "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":1000" + (extra.isEmpty() ? "" : "," + extra)
            + ",\"messages\":[" + String.join(",", messages) + "]}";
    }

    /** The fields of the Chat API that a passthrough route hands to the account whole, and the relay's body has no place for. */
    private static final String NATIVE_ONLY_FIELDS = "\"verbosity\":\"low\",\"logit_bias\":{\"50256\":-100},"
        + "\"prompt_cache_key\":\"sess-1\",\"prompt_cache_retention\":\"24h\",\"safety_identifier\":\"u-hash\"";

    private static List<Sample> samples() {
        List<Sample> samples = new ArrayList<>();
        samples.add(new Sample("claude code, new prompt", Entry.MESSAGES,
            RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(RequestFeatureDetectorTest.NEXT_PROMPT)));
        samples.add(new Sample("claude code, tool continuation", Entry.MESSAGES,
            RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(RequestFeatureDetectorTest.TOOL_RESULT_LAST)));
        samples.add(new Sample("messages, budget", Entry.MESSAGES,
            anthropic("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":9000}", "{\"role\":\"user\",\"content\":\"q\"}")));
        samples.add(new Sample("messages, adaptive without a known effort", Entry.MESSAGES,
            anthropic("\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"turbo\"}", "{\"role\":\"user\",\"content\":\"q\"}")));
        samples.add(new Sample("messages, effort without thinking", Entry.MESSAGES,
            anthropic("\"output_config\":{\"effort\":\"low\"}", "{\"role\":\"user\",\"content\":\"q\"}")));
        samples.add(new Sample("messages, images, documents and an error", Entry.MESSAGES, anthropic("",
            "{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://x.example/a.png\"}},"
                + "{\"type\":\"document\",\"source\":{\"type\":\"url\",\"url\":\"https://x.example/a.pdf\"}},"
                + "{\"type\":\"text\",\"text\":\"look\",\"cache_control\":{\"type\":\"ephemeral\"}}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"f\",\"input\":{}}]}",
            "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"boom\",\"is_error\":true}]}")));
        samples.add(new Sample("chat, plain", Entry.CHAT, chat("", user("\"q\""))));
        samples.add(new Sample("chat, image", Entry.CHAT, chat("", user("[{\"type\":\"text\",\"text\":\"look\"}," + IMAGE_PART + "]"))));
        samples.add(new Sample("chat, schema", Entry.CHAT, chat(SCHEMA, user("\"q\""))));
        samples.add(new Sample("chat, audio and file", Entry.CHAT, chat("", user(
            "[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}},{\"type\":\"file\",\"file\":{\"file_id\":\"f1\"}}]"))));
        samples.add(new Sample("chat, cache control on a message and a part", Entry.CHAT, chat("",
            "{\"role\":\"system\",\"content\":\"s\",\"cache_control\":{\"type\":\"ephemeral\"}}", user("[" + CACHED_TEXT + "]"))));
        samples.add(new Sample("chat, effort on a new turn", Entry.CHAT, chat("\"reasoning_effort\":\"high\"", user("\"q\""))));
        samples.add(new Sample("chat, effort on a tool continuation", Entry.CHAT, chat("\"reasoning_effort\":\"high\"",
            user("\"q\""), TOOL_CALL, "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"r\"}")));
        samples.add(new Sample("chat, tool choice that restricts the tools", Entry.CHAT, chat(
            "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}},{\"type\":\"function\",\"function\":{\"name\":\"g\"}}],"
                + "\"tool_choice\":{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"required\","
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"g\"}}]}}", user("\"q\""))));
        samples.add(new Sample("chat, tool choices a relay is given", Entry.CHAT, chat(
            "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}],"
                + "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"f\"}}", user("\"q\""))));
        samples.add(new Sample("chat, a tool choice without tools", Entry.CHAT, chat(
            "\"tool_choice\":{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"auto\",\"tools\":[]}}", user("\"q\""))));
        samples.add(new Sample("chat, effort on a tool continuation of a gpt model", Entry.CHAT, chat("\"reasoning_effort\":\"minimal\"",
            user("\"q\""), TOOL_CALL, "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"r\"}")));
        samples.add(new Sample("chat, thinking budget", Entry.CHAT,
            chat("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2000}", user("\"q\""))));
        samples.add(new Sample("chat, fields only a native account is handed", Entry.CHAT, chat(NATIVE_ONLY_FIELDS, user("\"q\""))));
        samples.add(new Sample("chat, everything at once", Entry.CHAT, chat("\"reasoning_effort\":\"low\"," + SCHEMA,
            "{\"role\":\"developer\",\"content\":[" + CACHED_TEXT + "]}",
            user("[" + CACHED_TEXT + "," + IMAGE_PART + ",{\"type\":\"file\",\"file\":{\"file_id\":\"f1\"}}]"))));
        return samples;
    }

    private static final int COMBINATIONS = 256;

    /**
     * Bits 0 to 5 are the switches of a member and the billed fast mode; bit 6 is a Claude model, which drops reasoning on
     * a continuation; bit 7 is an upstream that refuses a tool history in a request without tools.
     */
    private static Settings settings(int bits) {
        return new Settings((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, false, (bits & 16) != 0,
            (bits & 32) != 0, false, (bits & 64) != 0, (bits & 128) != 0);
    }

    private static Wire wire(Sample sample, JsonNode body, Settings settings) {
        return sample.entry() == Entry.MESSAGES
            ? ChatWireSanitizer.sanitize(ADAPTER.messagesToChat(body), body, settings, "relay-model")
            : ChatWireSanitizer.sanitize(body, null, settings, "relay-model");
    }

    private static Set<String> labels(List<Feature> features) {
        Set<String> labels = new LinkedHashSet<>();
        features.forEach(feature -> labels.add(feature.label()));
        return labels;
    }

    @Test
    void whatTheSanitizerLeavesOutTheVerdictHasNamed() {
        for (Sample sample : samples()) {
            JsonNode body = JSON.readTree(sample.body());
            Detected detected = RequestFeatureDetector.detect(sample.entry(), body);
            for (int bits = 0; bits < COMBINATIONS; bits++) {
                Settings settings = settings(bits);
                Wire wire = wire(sample, body, settings);
                for (Baseline baseline : Baseline.values()) {
                    Verdict verdict = FeaturePolicy.judge(detected, baseline, settings);
                    Set<String> named = labels(verdict.soft());
                    named.addAll(labels(verdict.hard()));
                    assertThat(wire.degraded()).as("%s, %s, %s", sample.name(), baseline, settings).isSubsetOf(named);
                }
            }
        }
    }

    @Test
    void whatThePolicyCarriesTheSanitizerHasNotDegraded() {
        for (Sample sample : samples()) {
            JsonNode body = JSON.readTree(sample.body());
            Detected detected = RequestFeatureDetector.detect(sample.entry(), body);
            for (int bits = 0; bits < COMBINATIONS; bits++) {
                Settings settings = settings(bits);
                Wire wire = wire(sample, body, settings);
                for (Baseline baseline : Baseline.values()) {
                    for (Feature feature : detected.features()) {
                        if (FeaturePolicy.treatment(feature, detected, baseline, settings) == Treatment.CARRY) {
                            assertThat(wire.degraded()).as("%s, %s, %s, %s", sample.name(), baseline, settings, feature)
                                .doesNotContain(feature.label());
                        }
                    }
                }
            }
        }
    }

    @Test
    void onChatRequestsTheTwoAgreeBothWaysOnTheFeaturesTheyShare() {
        for (Sample sample : samples()) {
            if (sample.entry() != Entry.CHAT) {
                continue;
            }
            JsonNode body = JSON.readTree(sample.body());
            Detected detected = RequestFeatureDetector.detect(Entry.CHAT, body);
            for (int bits = 0; bits < COMBINATIONS; bits++) {
                Settings settings = settings(bits);
                Set<String> degraded = new LinkedHashSet<>(wire(sample, body, settings).degraded());
                for (Baseline baseline : Baseline.values()) {
                    Set<String> expected = new LinkedHashSet<>();
                    for (Feature feature : SHARED) {
                        if (detected.has(feature) && FeaturePolicy.treatment(feature, detected, baseline, settings) != Treatment.CARRY) {
                            expected.add(feature.label());
                        }
                    }
                    assertThat(degraded).as("%s, %s, %s", sample.name(), baseline, settings).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void onMessagesRequestsTheyAgreeBothWaysOnReasoningAndOnTheToolSearchTool() {
        for (Sample sample : samples()) {
            if (sample.entry() != Entry.MESSAGES) {
                continue;
            }
            JsonNode body = JSON.readTree(sample.body());
            Detected detected = RequestFeatureDetector.detect(Entry.MESSAGES, body);
            for (int bits = 0; bits < COMBINATIONS; bits++) {
                Settings settings = settings(bits);
                Set<String> degraded = new LinkedHashSet<>(wire(sample, body, settings).degraded());
                Set<String> expected = new LinkedHashSet<>();
                if (detected.has(Feature.THINKING)
                    && FeaturePolicy.treatment(Feature.THINKING, detected, Baseline.TEXT_ONLY, settings) != Treatment.CARRY) {
                    expected.add(Feature.THINKING.label());
                }
                // The sanitizer takes the tool out, which the policy names as left out; it is never carried.
                if (detected.has(Feature.TOOL_SEARCH)) {
                    assertThat(FeaturePolicy.treatment(Feature.TOOL_SEARCH, detected, Baseline.TEXT_ONLY, settings)).isEqualTo(Treatment.SOFT);
                    expected.add(Feature.TOOL_SEARCH.label());
                }
                assertThat(degraded).as("%s, %s", sample.name(), settings).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    /**
     * A field that the sanitizer leaves out and a native account would have been handed must be a feature, or the answer
     * is made without it and nothing says so (design 7.2, P3): the two lists are joined here, by the keys the relay's body
     * has not got and the features the request has.
     */
    @Test
    void everyFieldTheRelayIsNotSentThatANativeAccountIsHandedIsNamedAndTreatedAsTheBaselineSays() {
        Map<String, Feature> named = new LinkedHashMap<>();
        named.put("verbosity", Feature.VERBOSITY);
        named.put("logit_bias", Feature.LOGIT_BIAS);
        named.put("prompt_cache_key", Feature.PROMPT_CACHE_KEY);
        named.put("prompt_cache_retention", Feature.PROMPT_CACHE_RETENTION);
        named.put("safety_identifier", Feature.SAFETY_IDENTIFIER);
        JsonNode body = JSON.readTree(chat(NATIVE_ONLY_FIELDS, user("\"q\"")));
        Detected detected = RequestFeatureDetector.detect(Entry.CHAT, body);

        for (int bits = 0; bits < COMBINATIONS; bits++) {
            Settings settings = settings(bits);
            Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(body, null, settings, "relay-model"), settings);
            named.forEach((key, feature) -> {
                assertThat(body.has(key)).as(key).isTrue();
                assertThat(wire.body().has(key)).as("%s is not sent, %s", key, settings).isFalse();
                assertThat(detected.has(feature)).as("%s is named", key).isTrue();
                boolean changesTheAnswer = feature == Feature.VERBOSITY || feature == Feature.LOGIT_BIAS;
                assertThat(FeaturePolicy.treatment(feature, detected, Baseline.PASSTHROUGH, settings))
                    .as("%s on a passthrough route", key).isEqualTo(changesTheAnswer ? Treatment.HARD : Treatment.SOFT);
                assertThat(FeaturePolicy.treatment(feature, detected, Baseline.TEXT_ONLY, settings))
                    .as("%s on a text route", key).isEqualTo(Treatment.SOFT);
            });
        }
    }

    /**
     * The canonicalizer records the repairs it makes in the same list. They are not features of the request, so the
     * policy has no word for them, and they must not be mistaken for ones: the names are disjoint from the features'. A
     * repair is recorded only where the member's settings say the native path of its model makes it, so that a request
     * that a native account is handed whole is never given a change the policy would have to call a loss.
     */
    @Test
    void theRepairsTheCanonicalizerRecordsAreNeverFeaturesAndFollowTheSettingsOfTheMember() {
        Set<String> repairs = Set.of(
            ChatCanonicalizer.EMPTY_TOOL_RESULT, ChatCanonicalizer.EMPTY_USER_MESSAGE, ChatCanonicalizer.TOOL_HISTORY_FLATTENED);
        for (Feature feature : Feature.values()) {
            assertThat(repairs).as("a repair named like %s", feature).doesNotContain(feature.label());
        }
        boolean flattenedSomewhere = false;
        boolean keptSomewhere = false;
        for (Sample sample : samples()) {
            JsonNode body = JSON.readTree(sample.body());
            Detected detected = RequestFeatureDetector.detect(sample.entry(), body);
            boolean hasToolHistory = body.toString().contains("tool_calls") || body.toString().contains("tool_use");
            boolean declaresTools = body.path("tools").isArray() && !body.path("tools").isEmpty();
            for (int bits = 0; bits < COMBINATIONS; bits++) {
                Settings settings = settings(bits);
                Wire sanitized = wire(sample, body, settings);
                Wire wire = ChatCanonicalizer.canonicalize(sanitized, settings);
                String where = sample.name() + ", " + settings;

                Set<String> added = new LinkedHashSet<>(wire.degraded());
                added.removeAll(sanitized.degraded());
                assertThat(repairs).as(where).containsAll(added);
                for (Baseline baseline : Baseline.values()) {
                    Verdict verdict = FeaturePolicy.judge(detected, baseline, settings);
                    Set<String> named = labels(verdict.soft());
                    named.addAll(labels(verdict.hard()));
                    // Everything the request log gets is a feature the verdict named or a repair.
                    Set<String> unexplained = new LinkedHashSet<>(wire.degraded());
                    unexplained.removeAll(named);
                    assertThat(repairs).as(where + ", " + baseline).containsAll(unexplained);
                }
                boolean flattened = wire.degraded().contains(ChatCanonicalizer.TOOL_HISTORY_FLATTENED);
                if (flattened) {
                    assertThat(settings.needsToolsForToolHistory()).as(where).isTrue();
                    assertThat(declaresTools).as(where).isFalse();
                }
                if (settings.needsToolsForToolHistory() && hasToolHistory && !declaresTools) {
                    flattenedSomewhere |= flattened;
                }
                if (!settings.needsToolsForToolHistory() && hasToolHistory && !declaresTools) {
                    keptSomewhere = true;
                    assertThat(flattened).as(where).isFalse();
                    assertThat(wire.body().toString()).as(where).contains("tool_calls");
                }
            }
        }
        assertThat(flattenedSomewhere).as("a sample is flattened for a member that needs it").isTrue();
        assertThat(keptSomewhere).as("a sample is kept for a member that does not").isTrue();
    }

    @Test
    void theSamplesCoverEveryFeatureTheTwoShare() {
        Set<Feature> seen = EnumSet.noneOf(Feature.class);
        for (Sample sample : samples()) {
            seen.addAll(RequestFeatureDetector.detect(sample.entry(), JSON.readTree(sample.body())).features());
        }

        assertThat(seen).containsAll(SHARED);
    }
}
