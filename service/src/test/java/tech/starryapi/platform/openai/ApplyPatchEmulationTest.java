package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The editing tool of Codex as a function (design 7.6): the declaration in the Responses and the Chat shape, the choice that
 * forces it, the arguments of a call of it, and what the model's call is turned into. {@code
 * OpenAiUpstreamClientApplyPatchTest} pins what the native client sends and parses with these rules; here are the rules.
 */
class ApplyPatchEmulationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ApplyPatchEmulation EMULATION = new ApplyPatchEmulation(JSON);

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static final String DEFAULT_DESCRIPTION = "Apply a raw patch. The patch string must start with *** Begin Patch and end with *** End Patch.";
    private static final String PARAMETERS = "{\"type\":\"object\",\"properties\":{\"patch\":{\"type\":\"string\","
        + "\"description\":\"Raw apply_patch payload, including the *** Begin Patch and *** End Patch lines.\"}},\"required\":[\"patch\"]}";

    // ---------------------------------------------------------------------------------------- the declaration

    @Test
    void theToolIsToldByItsNameAlone() {
        assertThat(EMULATION.isApplyPatchTool(tree("{\"type\":\"custom\",\"name\":\"apply_patch\"}"))).isTrue();
        assertThat(EMULATION.isApplyPatchTool(tree("{\"type\":\"function\",\"name\":\"apply_patch\"}"))).isTrue();
        assertThat(EMULATION.isApplyPatchTool(tree("{\"name\":\"apply_patch\"}"))).isTrue();
        assertThat(EMULATION.isApplyPatchTool(tree("{\"type\":\"custom\",\"name\":\"Apply_Patch\"}"))).isFalse();
        assertThat(EMULATION.isApplyPatchTool(tree("{\"type\":\"custom\"}"))).isFalse();
        assertThat(EMULATION.isApplyPatchTool(tree("{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}"))).isFalse();
    }

    @Test
    void theFunctionOfTheResponsesShapeHasTheClientsDescriptionAndStrictness() {
        assertThat(EMULATION.functionTool(tree("{\"type\":\"custom\",\"name\":\"apply_patch\",\"description\":\"Edit files.\","
            + "\"format\":{\"type\":\"grammar\"}}")))
            .isEqualTo(tree("{\"type\":\"function\",\"name\":\"apply_patch\",\"description\":\"Edit files.\",\"parameters\":" + PARAMETERS + "}"));
        assertThat(EMULATION.functionTool(tree("{\"type\":\"function\",\"name\":\"apply_patch\",\"strict\":true}")))
            .isEqualTo(tree("{\"type\":\"function\",\"name\":\"apply_patch\",\"description\":\"" + DEFAULT_DESCRIPTION + "\",\"parameters\":" + PARAMETERS
                + ",\"strict\":true}"));
        assertThat(EMULATION.functionTool(tree("{\"name\":\"apply_patch\",\"description\":\"  \"}")).path("description").stringValue())
            .isEqualTo(DEFAULT_DESCRIPTION);
        assertThat(EMULATION.functionTool(tree("{\"name\":\"apply_patch\"}")).propertyNames()).containsExactly("type", "name", "description", "parameters");
    }

    @Test
    void theFunctionOfTheChatShapeIsTheSameFunctionOneLevelDownWithADescriptionThatSaysWhereThePatchGoes() {
        JsonNode source = tree("{\"type\":\"custom\",\"name\":\"apply_patch\",\"description\":\"Edit files.\",\"strict\":false}");

        JsonNode chat = EMULATION.chatFunctionTool(source);

        assertThat(chat).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\",\"description\":"
            + JSON.valueToTree(ApplyPatchEmulation.CHAT_DESCRIPTION) + ",\"parameters\":" + PARAMETERS + ",\"strict\":false}}"));
        assertThat(chat.propertyNames()).containsExactly("type", "function");
        assertThat(chat.path("function").propertyNames()).containsExactly("name", "description", "parameters", "strict");
        // The accounts that speak Responses keep the client's own words, as they always did.
        assertThat(EMULATION.functionTool(source).path("description").stringValue()).isEqualTo("Edit files.");
    }

    /**
     * Codex's own words for the custom tool say that it takes no JSON, which is the opposite of what a relay is asked for: a model
     * that believed it would write the bare patch where the arguments are read as JSON.
     */
    @Test
    void aRelayIsNotToldThatTheEditingToolTakesNoJson() {
        JsonNode freeform = tree("{\"type\":\"custom\",\"name\":\"apply_patch\",\"description\":\"Use the `apply_patch` tool to edit files. "
            + "This is a FREEFORM tool, so do not wrap the patch in JSON.\",\"format\":{\"type\":\"grammar\"}}");

        String relay = EMULATION.chatFunctionTool(freeform).path("function").path("description").stringValue();
        String accountOfResponses = EMULATION.functionTool(freeform).path("description").stringValue();

        assertThat(relay).doesNotContain("FREEFORM", "do not wrap").contains("`patch` argument", "*** Begin Patch", "*** End Patch");
        assertThat(accountOfResponses).contains("do not wrap the patch in JSON");
        // And the same words when the client wrote none.
        assertThat(EMULATION.chatFunctionTool(tree("{\"type\":\"custom\",\"name\":\"apply_patch\"}")).path("function").path("description").stringValue())
            .isEqualTo(relay);
    }

    @Test
    void aToolIsTheFunctionWhenItIsTheEditingToolInAnyOfItsShapesAndItselfOtherwise() {
        JsonNode custom = tree("{\"type\":\"custom\",\"name\":\"apply_patch\"}");
        JsonNode nested = tree("{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\",\"description\":\"nested\"}}");
        JsonNode other = tree("{\"type\":\"function\",\"name\":\"exec_command\"}");
        JsonNode search = tree("{\"type\":\"web_search\"}");

        assertThat(EMULATION.customToolAsFunction(custom)).isEqualTo(EMULATION.functionTool(custom));
        assertThat(EMULATION.customToolAsFunction(nested).path("description").stringValue()).isEqualTo("nested");
        assertThat(EMULATION.customToolAsFunction(nested).path("name").stringValue()).isEqualTo("apply_patch");
        assertThat(EMULATION.customToolAsFunction(other)).isSameAs(other);
        assertThat(EMULATION.customToolAsFunction(search)).isSameAs(search);
    }

    // ---------------------------------------------------------------------------------------- the choice

    @Test
    void theChoiceThatForcesTheEditingToolIsAFunctionChoiceInTheShapeOfTheApi() {
        for (String choice : new String[] {
            "{\"type\":\"custom\",\"name\":\"apply_patch\"}",
            "{\"type\":\"function\",\"name\":\"apply_patch\"}",
            "{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}",
            "{\"name\":\"apply_patch\"}"}) {
            assertThat(EMULATION.toolChoice(tree(choice))).isEqualTo(tree("{\"type\":\"function\",\"name\":\"apply_patch\"}"));
            assertThat(EMULATION.chatToolChoice(tree(choice))).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}"));
        }
    }

    @Test
    void anyOtherChoiceIsTheSameInstance() {
        for (String choice : new String[] {
            "\"auto\"", "\"required\"", "null", "7",
            "{\"type\":\"function\",\"name\":\"exec_command\"}",
            "{\"type\":\"function\",\"function\":{\"name\":\"exec_command\"}}",
            "{\"type\":\"allowed_tools\",\"tools\":[]}",
            "{\"type\":\"custom\",\"name\":\"other\"}",
            "{}"}) {
            JsonNode node = tree(choice);

            assertThat(EMULATION.toolChoice(node)).isSameAs(node);
            assertThat(EMULATION.chatToolChoice(node)).isSameAs(node);
        }
    }

    @Test
    void theNameOfAChoiceIsTheNameOfItsFunctionAndElseItsOwn() {
        assertThat(ApplyPatchEmulation.toolChoiceName(tree("{\"type\":\"function\",\"function\":{\"name\":\"a\"},\"name\":\"b\"}"))).isEqualTo("a");
        assertThat(ApplyPatchEmulation.toolChoiceName(tree("{\"type\":\"function\",\"name\":\"b\"}"))).isEqualTo("b");
        assertThat(ApplyPatchEmulation.toolChoiceName(tree("{\"type\":\"function\",\"function\":{\"name\":\" \"},\"name\":\"b\"}"))).isEqualTo("b");
        assertThat(ApplyPatchEmulation.toolChoiceName(tree("{\"type\":\"function\"}"))).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- a call of the function

    @Test
    void theArgumentsOfACallAreTheJsonObjectWithThePatchWhateverIsInIt() {
        String patch = "*** Begin Patch\n*** Add File: \"é\\x\".txt\n+tab\there\n*** End Patch\n";

        String arguments = EMULATION.patchArguments(patch);

        assertThat(arguments).startsWith("{\"patch\":\"").endsWith("\"}");
        assertThat(tree(arguments).propertyNames()).containsExactly("patch");
        assertThat(tree(arguments).path("patch").stringValue()).isEqualTo(patch);
        assertThat(arguments).doesNotContain("\n");
        assertThat(EMULATION.patchArguments("")).isEqualTo("{\"patch\":\"\"}");
        assertThat(EMULATION.patchArguments(null)).isEqualTo("{\"patch\":\"\"}");
    }

    // ---------------------------------------------------------------------------------------- what the model called

    @Test
    void aFunctionCallOfTheEditingToolIsACustomCallAndNothingElseChanges() {
        assertThat(EMULATION.toolUseType("function_call", "apply_patch")).isEqualTo("custom_tool_call");
        assertThat(EMULATION.toolUseType("custom_tool_call", "apply_patch")).isEqualTo("custom_tool_call");
        assertThat(EMULATION.toolUseType("function_call", "exec_command")).isEqualTo("function_call");
        assertThat(EMULATION.toolUseType("custom_tool_call", "exec_command")).isEqualTo("custom_tool_call");
        assertThat(EMULATION.toolUseType("mcp_tool_call", "apply_patch")).isEqualTo("mcp_tool_call");
        assertThat(EMULATION.toolUseType("function_call", null)).isEqualTo("function_call");
        assertThat(ApplyPatchEmulation.isCustomApplyPatchCall("custom_tool_call", "apply_patch")).isTrue();
        assertThat(ApplyPatchEmulation.isCustomApplyPatchCall("function_call", "apply_patch")).isFalse();
        assertThat(ApplyPatchEmulation.isCustomApplyPatchCall("custom_tool_call", "other")).isFalse();
    }

    @Test
    void theInputOfACustomCallIsThePatchWhereverTheModelPutIt() {
        String patch = "*** Begin Patch\n*** Add File: a.txt\n+hello\n*** End Patch\n";
        String type = "custom_tool_call";

        for (String arguments : new String[] {
            "{\"patch\":" + JSON.valueToTree(patch) + "}",
            "{\"input\":" + JSON.valueToTree(patch) + "}",
            "{\"text\":" + JSON.valueToTree(patch) + "}",
            JSON.valueToTree(patch).toString(),
            patch}) {
            assertThat(EMULATION.toolUseRawInput(type, "apply_patch", arguments)).as(arguments).isEqualTo(patch);
        }
    }

    @Test
    void theInputIsCutAtTheEndOfThePatchAndHasLinesFeedsAndNoWordsBeforeIt() {
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "words\r\n*** Begin Patch\r\n+a\r\n*** End Patch\r\nand after"))
            .isEqualTo("*** Begin Patch\n+a\n*** End Patch\n");
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "*** Begin Patch\n+a")).isEqualTo("*** Begin Patch\n+a");
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "*** Begin Patch\n*** End Patch\n*** End Patch\ntail"))
            .isEqualTo("*** Begin Patch\n*** End Patch\n*** End Patch\n");
    }

    @Test
    void whatIsNotAPatchIsKeptAsItCameAndWhatIsEmptyIsEmpty() {
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", null)).isEmpty();
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "  ")).isEmpty();
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "{}")).isEmpty();
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "{\"patch\":\"\"}")).isEqualTo("{\"patch\":\"\"}");
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "{\"other\":1}")).isEqualTo("{\"other\":1}");
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "apply_patch", "not json {")).isEqualTo("not json {");
    }

    @Test
    void theArgumentsOfAnyOtherCallAreNotRead() {
        String arguments = "{\"patch\":\"*** Begin Patch\\n*** End Patch\"} trailing";

        assertThat(EMULATION.toolUseRawInput("function_call", "apply_patch", arguments)).isSameAs(arguments);
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "freeform", arguments)).isSameAs(arguments);
        assertThat(EMULATION.toolUseRawInput("custom_tool_call", "freeform", null)).isNull();
    }

    @Test
    void aTextEndsAPatchWhenItsLastLineIsTheEndLine() {
        assertThat(ApplyPatchEmulation.looksLikeCompletePatch("*** Begin Patch\n+a\n*** End Patch")).isTrue();
        assertThat(ApplyPatchEmulation.looksLikeCompletePatch("*** Begin Patch\r\n+a\r\n*** End Patch\r\n\r\n")).isTrue();
        assertThat(ApplyPatchEmulation.looksLikeCompletePatch("*** Begin Patch\n+a")).isFalse();
        assertThat(ApplyPatchEmulation.looksLikeCompletePatch("*** End Patch\nmore")).isFalse();
        assertThat(ApplyPatchEmulation.looksLikeCompletePatch(" ")).isFalse();
        assertThat(ApplyPatchEmulation.looksLikeCompletePatch(null)).isFalse();
    }
}
