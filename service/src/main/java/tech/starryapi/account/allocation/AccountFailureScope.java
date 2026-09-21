package tech.starryapi.account.allocation;

public enum AccountFailureScope {
    REQUEST,
    CREDENTIAL,
    CAPACITY_ACCOUNT,
    CAPACITY_MODEL,
    CAPACITY_ENDPOINT,
    TRANSPORT,
    PROXY_EDGE,
    REGIONAL_POLICY,
    PROVIDER_POOL,
    UNKNOWN
}
