package tech.starryapi.account;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tech.starryapi.model.ModelDtos;

final class CompatibleAccountVendors {

    private static final String OPENAI_CHAT_COMPLETIONS = "OPENAI_CHAT_COMPLETIONS";

    private CompatibleAccountVendors() {
    }

    static List<AccountDtos.CompatibleVendorModels> from(List<ModelDtos.ManagedModelResponse> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        Map<String, List<ModelDtos.ManagedModelResponse>> grouped = new LinkedHashMap<>();
        for (ModelDtos.ManagedModelResponse model : models) {
            if (model == null || Boolean.TRUE.equals(model.builtIn())) {
                continue;
            }
            if (model.requestFormat() != null
                && !OPENAI_CHAT_COMPLETIONS.equalsIgnoreCase(model.requestFormat())) {
                continue;
            }
            String name = AccountPlatforms.clean(model.provider());
            if (name == null || AccountPlatforms.isFirstClassAccountProvider(name)) {
                continue;
            }
            grouped.computeIfAbsent(name, key -> new ArrayList<>()).add(model);
        }
        List<AccountDtos.CompatibleVendorModels> vendors = new ArrayList<>();
        grouped.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .forEach(entry -> vendors.add(vendor(entry.getKey(), entry.getValue())));
        return List.copyOf(vendors);
    }

    static AccountDtos.CompatibleVendorModels find(List<AccountDtos.CompatibleVendorModels> vendors, String provider) {
        String providerKey = AccountPlatforms.key(provider);
        if (providerKey.isEmpty() || vendors == null) {
            return null;
        }
        for (AccountDtos.CompatibleVendorModels vendor : vendors) {
            if (providerKey.equals(AccountPlatforms.key(vendor.name()))) {
                return vendor;
            }
        }
        return null;
    }

    static String defaultTestModel(List<AccountDtos.CompatibleVendorModels> vendors, String provider) {
        AccountDtos.CompatibleVendorModels vendor = find(vendors, provider);
        if (vendor == null) {
            return null;
        }
        for (AccountDtos.TestModelOption option : vendor.models()) {
            if (option != null && option.value() != null && !"auto".equalsIgnoreCase(option.value())) {
                return option.value();
            }
        }
        return null;
    }

    private static AccountDtos.CompatibleVendorModels vendor(
        String name,
        List<ModelDtos.ManagedModelResponse> models
    ) {
        String icon = firstPresent(models, ModelDtos.ManagedModelResponse::icon);
        String color = firstPresent(models, ModelDtos.ManagedModelResponse::badgeColor);
        List<ModelDtos.ManagedModelResponse> enabled = models.stream()
            .filter(model -> !Boolean.FALSE.equals(model.enabled()))
            .toList();
        List<ModelDtos.ManagedModelResponse> source = enabled.isEmpty() ? models : enabled;
        List<AccountDtos.TestModelOption> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String firstId = null;
        for (ModelDtos.ManagedModelResponse model : source) {
            String configuredId = firstNonBlank(model.id(), model.targetModelId());
            String upstreamId = firstNonBlank(model.targetModelId(), configuredId);
            if (upstreamId == null || !seen.add(upstreamId.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (firstId == null) {
                firstId = upstreamId;
            }
            String label = configuredId == null || configuredId.equals(upstreamId)
                ? upstreamId
                : configuredId + " → " + upstreamId;
            options.add(new AccountDtos.TestModelOption(label, upstreamId));
        }
        if (firstId != null) {
            options.add(0, new AccountDtos.TestModelOption("auto (" + firstId + ")", "auto"));
        }
        return new AccountDtos.CompatibleVendorModels(name, icon, color, options);
    }

    private static String firstPresent(
        List<ModelDtos.ManagedModelResponse> models,
        java.util.function.Function<ModelDtos.ManagedModelResponse, String> getter
    ) {
        return models.stream()
            .filter(model -> !Boolean.FALSE.equals(model.enabled()))
            .map(getter)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElseGet(() -> models.stream()
                .map(getter)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null));
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }
}
