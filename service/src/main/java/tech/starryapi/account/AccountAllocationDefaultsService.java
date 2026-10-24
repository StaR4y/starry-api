package tech.starryapi.account;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AllocationDefaultsDtos.CategoryDefaults;
import tech.starryapi.account.AllocationDefaultsDtos.Defaults;
import tech.starryapi.account.AllocationDefaultsDtos.Policy;
import tech.starryapi.account.AllocationDefaultsDtos.ResolveRequest;
import tech.starryapi.account.AllocationDefaultsDtos.ResolveResponse;
import tech.starryapi.account.AllocationDefaultsDtos.UpdateDefaults;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.ObjectMapper;

/**
 * Administrator-managed routing limits for new accounts, one JSON system setting per category. The account-creation
 * trigger reads the same settings (starry_api.account_allocation_defaults) and falls back to the built-in values for
 * a category whose setting is missing or invalid, so what is stored here is exactly what new accounts start with.
 * Existing accounts are never changed.
 */
@Service
public class AccountAllocationDefaultsService {

    private static final Set<String> TLS_PROFILES = Set.of("", "chrome136", "safari18_4");
    private static final String LOCK = "starry_api:system_settings:account_allocation_defaults";

    private final SystemSettingRepository repository;
    private final ObjectMapper mapper;

