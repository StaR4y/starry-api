package tech.starryapi.sourcetier;

import io.r2dbc.spi.Readable;
import tech.starryapi.account.ProxyAccountEntity;

/**
 * The columns of an account that the tier rules read: provider, auth method, base URL and whether it is enabled. The
 * credential columns of every account stay out of memory, which is why the tier pages do not use
 * ProxyAccountRepository, and why the entities built here are only good for AccountPlatforms, PoolCell,
 * AccountAllocationCategory and the mapping fingerprint.
 */
final class SourceTierAccounts {

    static final String SELECT = "SELECT account_id, provider, auth_method, base_url, enabled FROM proxy_accounts";

    private SourceTierAccounts() {
    }

    static ProxyAccountEntity read(Readable row) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(row.get("account_id", String.class));
        account.setProvider(row.get("provider", String.class));
        account.setAuthMethod(row.get("auth_method", String.class));
        account.setBaseUrl(row.get("base_url", String.class));
        account.setEnabled(row.get("enabled", Boolean.class));
        return account;
    }
}
