package tech.starryapi.proxy.tier;

/**
 * Which kind of upstream a request is routed to before tiers come into it. It mirrors the facade's own route, which
 * stays private to it; the planner needs the value to know which account kinds the route would have picked from.
 */
public enum TierRoute {
    KIRO,
    OPENAI,
    ANTHROPIC
}