    public AccountAllocationDefaultsService(SystemSettingRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public Mono<Defaults> get() {
        return configured().map(AccountAllocationDefaultsService::defaults);
    }

    @Transactional
    public Mono<Defaults> update(UpdateDefaults request) {
        Map<AccountAllocationCategory, Policy> requested = parse(request);
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (AccountAllocationCategory category : AccountAllocationCategory.values()) {
            Policy policy = requested.get(category);
            keys.add(category.settingKey());
            // The runtime role cannot delete settings: an empty value is how a category goes back to its built-in values.
            values.add(policy == null ? "" : json(policy));
        }
        return repository.acquireTransactionLock(LOCK)
            .then(Mono.defer(() -> repository.upsertSettings(keys.toArray(String[]::new), values.toArray(String[]::new))))
            .then(Mono.defer(this::get));
    }

    /** Predicts the category and starting policy of a new account the way it will be stored. */
    public Mono<ResolveResponse> resolve(ResolveRequest request) {
        String authMethod = AccountPlatforms.normalizeAuthMethod(request.authMethod(), request.provider());
        String provider = AccountPlatforms.normalizeProvider(request.provider(), authMethod);
        String baseUrl = AccountPlatforms.defaultBaseUrl(provider, authMethod, request.baseUrl());
        AccountAllocationCategory category = AccountAllocationCategory.classify(provider, authMethod, baseUrl);
        return configured().map(configured -> {
            Policy policy = configured.getOrDefault(category, category.builtIn());
            // Session limits and TLS profiles are stored for Anthropic accounts only.
            if (!AccountAllocationCategory.anthropic(provider)) {
                policy = new Policy(policy.safeConcurrency(), policy.safeRpm(), policy.burstCapacity(), 0,
                    policy.sessionIdleSeconds(), policy.cooldownSeconds(), policy.maxInflightTokens(),
                    policy.allocationPriority(), "");
            }
            return new ResolveResponse(category.name(), policy);
        });
    }

    /** The valid configured policies; an empty, unparsable or out-of-range setting counts as unset, as it does in the trigger. */
    private Mono<Map<AccountAllocationCategory, Policy>> configured() {
        Map<String, AccountAllocationCategory> byKey = new java.util.HashMap<>();
        for (AccountAllocationCategory category : AccountAllocationCategory.values()) byKey.put(category.settingKey(), category);
        return repository.findAllById(byKey.keySet()).collectList().map(rows -> {
            Map<AccountAllocationCategory, Policy> result = new EnumMap<>(AccountAllocationCategory.class);
            for (SystemSettingEntity row : rows) {
                Policy policy = read(row.getSettingValue());
                if (policy != null && problem(policy) == null) result.put(byKey.get(row.getSettingKey()), policy);
            }
            return result;
        });
    }

    private static Defaults defaults(Map<AccountAllocationCategory, Policy> configured) {
        List<CategoryDefaults> categories = new ArrayList<>();
        for (AccountAllocationCategory category : AccountAllocationCategory.values()) {
            Policy policy = configured.get(category);
            categories.add(new CategoryDefaults(category.name(), category.anthropicSettings(), category.builtIn(),
                policy, policy == null ? category.builtIn() : policy));
        }
        return new Defaults(categories);
    }

    private Map<AccountAllocationCategory, Policy> parse(UpdateDefaults request) {
        Map<AccountAllocationCategory, Policy> result = new EnumMap<>(AccountAllocationCategory.class);
        if (request == null || request.categories() == null) return result;
        request.categories().forEach((name, policy) -> {
            AccountAllocationCategory category;
            try { category = AccountAllocationCategory.valueOf(name); }
            catch (IllegalArgumentException unknown) { throw invalid("Unknown account category: " + name); }
            if (policy == null) return;
            String problem = problem(policy);
            if (problem == null && !category.anthropicSettings() && (policy.maxSessions() != 0 || !policy.tlsProfile().isEmpty())) {
                problem = "maxSessions and tlsProfile only apply to Anthropic accounts, which " + name + " never contains";
            }
            if (problem != null) throw invalid(name + ": " + problem);
            result.put(category, policy);
        });
        return result;
    }

    /**
     * Why a policy is not acceptable, or null. The ranges are those of the per-account policy and of the SQL that reads
     * the setting back; the rate limit and its burst are on or off together.
     */
    static String problem(Policy policy) {
        if (policy.safeConcurrency() < 0 || policy.safeConcurrency() > 10000) return "safeConcurrency must be between 0 and 10000";
        if (policy.safeRpm() < 0 || policy.safeRpm() > 1_000_000) return "safeRpm must be between 0 and 1000000";
        if (policy.burstCapacity() < 0 || policy.burstCapacity() > 100000) return "burstCapacity must be between 0 and 100000";
        if (policy.maxSessions() < 0 || policy.maxSessions() > 100000) return "maxSessions must be between 0 and 100000";
        if (policy.sessionIdleSeconds() < 60 || policy.sessionIdleSeconds() > 86400) return "sessionIdleSeconds must be between 60 and 86400";
        if (policy.cooldownSeconds() < 0 || policy.cooldownSeconds() > 86400) return "cooldownSeconds must be between 0 and 86400";
        if (policy.maxInflightTokens() < 1 || policy.maxInflightTokens() > 1_000_000_000L) return "maxInflightTokens must be between 1 and 1000000000";
        if (policy.allocationPriority() < 1 || policy.allocationPriority() > 10000) return "allocationPriority must be between 1 and 10000";
        if (!TLS_PROFILES.contains(policy.tlsProfile())) return "tlsProfile must be empty, chrome136 or safari18_4";
        if (policy.safeRpm() == 0 && policy.burstCapacity() != 0) return "burstCapacity must be 0 when safeRpm is 0";
        if (policy.safeRpm() > 0 && policy.burstCapacity() == 0) return "burstCapacity must be positive when safeRpm is set";
        return null;
    }

    private Policy read(String value) {
        try {
            Policy policy = mapper.readValue(value, Policy.class);
            return policy.safeConcurrency() == null || policy.safeRpm() == null || policy.burstCapacity() == null
                || policy.maxSessions() == null || policy.sessionIdleSeconds() == null || policy.cooldownSeconds() == null
                || policy.maxInflightTokens() == null || policy.allocationPriority() == null || policy.tlsProfile() == null
                ? null : policy;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private String json(Policy policy) {
        try { return mapper.writeValueAsString(policy); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ALLOCATION_DEFAULTS_INVALID", message);
    }
}
