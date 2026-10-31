package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.FeaturePolicy.Baseline;
import tech.starryapi.platform.xproto.FeaturePolicy.Treatment;
import tech.starryapi.platform.xproto.FeaturePolicy.Verdict;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Design 7.9, GEMINI: what is found in a {@code generateContent} request and what a member makes of it. A feature is found
 * where the conversion ({@link GeminiChatConverter}) does not carry it, so the negative cases matter as much as the
 * positive ones: a request that only declares functions has nothing the policy refuses, and the main loop of Gemini CLI
 * (a dynamic thinking budget, the summaries of thoughts, a {@code topK}) has no HARD feature.
 */
class GeminiRequestFeaturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static Detected detect(String json) {
        return RequestFeatureDetector.detect(Entry.GEMINI, tree(json));
    }

    private static final String USER = "{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}";
    private static final String PNG = "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}";

    private static String body(String rest) {
        return "{\"contents\":[" + USER + "]" + (rest.isEmpty() ? "" : "," + rest) + "}";
    }

    private static String bodyOf(String... contents) {
        return "{\"contents\":[" + String.join(",", contents) + "]}";
    }

    private static String model(String... parts) {
        return "{\"role\":\"model\",\"parts\":[" + String.join(",", parts) + "]}";
    }

    private static String user(String... parts) {
        return "{\"role\":\"user\",\"parts\":[" + String.join(",", parts) + "]}";
    }

    private static final String CALL = "{\"functionCall\":{\"name\":\"f\",\"args\":{}}}";
    private static final String ANSWER = "{\"functionResponse\":{\"name\":\"f\",\"response\":{}}}";
    private static final String TOOLS = "\"tools\":[{\"functionDeclarations\":[{\"name\":\"f\"},{\"name\":\"g\"}]}]";

    // ---------------------------------------------------------------------------------------- Gemini CLI

    private static final String GEMINI_CLI_TURN = """
        {"systemInstruction":{"parts":[{"text":"You are an interactive CLI agent."}]},
         "contents":[%s],
         %s,
         "generationConfig":{"temperature":1,"topP":0.95,"topK":64,"thinkingConfig":{"includeThoughts":true,"thinkingBudget":-1}}}
        """;

    @Test
    void theMainLoopOfGeminiCliHasTheTopKAndNoHardFeature() {
        Detected detected = detect(GEMINI_CLI_TURN.formatted(String.join(",", USER,
            model("{\"text\":\"Reading.\",\"thought\":true}", "{\"functionCall\":{\"name\":\"f\",\"args\":{}},\"thoughtSignature\":\"SIG\"}"),
            user(ANSWER)), TOOLS));

        assertThat(detected.entry()).isEqualTo(Entry.GEMINI);
        assertThat(detected.features()).containsExactlyInAnyOrder(Feature.TOP_K, Feature.HISTORY_THINKING, Feature.REASONING_SUMMARY);
        assertThat(detected.toolContinuation()).isTrue();
        assertThat(detected.reasoningUnmapped()).isFalse();
        assertThat(detected.imageNotCarried()).isFalse();
        for (Baseline baseline : Baseline.values()) {
            assertThat(FeaturePolicy.judge(detected, baseline, member(false, false, false)).hard()).isEmpty();
        }
    }

    @Test
    void aPlainTextRequestHasNoFeature() {
        assertThat(detect(body("\"systemInstruction\":{\"parts\":[{\"text\":\"Be brief\"}]},\"generationConfig\":{\"temperature\":0.2,\"maxOutputTokens\":99,"
            + "\"stopSequences\":[\"a\",\"b\",\"c\",\"d\"],\"candidateCount\":1,\"seed\":3,\"presencePenalty\":0.1}")).features()).isEmpty();
    }

    @Test
    void theRequestIsNotChangedAndTheResultCannotBe() {
        String json = body(TOOLS + ",\"generationConfig\":{\"topK\":5}");
        JsonNode request = tree(json);

        Detected detected = RequestFeatureDetector.detect(Entry.GEMINI, request);

        assertThat(request).isEqualTo(tree(json));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> detected.features().add(Feature.WEB_SEARCH))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------------------------------- hard

    @Test
    void severalCandidatesAndMoreThanFourStopSequencesAreHard() {
        assertThat(detect(body("\"generationConfig\":{\"candidateCount\":2}")).features()).containsExactly(Feature.N_OVER_ONE);
        assertThat(detect(body("\"generationConfig\":{\"candidateCount\":1}")).features()).isEmpty();
        assertThat(detect(body("\"generationConfig\":{\"stopSequences\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"\"]}")).features())
            .containsExactly(Feature.STOP_SEQUENCES_OVER_4);
        assertThat(detect(body("\"generationConfig\":{\"stopSequences\":[\"a\",\"b\",\"c\",\"d\",\"\",\"\"]}")).features()).isEmpty();
    }

    @Test
    void theToolsGoogleRunsAreHardAndEachHasTheFeatureOfWhatItDoes() {
        assertThat(detect(body("\"tools\":[{\"googleSearch\":{}}]")).features()).containsExactly(Feature.WEB_SEARCH);
        assertThat(detect(body("\"tools\":[{\"googleSearchRetrieval\":{}}]")).features()).containsExactly(Feature.WEB_SEARCH);
        assertThat(detect(body("\"tools\":[{\"codeExecution\":{}}]")).features()).containsExactly(Feature.CODE_EXECUTION);
        assertThat(detect(body("\"tools\":[{\"urlContext\":{}}]")).features()).containsExactly(Feature.WEB_FETCH);
        assertThat(detect(body("\"tools\":[{\"computerUse\":{\"environment\":\"ENVIRONMENT_BROWSER\"}}]")).features()).containsExactly(Feature.COMPUTER_TOOL);
        assertThat(detect(body("\"tools\":[{\"googleMaps\":{}},{\"retrieval\":{}}]")).features()).containsExactly(Feature.NON_FUNCTION_TOOL);
        assertThat(detect(body("\"tools\":[{\"functionDeclarations\":[{\"name\":\"f\"}],\"googleSearch\":{}}]")).features()).containsExactly(Feature.WEB_SEARCH);
        // A tool that is switched off by a null, and a function list, are nothing.
        assertThat(detect(body("\"tools\":[{\"googleSearch\":null},{\"functionDeclarations\":[]},\"junk\"]")).features()).isEmpty();
    }

    @Test
    void theSubRequestOfGeminiCliThatSearchesTheWebCannotGoToARelay() {
        Detected detected = detect(body("\"tools\":[{\"googleSearch\":{}}],\"generationConfig\":{\"temperature\":0,\"topK\":1}"));

        for (Baseline baseline : Baseline.values()) {
            assertThat(FeaturePolicy.judge(detected, baseline, member(true, true, true)).hard()).containsExactly(Feature.WEB_SEARCH);
        }
    }

    @Test
    void contextThatLivesWithGoogleIsHard() {
        assertThat(detect(body("\"cachedContent\":\"cachedContents/abc\"")).features()).containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(detect(body("\"cachedContent\":\"\"")).features()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- left out

    @Test
    void whatAreOnlyLeftOutAreNamedAndNeverHard() {
        Detected detected = detect(body("""
            "safetySettings":[{"category":"HARM_CATEGORY_HARASSMENT","threshold":"BLOCK_NONE"}],
             "generationConfig":{"topK":3,"responseLogprobs":true,"responseModalities":["TEXT","IMAGE"]}"""));

        assertThat(detected.features()).containsExactlyInAnyOrder(Feature.SAFETY_SETTINGS, Feature.TOP_K, Feature.LOGPROBS, Feature.MODALITIES);
        for (Baseline baseline : Baseline.values()) {
            Verdict verdict = FeaturePolicy.judge(detected, baseline, member(true, true, true));
            assertThat(verdict.hard()).isEmpty();
            assertThat(verdict.soft()).containsExactlyInAnyOrder(Feature.SAFETY_SETTINGS, Feature.TOP_K, Feature.LOGPROBS, Feature.MODALITIES);
        }
    }

    @Test
    void textAsTheOnlyOutputAndNoLogprobsAreNothing() {
        assertThat(detect(body("\"generationConfig\":{\"responseModalities\":[\"TEXT\"],\"responseLogprobs\":false}")).features()).isEmpty();
        assertThat(detect(body("\"generationConfig\":{\"speechConfig\":{\"voiceConfig\":{}}}")).features()).containsExactly(Feature.MODALITIES);
        assertThat(detect(body("\"generationConfig\":{\"logprobs\":3}")).features()).containsExactly(Feature.LOGPROBS);
    }

    // ---------------------------------------------------------------------------------------- media

    @Test
    void anImageOfTheUserIsTheImageFeatureAndOneThatIsCarriedIsNotFlaggedAsLost() {
        Detected inline = detect(bodyOf(user("{\"text\":\"look\"}", PNG)));
        Detected byAddress = detect(bodyOf(user("{\"fileData\":{\"mimeType\":\"image/jpeg\",\"fileUri\":\"https://example.com/a.jpg\"}}")));

        assertThat(inline.features()).containsExactly(Feature.IMAGE);
        assertThat(inline.imageNotCarried()).isFalse();
        assertThat(byAddress.features()).containsExactly(Feature.IMAGE);
        assertThat(byAddress.imageNotCarried()).isFalse();
    }

    /** What the conversion tells the model of the media of an answer is that they were there; a PDF or a sound of it is left out like any other. */
    @Test
    void theAudioAndTheFilesOfAnAnswerAreFeaturesLikeThoseOfTheUserAndAreLeftOut() {
        String answer = "{\"functionResponse\":{\"name\":\"f\",\"response\":{},\"parts\":[%s]}}";
        Detected audio = detect(bodyOf(USER, user(answer.formatted("{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"AAAA\"}}"))));
        Detected pdf = detect(bodyOf(USER, user(answer.formatted("{\"fileData\":{\"mimeType\":\"application/pdf\",\"fileUri\":\"https://e.com/a.pdf\"}}"))));

        assertThat(audio.features()).containsExactly(Feature.AUDIO);
        assertThat(pdf.features()).containsExactly(Feature.FILE);
        assertThat(FeaturePolicy.judge(pdf, Baseline.TEXT_ONLY, member(true, true, true)).soft()).containsExactly(Feature.FILE);
    }

    /** What Google ran for the model in an earlier turn has no place in a relay's history; the conversion leaves it out, so the policy says so. */
    @Test
    void theHistoryOfACodeExecutionOrOfAToolOfTheApiIsLeftOutAndRecorded() {
        for (String part : List.of("{\"executableCode\":{\"language\":\"PYTHON\",\"code\":\"print(1)\"}}",
            "{\"codeExecutionResult\":{\"outcome\":\"OUTCOME_OK\",\"output\":\"1\"}}",
            "{\"toolCall\":{\"toolType\":\"GOOGLE_SEARCH_WEB\",\"args\":{}}}", "{\"toolResponse\":{\"toolType\":\"GOOGLE_SEARCH_WEB\"}}")) {
            Detected detected = detect(bodyOf(USER, model(part, "{\"text\":\"here\"}")));

            assertThat(detected.features()).as(part).containsExactly(Feature.SERVER_TOOL_HISTORY);
            for (Baseline baseline : Baseline.values()) {
                assertThat(FeaturePolicy.judge(detected, baseline, member(true, true, true)).soft()).as(part).containsExactly(Feature.SERVER_TOOL_HISTORY);
            }
        }
    }

    /** A token that continues what Google holds is as much a state of Google's as a cache: a relay would start the answer over. */
    @Test
    void aContinuationTokenIsContextThatGoogleHoldsAndNoRelayHas() {
        Detected detected = detect(body("\"continuationToken\":\"abc\""));

        assertThat(detected.features()).containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(FeaturePolicy.judge(detected, Baseline.TEXT_ONLY, member(true, true, true)).hard()).containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(detect(body("\"continuationToken\":\"\"")).features()).isEmpty();
    }

    @Test
    void anImageTheConversionHasNoPlaceForIsFlaggedSoThatNoMemberIsPromisedIt() {
        for (String content : List.of(
            user("{\"fileData\":{\"mimeType\":\"image/png\",\"fileUri\":\"https://generativelanguage.googleapis.com/v1beta/files/abc\"}}"),
            user("{\"fileData\":{\"mimeType\":\"image/png\",\"fileUri\":\"gs://bucket/a.png\"}}"),
            user("{\"inlineData\":{\"mimeType\":\"image/png\"}}"),
            model(PNG, "{\"text\":\"here\"}"),
            user("{\"functionResponse\":{\"name\":\"f\",\"response\":{},\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAAA\"}}]}}"))) {
            Detected detected = detect(bodyOf(USER, content));

            if (content.contains("functionResponse")) {
                assertThat(detected.features()).as(content).containsExactly(Feature.TOOL_OUTPUT_IMAGE);
            } else {
                assertThat(detected.features()).as(content).containsExactly(Feature.IMAGE);
                assertThat(detected.imageNotCarried()).as(content).isTrue();
                assertThat(FeaturePolicy.treatment(Feature.IMAGE, detected, Baseline.TEXT_ONLY, member(true, false, false))).as(content)
                    .isEqualTo(Treatment.SOFT);
            }
        }
    }

    @Test
    void audioAndOtherFilesAreNamedWhereverTheyAre() {
        assertThat(detect(bodyOf(user("{\"inlineData\":{\"mimeType\":\"audio/mp3\",\"data\":\"AAAA\"}}"))).features()).containsExactly(Feature.AUDIO);
        assertThat(detect(bodyOf(user("{\"inlineData\":{\"mimeType\":\"application/pdf\",\"data\":\"AAAA\"}}"))).features()).containsExactly(Feature.FILE);
        assertThat(detect(bodyOf(user("{\"fileData\":{\"mimeType\":\"video/mp4\",\"fileUri\":\"https://example.com/a.mp4\"}}"))).features())
            .containsExactly(Feature.FILE);
        assertThat(detect(bodyOf(USER, model("{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"AAAA\"}}"))).features()).containsExactly(Feature.AUDIO);
    }

    @Test
    void theSummariesOfThoughtsAreHistoryThinking() {
        assertThat(detect(bodyOf(USER, model("{\"text\":\"hm\",\"thought\":true}", "{\"text\":\"Answer\"}"))).features())
            .containsExactly(Feature.HISTORY_THINKING);
        assertThat(detect(bodyOf(USER, model("{\"text\":\"Answer\",\"thought\":false}"))).features()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- the format and the choice

    @Test
    void aResponseSchemaIsAJsonSchemaAndTheJsonModeAloneIsNothing() {
        assertThat(detect(body("\"generationConfig\":{\"responseMimeType\":\"application/json\"}")).features()).isEmpty();
        assertThat(detect(body("\"generationConfig\":{\"responseMimeType\":\"application/json\",\"responseSchema\":{\"type\":\"OBJECT\"}}")).features())
            .containsExactly(Feature.JSON_SCHEMA);
        assertThat(detect(body("\"generationConfig\":{\"responseMimeType\":\"application/json\",\"responseJsonSchema\":{\"type\":\"object\"}}")).features())
            .containsExactly(Feature.JSON_SCHEMA);
        // The current form of the API: responseFormat.text, which the two fields above are deprecated in favour of.
        assertThat(detect(body("\"generationConfig\":{\"responseFormat\":{\"text\":{\"mimeType\":\"APPLICATION_JSON\"}}}")).features()).isEmpty();
        assertThat(detect(body("\"generationConfig\":{\"responseFormat\":{\"text\":{\"mimeType\":\"APPLICATION_JSON\",\"schema\":{\"type\":\"object\"}}}}"))
            .features()).containsExactly(Feature.JSON_SCHEMA);
        // A schema with no mime to say what is asked for is not converted, and is nothing asked for.
        assertThat(detect(body("\"generationConfig\":{\"responseSchema\":{\"type\":\"OBJECT\"}}")).features()).isEmpty();
    }

    /** One of a set of values is no format a relay has a word for: the model answers in words, and the answer is recorded as made so. */
    @Test
    void anAnswerThatIsOneOfASetOfValuesIsLeftOutAndRecorded() {
        for (String config : List.of(
            "\"generationConfig\":{\"responseMimeType\":\"text/x.enum\",\"responseSchema\":{\"type\":\"STRING\"}}",
            "\"generationConfig\":{\"responseFormat\":{\"text\":{\"mimeType\":\"text/x.enum\"}}}")) {
            Detected detected = detect(body(config));

            assertThat(detected.features()).as(config).containsExactly(Feature.ENUM_ANSWER);
            for (Baseline baseline : Baseline.values()) {
                assertThat(FeaturePolicy.treatment(Feature.ENUM_ANSWER, detected, baseline, member(true, true, true)))
                    .as(config).isEqualTo(Treatment.SOFT);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"functionCallingConfig\":{\"mode\":\"AUTO\"}}",
        "{\"functionCallingConfig\":{\"mode\":\"NONE\"}}",
        "{\"functionCallingConfig\":{\"mode\":\"ANY\"}}",
        "{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"g\"]}}",
        // Every function there is, allowed: no restriction, which is the required of a relay.
        "{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"f\",\"g\"]}}",
        // The model decides between a call and words, which is the default of a relay; naming the functions it may call from all
        // of them restricts nothing.
        "{\"functionCallingConfig\":{\"mode\":\"VALIDATED\"}}",
        "{\"functionCallingConfig\":{\"mode\":\"VALIDATED\",\"allowedFunctionNames\":[\"g\",\"f\"]}}",
        "{}"})
    void aChoiceARelayHasAWordForIsNoFeature(String toolConfig) {
        assertThat(detect(body(TOOLS + ",\"toolConfig\":" + toolConfig)).features()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"nobody\"]}}",
        // VALIDATED does not force a call, so it is not a forced function either; the names it limits the calls to are a restriction.
        "{\"functionCallingConfig\":{\"mode\":\"VALIDATED\",\"allowedFunctionNames\":[\"f\",\"f\"]}}",
        "{\"functionCallingConfig\":{\"mode\":\"VALIDATED\",\"allowedFunctionNames\":[\"g\"]}}"})
    void aChoiceAmongFunctionsOrOfOneNobodyDeclaredIsTheToolChoiceFeature(String toolConfig) {
        Detected detected = detect(body(TOOLS + ",\"toolConfig\":" + toolConfig));

        assertThat(detected.features()).containsExactly(Feature.TOOL_CHOICE);
        // A relay has no word for it and a route that reads text only would not have honored it either: it is left out, not refused.
        for (Baseline baseline : Baseline.values()) {
            assertThat(FeaturePolicy.treatment(Feature.TOOL_CHOICE, detected, baseline, member(true, true, true))).isEqualTo(Treatment.SOFT);
        }
    }

    @Test
    void aChoiceAmongSomeOfThreeFunctionsIsTheToolChoiceFeatureAndAmongAllOfThemIsNot() {
        String three = "\"tools\":[{\"functionDeclarations\":[{\"name\":\"f\"},{\"name\":\"g\"},{\"name\":\"h\"}]}]";

        assertThat(detect(body(three + ",\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"f\",\"g\"]}}"))
            .features()).containsExactly(Feature.TOOL_CHOICE);
        assertThat(detect(body(three + ",\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"h\",\"f\",\"g\"]}}"))
            .features()).isEmpty();
    }

    @Test
    void theChoiceIsReadOnlyInARequestThatDeclaresAFunction() {
        String choice = "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"f\",\"g\"]}}";

        assertThat(detect(body(choice)).features()).isEmpty();
        assertThat(detect(body("\"tools\":[{\"googleSearch\":{}}]," + choice)).features()).containsExactly(Feature.WEB_SEARCH);
    }

    // ---------------------------------------------------------------------------------------- reasoning and the end of the history

    @Test
    void aThinkingBudgetOrLevelIsTheThinkingFeatureAndADynamicOrNoneIsNothing() {
        assertThat(detect(body("\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":2048}}")).features()).containsExactly(Feature.THINKING);
        assertThat(detect(body("\"generationConfig\":{\"thinkingConfig\":{\"thinkingLevel\":\"HIGH\"}}")).features()).containsExactly(Feature.THINKING);
        assertThat(detect(body("\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":-1}}")).features()).isEmpty();
        assertThat(detect(body("\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":0}}")).features()).isEmpty();
        assertThat(detect(body("\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":false}}")).features()).isEmpty();
    }

    /**
     * A client that asks for the summaries of the model's thoughts (Gemini CLI does, with every request) is not given them by a
     * relay, which returns its reasoning as it has it: the request log says so, and the relay serves the request.
     */
    @Test
    void theSummariesOfThoughtsAreAskedForWhereverTheThinkingConfigIsAndAreOnlyLeftOut() {
        assertThat(detect(body("\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true}}")).features())
            .containsExactly(Feature.REASONING_SUMMARY);
        assertThat(detect(body("\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":2048}")).features())
            .containsExactlyInAnyOrder(Feature.REASONING_SUMMARY, Feature.THINKING);
        for (Baseline baseline : Baseline.values()) {
            FeaturePolicy.Verdict verdict = FeaturePolicy.judge(
                detect(body("\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true}}")), baseline, member(false, false, true));
            assertThat(verdict.hard()).isEmpty();
            assertThat(verdict.soft()).containsExactly(Feature.REASONING_SUMMARY);
        }
    }

    @Test
    void aServiceTierOtherThanTheDefaultIsLeftOutAndNamed() {
        assertThat(detect(body("\"serviceTier\":\"priority\"")).features()).containsExactly(Feature.SERVICE_TIER);
        assertThat(detect(body("\"serviceTier\":\"flex\"")).features()).containsExactly(Feature.SERVICE_TIER);
        assertThat(detect(body("\"serviceTier\":\"standard\"")).features()).isEmpty();
        assertThat(detect(body("\"serviceTier\":\"\"")).features()).isEmpty();
        assertThat(detect(body("\"serviceTier\":null")).features()).isEmpty();
        for (Baseline baseline : Baseline.values()) {
            assertThat(FeaturePolicy.judge(detect(body("\"serviceTier\":\"priority\"")), baseline, member(false, false, false)).soft())
                .containsExactly(Feature.SERVICE_TIER);
        }
    }

    /**
     * The names of the functions are the client's: MCP servers add theirs with dots and colons, which Gemini takes and the
     * upstreams of the other vendors do not. Only a name the converter would send is looked at: a function that is declared, and
     * a call of the model's earlier turns.
     */
    @Test
    void aFunctionNameOnlyGeminiTakesIsAFeatureWhereverTheConversionSendsIt() {
        String dotted = "\"tools\":[{\"functionDeclarations\":[{\"name\":\"server.tool:run\"}]}]";
        assertThat(detect(body(dotted)).features()).containsExactly(Feature.FUNCTION_NAME);
        assertThat(detect(body("\"tools\":[{\"functionDeclarations\":[{\"name\":\"" + "n".repeat(65) + "\"}]}]")).features())
            .containsExactly(Feature.FUNCTION_NAME);
        assertThat(detect(body("\"tools\":[{\"functionDeclarations\":[{\"name\":\"read_file-2\"}]}]")).features()).isEmpty();
        assertThat(detect(bodyOf(USER, model("{\"functionCall\":{\"name\":\"ns.call\",\"args\":{}}}"), user(ANSWER))).features())
            .containsExactly(Feature.FUNCTION_NAME);
        // The call of a turn of the user is not one the conversion sends, and a declaration without a name is left out by it.
        assertThat(detect(bodyOf(user("{\"functionCall\":{\"name\":\"ns.call\",\"args\":{}}}"))).features()).isEmpty();
        assertThat(detect(body("\"tools\":[{\"functionDeclarations\":[{\"description\":\"x\"}]}]")).features()).isEmpty();
        for (Baseline baseline : Baseline.values()) {
            assertThat(FeaturePolicy.judge(detect(body(dotted)), baseline, member(false, false, false)).hard())
                .containsExactly(Feature.FUNCTION_NAME);
        }
    }

    @Test
    void aTurnIsAToolContinuationWhenTheLastWordIsTheAnswerOfAFunction() {
        assertThat(detect(bodyOf(USER, model(CALL), user(ANSWER))).toolContinuation()).isTrue();
        assertThat(detect(bodyOf(USER, model(CALL), user(ANSWER, "{\"text\":\"and?\"}"))).toolContinuation()).isTrue();
        assertThat(detect(bodyOf(USER, model(CALL), user(ANSWER), user("{\"text\":\"more\"}"))).toolContinuation()).isTrue();
        assertThat(detect(bodyOf(USER, model(CALL), user(ANSWER), model("{\"text\":\"done\"}"), user("{\"text\":\"thanks\"}"))).toolContinuation()).isFalse();
        assertThat(detect(bodyOf(USER, model(CALL))).toolContinuation()).isFalse();
        assertThat(detect(bodyOf(USER)).toolContinuation()).isFalse();
        assertThat(detect("{\"contents\":\"text\"}").toolContinuation()).isFalse();
    }

    @Test
    void aClaudeModelIsNotAskedForALevelOnAContinuationAndAGeminiOneIs() {
        Detected continuation = detect("{\"contents\":[" + String.join(",", USER, model(CALL), user(ANSWER)) + "],"
            + "\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":2048}}}");
        Settings claude = new Settings(false, false, false, false, false, true, false, false, true, true, false);
        Settings gemini = new Settings(false, false, false, false, false, true, false, false, false, true, true);

        assertThat(continuation.has(Feature.THINKING)).isTrue();
        assertThat(FeaturePolicy.treatment(Feature.THINKING, continuation, Baseline.TEXT_ONLY, claude)).isEqualTo(Treatment.SOFT);
        assertThat(FeaturePolicy.treatment(Feature.THINKING, continuation, Baseline.TEXT_ONLY, gemini)).isEqualTo(Treatment.CARRY);
    }

    // ---------------------------------------------------------------------------------------- the policy

    private static Settings member(boolean vision, boolean jsonSchema, boolean reasoningEffort) {
        return new Settings(vision, jsonSchema, false, false, false, reasoningEffort, false, false, false, false, false);
    }

    private static final Set<Feature> GEMINI_CARRIES = EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.THINKING);

    @Test
    void whatTheMemberCanCarryIsCarriedAndTheRestIsLeftOutOnEveryRouteWithoutBeingMadeHard() {
        Detected detected = new Detected(Entry.GEMINI, EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.THINKING, Feature.AUDIO, Feature.FILE,
            Feature.TOOL_CHOICE, Feature.TOOL_OUTPUT_IMAGE, Feature.SAFETY_SETTINGS), false, false);

        for (Baseline baseline : Baseline.values()) {
            Verdict plain = FeaturePolicy.judge(detected, baseline, member(false, false, false));
            Verdict able = FeaturePolicy.judge(detected, baseline, member(true, true, true));

            assertThat(plain.hard()).isEmpty();
            assertThat(plain.soft()).containsExactlyInAnyOrder(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.THINKING, Feature.AUDIO, Feature.FILE,
                Feature.TOOL_CHOICE, Feature.TOOL_OUTPUT_IMAGE, Feature.SAFETY_SETTINGS);
            assertThat(able.hard()).isEmpty();
            assertThat(able.soft()).containsExactlyInAnyOrder(Feature.AUDIO, Feature.FILE, Feature.TOOL_CHOICE, Feature.TOOL_OUTPUT_IMAGE,
                Feature.SAFETY_SETTINGS);
        }
    }

    @Test
    void anImageIsNotCarriedByAMemberThatSeesWhenTheConversionHasNoPlaceForIt() {
        Detected lost = new Detected(Entry.GEMINI, EnumSet.of(Feature.IMAGE), false, false, true);

        assertThat(FeaturePolicy.treatment(Feature.IMAGE, lost, Baseline.TEXT_ONLY, member(true, true, true))).isEqualTo(Treatment.SOFT);
    }

    @Test
    void theFeaturesNoRelayCanDoForTheAnswerAreHardForEveryMemberAndRoute() {
        Detected detected = new Detected(Entry.GEMINI, EnumSet.noneOf(Feature.class), false, false);
        for (Feature feature : EnumSet.of(Feature.N_OVER_ONE, Feature.STOP_SEQUENCES_OVER_4, Feature.WEB_SEARCH, Feature.CODE_EXECUTION,
            Feature.WEB_FETCH, Feature.COMPUTER_TOOL, Feature.NON_FUNCTION_TOOL, Feature.SERVER_HELD_CONTEXT)) {
            for (Baseline baseline : Baseline.values()) {
                for (boolean able : new boolean[] {false, true}) {
                    assertThat(FeaturePolicy.treatment(feature, detected, baseline, member(able, able, able))).as("%s", feature).isEqualTo(Treatment.HARD);
                }
            }
        }
    }

    @Test
    void geminiCarriesExactlyWhatItsConverterHas() {
        Detected detected = new Detected(Entry.GEMINI, EnumSet.noneOf(Feature.class), false, false);
        Settings full = new Settings(true, true, true, true, true, true, false, false, false, false, false);

        for (Feature feature : Feature.values()) {
            if (feature == Feature.FAST) {
                continue;
            }
            assertThat(FeaturePolicy.treatment(feature, detected, Baseline.TEXT_ONLY, full) == Treatment.CARRY)
                .as("%s", feature).isEqualTo(GEMINI_CARRIES.contains(feature));
        }
    }
}
