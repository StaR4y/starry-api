package tech.starryapi.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.payment.PaymentDtos.PaymentSettingsResponse;
import tech.starryapi.payment.PaymentDtos.UpdatePaymentSettingsRequest;

@Service
public class PaymentSettingsService {

    static final String PAYMENT_ENABLED = "payment_enabled";
    static final String MIN_RECHARGE_AMOUNT = "MIN_RECHARGE_AMOUNT";
    static final String MAX_RECHARGE_AMOUNT = "MAX_RECHARGE_AMOUNT";
    static final String DAILY_RECHARGE_LIMIT = "DAILY_RECHARGE_LIMIT";
    static final String ORDER_TIMEOUT_MINUTES = "ORDER_TIMEOUT_MINUTES";
    static final String MAX_PENDING_ORDERS = "MAX_PENDING_ORDERS";
    static final String ENABLED_PAYMENT_TYPES = "ENABLED_PAYMENT_TYPES";
    static final String BALANCE_RECHARGE_MULTIPLIER = "BALANCE_RECHARGE_MULTIPLIER";
    static final String API_BILLING_MULTIPLIER = "API_BILLING_MULTIPLIER";
    static final String RECHARGE_FEE_RATE = "RECHARGE_FEE_RATE";
    static final String PRODUCT_NAME_PREFIX = "PRODUCT_NAME_PREFIX";
    static final String PRODUCT_NAME_SUFFIX = "PRODUCT_NAME_SUFFIX";
    static final String PAYMENT_HELP_IMAGE_URL = "PAYMENT_HELP_IMAGE_URL";
    static final String PAYMENT_HELP_TEXT = "PAYMENT_HELP_TEXT";
    static final String EASYPAY_API_BASE = "EASYPAY_API_BASE";
    static final String EASYPAY_PID = "EASYPAY_PID";
    static final String EASYPAY_PKEY = "EASYPAY_PKEY";
    static final String EASYPAY_PAYMENT_MODE = "EASYPAY_PAYMENT_MODE";
    static final String INVITE_REBATE_ENABLED = "INVITE_REBATE_ENABLED";
    static final String INVITE_REBATE_RATE = "INVITE_REBATE_RATE";
    static final String INVITE_REBATE_MAX_ORDERS = "INVITE_REBATE_MAX_ORDERS";
    static final String INVITE_REBATE_START_AT = "INVITE_REBATE_START_AT";
    static final String INVITE_REBATE_END_AT = "INVITE_REBATE_END_AT";
    public static final BigDecimal DEFAULT_API_BILLING_MULTIPLIER = new BigDecimal("0.0000166666666667");
    public static final BigDecimal DEFAULT_INVITE_REBATE_RATE = new BigDecimal("0.10");
    public static final int DEFAULT_INVITE_REBATE_MAX_ORDERS = 10;

    private static final List<String> SETTING_KEYS = List.of(
        PAYMENT_ENABLED,
        MIN_RECHARGE_AMOUNT,
        MAX_RECHARGE_AMOUNT,
        DAILY_RECHARGE_LIMIT,
        ORDER_TIMEOUT_MINUTES,
        MAX_PENDING_ORDERS,
        ENABLED_PAYMENT_TYPES,
        BALANCE_RECHARGE_MULTIPLIER,
        API_BILLING_MULTIPLIER,
        RECHARGE_FEE_RATE,
        PRODUCT_NAME_PREFIX,
        PRODUCT_NAME_SUFFIX,
        PAYMENT_HELP_IMAGE_URL,
        PAYMENT_HELP_TEXT,
        EASYPAY_API_BASE,
        EASYPAY_PID,
        EASYPAY_PKEY,
        EASYPAY_PAYMENT_MODE,
        INVITE_REBATE_ENABLED,
        INVITE_REBATE_RATE,
        INVITE_REBATE_MAX_ORDERS,
        INVITE_REBATE_START_AT,
        INVITE_REBATE_END_AT
    );

    private final SystemSettingRepository repository;

    public PaymentSettingsService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public Mono<PaymentConfig> config() {
        return settingValues().map(this::parseConfig);
    }

    public Mono<PaymentSettingsResponse> publicSettings() {
        return config().map(config -> toResponse(config, false));
    }

    public Mono<PaymentSettingsResponse> settings() {
        return config().map(config -> toResponse(config, true));
    }

