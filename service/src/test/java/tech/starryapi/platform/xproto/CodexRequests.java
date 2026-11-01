package tech.starryapi.platform.xproto;

/**
 * The requests of Codex 0.142.5 that the Responses entry's tests are made from, as captured against a stand-in for the
 * gateway (never a real one), cut down to what the tests read: the long texts and every identifier are placeholders.
 */
public final class CodexRequests {

    private CodexRequests() {
    }

    /**
     * What Codex 0.142.5 sends for a model it has no metadata for (a Claude model), as captured against a stand-in for the
     * gateway: the functions of its own, a namespace of functions of the sub-agents, the cached web search that it declares
     * whatever the model, no reasoning, nothing to include. The long texts and the identifiers are placeholders.
     */
    public static final String CLAUDE_TURN = """
        {"model":"claude-sonnet-4.5","instructions":"You are Codex.",
         "input":[{"type":"message","role":"developer","content":[{"type":"input_text","text":"<permissions instructions>"}]},
                  {"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context>"}]},
                  {"type":"message","role":"user","content":[{"type":"input_text","text":"run echo hello"}]}],
         "tools":[{"type":"function","name":"exec_command","description":"Runs a command","strict":false,
                   "parameters":{"type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"]}},
                  {"type":"function","name":"update_plan","description":"Plan","strict":false,"parameters":{"type":"object","properties":{}}},
                  {"type":"namespace","name":"multi_agent_v1","description":"Sub-agents",
                   "tools":[{"type":"function","name":"spawn_agent","description":"Spawns","parameters":{"type":"object","properties":{}}}]},
                  {"type":"web_search","external_web_access":false}],
         "tool_choice":"auto","parallel_tool_calls":false,"reasoning":null,"store":false,"stream":true,"include":[],
         "prompt_cache_key":"00000000-0000-0000-0000-000000000000",
         "client_metadata":{"session_id":"00000000-0000-0000-0000-000000000000","x-codex-installation-id":"00000000-0000-0000-0000-000000000001"}}
        """;

    /** What it sends for a model it knows (gpt-5.5): the editing tool as a custom tool, a tool search, reasoning and the encrypted reasoning back. */
    public static final String GPT_TURN = """
        {"model":"gpt-5.5","instructions":"You are Codex.",
         "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"say ok"}]}],
         "tools":[{"type":"function","name":"exec_command","description":"Runs a command","strict":false,
                   "parameters":{"type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"]}},
                  {"type":"custom","name":"apply_patch","description":"Edits files","format":{"type":"grammar","syntax":"lark","definition":"start: x"}},
                  {"type":"tool_search","execution":"client","description":"Search","parameters":{"type":"object","properties":{"query":{"type":"string"}}}},
                  {"type":"web_search","external_web_access":false,"search_content_types":["text","image"]}],
         "tool_choice":"auto","parallel_tool_calls":true,"reasoning":{"effort":"medium"},"store":false,"stream":true,
         "include":["reasoning.encrypted_content"],"prompt_cache_key":"00000000-0000-0000-0000-000000000000","text":{"verbosity":"low"},
         "client_metadata":{"session_id":"00000000-0000-0000-0000-000000000000"}}
        """;
}