    public Mono<PaymentSettingsResponse> update(UpdatePaymentSettingsRequest request) {
        return settingValues()
            .flatMap(previous -> {
                PaymentConfig current = parseConfig(previous);
                PaymentConfig next = new PaymentConfig(
                    request.enabled() == null ? current.enabled() : Boolean.TRUE.equals(request.enabled()),
                    normalizeAmount(request.minAmount() == null ? current.minAmount() : request.minAmount(), BigDecimal.ONE),
                    normalizeOptionalAmount(request.maxAmount() == null ? current.maxAmount() : request.maxAmount()),
                    normalizeOptionalAmount(request.dailyLimit() == null ? current.dailyLimit() : request.dailyLimit()),
                    normalizeOrderTimeout(request.orderTimeoutMinutes() == null ? current.orderTimeoutMinutes() : request.orderTimeoutMinutes()),
                    normalizeMaxPending(request.maxPendingOrders() == null ? current.maxPendingOrders() : request.maxPendingOrders()),
                    request.enabledTypes() == null ? current.enabledTypes() : normalizeTypes(request.enabledTypes()),
                    normalizeMultiplier(request.balanceRechargeMultiplier() == null ? current.balanceRechargeMultiplier() : request.balanceRechargeMultiplier()),
                    normalizeApiBillingMultiplier(request.apiBillingMultiplier() == null ? current.apiBillingMultiplier() : request.apiBillingMultiplier()),
                    normalizeFeeRate(request.rechargeFeeRate() == null ? current.rechargeFeeRate() : request.rechargeFeeRate()),
                    request.productNamePrefix() == null ? current.productNamePrefix() : clean(request.productNamePrefix()),
                    request.productNameSuffix() == null ? current.productNameSuffix() : clean(request.productNameSuffix()),
                    request.helpImageUrl() == null ? current.helpImageUrl() : clean(request.helpImageUrl()),
                    request.helpText() == null ? current.helpText() : clean(request.helpText()),
                    request.easypayApiBase() == null ? current.easypayApiBase() : clean(request.easypayApiBase()),
                    request.easypayPid() == null ? current.easypayPid() : clean(request.easypayPid()),
                    passwordValue(request.easypayPkey(), current.easypayPkey()),
                    normalizePaymentMode(request.easypayPaymentMode() == null ? current.easypayPaymentMode() : request.easypayPaymentMode()),
                    request.inviteRebateEnabled() == null ? current.inviteRebateEnabled() : Boolean.TRUE.equals(request.inviteRebateEnabled()),
                    normalizeInviteRebateRate(request.inviteRebateRate() == null ? current.inviteRebateRate() : request.inviteRebateRate()),
                    normalizeInviteRebateMaxOrders(request.inviteRebateMaxOrders() == null ? current.inviteRebateMaxOrders() : request.inviteRebateMaxOrders()),
                    request.inviteRebateStartAt(),
                    request.inviteRebateEndAt()
                );
                validateRange(next);
                return Mono.when(
                    set(PAYMENT_ENABLED, Boolean.toString(next.enabled())),
                    set(MIN_RECHARGE_AMOUNT, next.minAmount().toPlainString()),
                    set(MAX_RECHARGE_AMOUNT, next.maxAmount().toPlainString()),
                    set(DAILY_RECHARGE_LIMIT, next.dailyLimit().toPlainString()),
                    set(ORDER_TIMEOUT_MINUTES, Integer.toString(next.orderTimeoutMinutes())),
                    set(MAX_PENDING_ORDERS, Integer.toString(next.maxPendingOrders())),
                    set(ENABLED_PAYMENT_TYPES, String.join(",", next.enabledTypes())),
                    set(BALANCE_RECHARGE_MULTIPLIER, next.balanceRechargeMultiplier().toPlainString()),
                    set(API_BILLING_MULTIPLIER, next.apiBillingMultiplier().toPlainString()),
                    set(RECHARGE_FEE_RATE, next.rechargeFeeRate().toPlainString()),
                    set(PRODUCT_NAME_PREFIX, next.productNamePrefix()),
                    set(PRODUCT_NAME_SUFFIX, next.productNameSuffix()),
                    set(PAYMENT_HELP_IMAGE_URL, next.helpImageUrl()),
                    set(PAYMENT_HELP_TEXT, next.helpText()),
                    set(EASYPAY_API_BASE, next.easypayApiBase()),
                    set(EASYPAY_PID, next.easypayPid()),
                    set(EASYPAY_PKEY, next.easypayPkey()),
                    set(EASYPAY_PAYMENT_MODE, next.easypayPaymentMode()),
                    set(INVITE_REBATE_ENABLED, Boolean.toString(next.inviteRebateEnabled())),
                    set(INVITE_REBATE_RATE, next.inviteRebateRate().toPlainString()),
                    set(INVITE_REBATE_MAX_ORDERS, Integer.toString(next.inviteRebateMaxOrders())),
                    set(INVITE_REBATE_START_AT, instantValue(next.inviteRebateStartAt())),
                    set(INVITE_REBATE_END_AT, instantValue(next.inviteRebateEndAt()))
                )
                .then(settingValues())
                .map(saved -> toResponse(parseConfig(saved), true));
            });
    }

    private Mono<Map<String, String>> settingValues() {
        return Flux.fromIterable(SETTING_KEYS)
            .flatMap(key -> value(key, defaultValue(key)).map(value -> Map.entry(key, value)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private PaymentConfig parseConfig(Map<String, String> values) {
        PaymentConfig config = new PaymentConfig(
            bool(values, PAYMENT_ENABLED, false),
            amount(values, MIN_RECHARGE_AMOUNT, BigDecimal.ONE),
            amount(values, MAX_RECHARGE_AMOUNT, BigDecimal.ZERO),
            amount(values, DAILY_RECHARGE_LIMIT, BigDecimal.ZERO),
            normalizeOrderTimeout(number(values, ORDER_TIMEOUT_MINUTES, 30)),
            normalizeMaxPending(number(values, MAX_PENDING_ORDERS, 3)),
            normalizeTypes(splitTypes(values.get(ENABLED_PAYMENT_TYPES))),
            normalizeMultiplier(amount(values, BALANCE_RECHARGE_MULTIPLIER, BigDecimal.ONE)),
            normalizeApiBillingMultiplier(amount(values, API_BILLING_MULTIPLIER, DEFAULT_API_BILLING_MULTIPLIER)),
            normalizeFeeRate(amount(values, RECHARGE_FEE_RATE, BigDecimal.ZERO)),
            clean(values.get(PRODUCT_NAME_PREFIX)),
            clean(values.get(PRODUCT_NAME_SUFFIX)),
            clean(values.get(PAYMENT_HELP_IMAGE_URL)),
            clean(values.get(PAYMENT_HELP_TEXT)),
            clean(values.get(EASYPAY_API_BASE)),
            clean(values.get(EASYPAY_PID)),
            clean(values.get(EASYPAY_PKEY)),
            normalizePaymentMode(values.get(EASYPAY_PAYMENT_MODE)),
            bool(values, INVITE_REBATE_ENABLED, false),
            normalizeInviteRebateRate(amount(values, INVITE_REBATE_RATE, DEFAULT_INVITE_REBATE_RATE)),
            normalizeInviteRebateMaxOrders(number(values, INVITE_REBATE_MAX_ORDERS, DEFAULT_INVITE_REBATE_MAX_ORDERS)),
            instant(values.get(INVITE_REBATE_START_AT)),
            instant(values.get(INVITE_REBATE_END_AT))
        );
        validateRange(config);
        return config;
    }

    private PaymentSettingsResponse toResponse(PaymentConfig config, boolean includeProviderConfig) {
        return new PaymentSettingsResponse(
            config.enabled(),
            config.minAmount(),
            config.maxAmount(),
            config.dailyLimit(),
            config.orderTimeoutMinutes(),
            config.maxPendingOrders(),
            config.enabledTypes(),
            config.balanceRechargeMultiplier(),
            config.apiBillingMultiplier(),
            config.rechargeFeeRate(),
            config.productNamePrefix(),
            config.productNameSuffix(),
            config.helpImageUrl(),
            config.helpText(),
            includeProviderConfig ? config.easypayApiBase() : null,
            includeProviderConfig ? config.easypayPid() : null,
            includeProviderConfig ? !config.easypayPkey().isBlank() : null,
            includeProviderConfig ? config.easypayPaymentMode() : null,
            config.inviteRebateEnabled(),
            config.inviteRebateRate(),
            config.inviteRebateMaxOrders(),
            config.inviteRebateStartAt(),
            config.inviteRebateEndAt()
        );
    }

    private Mono<String> value(String key, String fallback) {
        return repository.findById(key)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty(fallback);
    }

    private Mono<Void> set(String key, String value) {
        return repository.upsertSetting(key, value == null ? "" : value).then();
    }

    private String defaultValue(String key) {
        return switch (key) {
            case PAYMENT_ENABLED -> "false";
            case MIN_RECHARGE_AMOUNT, BALANCE_RECHARGE_MULTIPLIER -> "1";
            case API_BILLING_MULTIPLIER -> DEFAULT_API_BILLING_MULTIPLIER.toPlainString();
            case ORDER_TIMEOUT_MINUTES -> "30";
            case MAX_PENDING_ORDERS -> "3";
            case ENABLED_PAYMENT_TYPES -> "alipay,wxpay";
            case EASYPAY_PAYMENT_MODE -> "popup";
            case INVITE_REBATE_ENABLED -> "false";
            case INVITE_REBATE_RATE -> DEFAULT_INVITE_REBATE_RATE.toPlainString();
            case INVITE_REBATE_MAX_ORDERS -> Integer.toString(DEFAULT_INVITE_REBATE_MAX_ORDERS);
            default -> "";
        };
    }

    private void validateRange(PaymentConfig config) {
        if (config.maxAmount().signum() > 0 && config.minAmount().compareTo(config.maxAmount()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_RANGE", "Minimum amount must not exceed maximum amount");
        }
        if (config.inviteRebateStartAt() != null
            && config.inviteRebateEndAt() != null
            && !config.inviteRebateStartAt().isBefore(config.inviteRebateEndAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITE_REBATE_WINDOW", "Invite rebate start time must be before end time");
        }
    }

    private List<String> splitTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("alipay", "wxpay");
        }
        return List.of(raw.split(","));
    }

    private List<String> normalizeTypes(List<String> raw) {
        List<String> normalized = raw == null ? List.of() : raw.stream()
            .map(this::clean)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> value.equals("alipay") || value.equals("wxpay"))
            .distinct()
            .toList();
        return normalized.isEmpty() ? List.of("alipay", "wxpay") : normalized;
    }

    private BigDecimal normalizeAmount(BigDecimal value, BigDecimal fallback) {
        BigDecimal amount = value == null ? fallback : value;
        if (amount.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_AMOUNT", "Payment amount must not be negative");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeOptionalAmount(BigDecimal value) {
        return normalizeAmount(value, BigDecimal.ZERO);
    }

    private BigDecimal normalizeMultiplier(BigDecimal value) {
        BigDecimal multiplier = value == null ? BigDecimal.ONE : value;
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return multiplier.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeApiBillingMultiplier(BigDecimal value) {
        BigDecimal multiplier = value == null ? DEFAULT_API_BILLING_MULTIPLIER : value;
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            return DEFAULT_API_BILLING_MULTIPLIER;
        }
        return multiplier.setScale(16, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeFeeRate(BigDecimal value) {
        BigDecimal rate = value == null ? BigDecimal.ZERO : value;
        if (rate.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_FEE_RATE", "Recharge fee rate must not be negative");
        }
        return rate.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeInviteRebateRate(BigDecimal value) {
        BigDecimal rate = value == null ? DEFAULT_INVITE_REBATE_RATE : value;
        BigDecimal min = new BigDecimal("0.10");
        BigDecimal max = new BigDecimal("0.20");
        if (rate.compareTo(min) < 0 || rate.compareTo(max) > 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_INVITE_REBATE_RATE",
                "Invite rebate rate must be between 10% and 20%"
            );
        }
        return rate.setScale(6, RoundingMode.HALF_UP);
    }

    private int normalizeOrderTimeout(Integer value) {
        if (value == null || value < 1) {
            return 30;
        }
        return Math.min(value, 24 * 60);
    }

    private int normalizeMaxPending(Integer value) {
        if (value == null || value < 1) {
            return 3;
        }
        return Math.min(value, 50);
    }

    private int normalizeInviteRebateMaxOrders(Integer value) {
        if (value == null || value < 1) {
            return DEFAULT_INVITE_REBATE_MAX_ORDERS;
        }
        return Math.min(value, 100);
    }

    private String normalizePaymentMode(String value) {
        String mode = clean(value).toLowerCase(Locale.ROOT);
        return mode.equals("qrcode") ? "qrcode" : "popup";
    }

    private String passwordValue(String requested, String previous) {
        String cleaned = clean(requested);
        return cleaned.isBlank() ? clean(previous) : cleaned;
    }

    private boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private int number(Map<String, String> values, String key, int fallback) {
        try {
            return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private BigDecimal amount(Map<String, String> values, String key, BigDecimal fallback) {
        try {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : new BigDecimal(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Instant instant(String value) {
        try {
            String cleaned = clean(value);
            return cleaned.isBlank() ? null : Instant.parse(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String instantValue(Instant value) {
        return value == null ? "" : value.toString();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record PaymentConfig(
        boolean enabled,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal dailyLimit,
        int orderTimeoutMinutes,
        int maxPendingOrders,
        List<String> enabledTypes,
        BigDecimal balanceRechargeMultiplier,
        BigDecimal apiBillingMultiplier,
        BigDecimal rechargeFeeRate,
        String productNamePrefix,
        String productNameSuffix,
        String helpImageUrl,
        String helpText,
        String easypayApiBase,
        String easypayPid,
        String easypayPkey,
        String easypayPaymentMode,
        boolean inviteRebateEnabled,
        BigDecimal inviteRebateRate,
        int inviteRebateMaxOrders,
        Instant inviteRebateStartAt,
        Instant inviteRebateEndAt
    ) {
        boolean easypayConfigured() {
            return !easypayApiBase.isBlank() && !easypayPid.isBlank() && !easypayPkey.isBlank();
        }

        boolean inviteRebateActiveAt(Instant now) {
            if (!inviteRebateEnabled) {
                return false;
            }
            if (inviteRebateStartAt != null && now.isBefore(inviteRebateStartAt)) {
                return false;
            }
            return inviteRebateEndAt == null || now.isBefore(inviteRebateEndAt);
        }
    }
}
