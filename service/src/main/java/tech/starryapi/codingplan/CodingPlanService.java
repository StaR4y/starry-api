package tech.starryapi.codingplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.codingplan.CodingPlanDtos.CodingPlanResponse;
import tech.starryapi.codingplan.CodingPlanDtos.CreateCodingPlanRequest;
import tech.starryapi.codingplan.CodingPlanDtos.UpdateCodingPlanRequest;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport;

@Service
public class CodingPlanService {

    private static final int MAX_LIST = 200;
    private static final String RETURNING_COLUMNS = """
        plan_id, version, name, description, price_credits, duration_days, quota_credits,
        allowed_providers, sort_order, enabled, created_at, updated_at
        """;

    private final R2dbcEntityTemplate template;
    private final ModelCatalogProvider modelCatalog;
    private final ObjectMapper objectMapper;

    public CodingPlanService(R2dbcEntityTemplate template, ModelCatalogProvider modelCatalog, ObjectMapper objectMapper) {
        this.template = template;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
    }

    public Flux<CodingPlanResponse> list(boolean enabledOnly) {
        String sql = """
            SELECT %s
            FROM coding_plans
            WHERE (:enabledOnly = FALSE OR enabled = TRUE)
            ORDER BY sort_order, created_at, id
            LIMIT :maxList
            """.formatted(RETURNING_COLUMNS);
        return template.getDatabaseClient().sql(sql)
            .bind("enabledOnly", enabledOnly)
            .bind("maxList", MAX_LIST)
            .map((row, metadata) -> toResponse(row))
            .all();
    }

    public Mono<CodingPlanResponse> create(CreateCodingPlanRequest request) {
        validateFields(request == null ? null : request.name(), request == null ? null : request.description(),
            request == null ? null : request.priceCredits(), request == null ? null : request.quotaCredits(), request == null ? null : request.sortOrder());
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_NAME_REQUIRED", "Coding plan name is required");
        }
        BigDecimal priceCredits = money(request.priceCredits());
        if (priceCredits.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_PRICE_INVALID", "Coding plan price must be zero or positive");
        }
        BigDecimal quotaCredits = money(request.quotaCredits());
        if (quotaCredits.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_QUOTA_INVALID", "Coding plan quota must be positive");
        }
        if (request.durationDays() == null || request.durationDays() < 1 || request.durationDays() > 3650) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_DURATION_INVALID", "Coding plan duration must be between 1 and 3650 days");
        }
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        String allowedProvidersJson = providersJson(request.allowedProviders());
        String sql = """
            INSERT INTO coding_plans (
                plan_id, name, description, price_credits, duration_days, quota_credits,
                allowed_providers, sort_order, enabled
            )
            VALUES (:planId, :name, :description, :priceCredits, :durationDays, :quotaCredits,
                    CAST(:allowedProviders AS jsonb), :sortOrder, TRUE)
            RETURNING %s
            """.formatted(RETURNING_COLUMNS);
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("planId", Hashing.shortUuid())
            .bind("name", request.name().trim())
            .bind("description", request.description() == null ? "" : request.description())
            .bind("priceCredits", priceCredits)
            .bind("durationDays", request.durationDays())
            .bind("quotaCredits", quotaCredits)
            .bind("sortOrder", sortOrder);
        spec = bindProviders(spec, allowedProvidersJson);
        return mapOne(spec);
    }

    public Mono<CodingPlanResponse> update(String planId, UpdateCodingPlanRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_UPDATE_REQUIRED", "Coding plan update payload is required");
        }
        validateFields(request.name(), request.description(), request.priceCredits(), request.quotaCredits(), request.sortOrder());
        if (request.durationDays() != null && (request.durationDays() < 1 || request.durationDays() > 3650)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_DURATION_INVALID", "Coding plan duration must be between 1 and 3650 days");
        }
        if (request.priceCredits() != null && money(request.priceCredits()).signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_PRICE_INVALID", "Coding plan price must be zero or positive");
        }
        if (request.quotaCredits() != null && money(request.quotaCredits()).signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_QUOTA_INVALID", "Coding plan quota must be positive");
        }
        String sql = """
            UPDATE coding_plans
            SET name = CASE WHEN :setName THEN :name ELSE name END,
                description = CASE WHEN :setDescription THEN :description ELSE description END,
                price_credits = CASE WHEN :setPriceCredits THEN :priceCredits ELSE price_credits END,
                duration_days = CASE WHEN :setDurationDays THEN :durationDays ELSE duration_days END,
                quota_credits = CASE WHEN :setQuotaCredits THEN :quotaCredits ELSE quota_credits END,
                allowed_providers = CASE WHEN :setAllowedProviders THEN CAST(:allowedProviders AS jsonb) ELSE allowed_providers END,
                sort_order = CASE WHEN :setSortOrder THEN :sortOrder ELSE sort_order END,
                enabled = CASE WHEN :setEnabled THEN :enabled ELSE enabled END,
                updated_at = clock_timestamp()
            WHERE plan_id = :planId
            RETURNING %s
            """.formatted(RETURNING_COLUMNS);
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("setName", request.name() != null)
            .bind("setDescription", request.description() != null)
            .bind("setPriceCredits", request.priceCredits() != null)
            .bind("setDurationDays", request.durationDays() != null)
            .bind("setQuotaCredits", request.quotaCredits() != null)
            .bind("setAllowedProviders", request.allowedProviders() != null)
            .bind("setSortOrder", request.sortOrder() != null)
            .bind("setEnabled", request.enabled() != null)
            .bind("planId", clean(planId))
            .bind("name", request.name() == null ? "" : request.name().trim())
            .bind("description", request.description() == null ? "" : request.description())
            .bind("priceCredits", request.priceCredits() == null ? BigDecimal.ZERO : money(request.priceCredits()))
            .bind("durationDays", request.durationDays() == null ? 0 : request.durationDays())
            .bind("quotaCredits", request.quotaCredits() == null ? BigDecimal.ZERO : money(request.quotaCredits()))
            .bind("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
            .bind("enabled", Boolean.TRUE.equals(request.enabled()));
        spec = bindProviders(spec, providersJson(request.allowedProviders()));
        return mapOne(spec);
    }

    private static GenericExecuteSpec bindProviders(GenericExecuteSpec spec, String allowedProvidersJson) {
        return allowedProvidersJson == null
            ? spec.bindNull("allowedProviders", String.class)
            : spec.bind("allowedProviders", allowedProvidersJson);
    }

    private Mono<CodingPlanResponse> mapOne(GenericExecuteSpec spec) {
        return spec
            .map((row, metadata) -> toResponse(row))
            .one()
            .switchIfEmpty(Mono.error(new ApiException(
                HttpStatus.NOT_FOUND,
                "CODING_PLAN_NOT_FOUND",
                "Coding plan not found"
            )));
    }

    private CodingPlanResponse toResponse(io.r2dbc.spi.Row row) {
        return new CodingPlanResponse(
            row.get("plan_id", String.class),
            row.get("version", Integer.class),
            row.get("name", String.class),
            row.get("description", String.class),
            row.get("price_credits", BigDecimal.class),
            row.get("duration_days", Integer.class),
            row.get("quota_credits", BigDecimal.class),
            parseProviders(row.get("allowed_providers", String.class)),
            row.get("sort_order", Integer.class),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
            row.get("created_at", Instant.class),
            row.get("updated_at", Instant.class)
        );
    }

    private List<String> parseProviders(String allowedProvidersJson) {
        if (allowedProvidersJson == null || allowedProvidersJson.isBlank() || "null".equals(allowedProvidersJson)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(
                allowedProvidersJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }
            );
            return parsed.stream()
                .map(PlatformModelCatalogSupport::canonicalCatalogProvider)
                .filter(provider -> provider != null && !provider.isBlank())
                .distinct()
                .toList();
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    String providersJson(List<String> providers) {
        if (providers == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String provider : providers) {
            String candidate = provider == null ? "" : provider.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            normalized.add(modelCatalog.models().stream()
                .map(model -> model.provider())
                .filter(value -> PlatformModelCatalogSupport.sameCatalogProvider(value, candidate))
                .map(PlatformModelCatalogSupport::canonicalCatalogProvider)
                .findFirst()
                .orElseThrow(() -> new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CODING_PLAN_PROVIDER_INVALID",
                    "Coding plan provider does not exist in the model catalog: " + candidate
                )));
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(normalized));
        } catch (JsonProcessingException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CODING_PLAN_PROVIDER_ENCODING_FAILED", "Failed to encode allowed providers");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }

    private void validateFields(String name, String description, BigDecimal price, BigDecimal quota, Integer sortOrder) {
        if (name != null && (name.isBlank() || name.length() > 128)
            || description != null && description.length() > 512
            || sortOrder != null && (sortOrder < 0 || sortOrder > 1_000_000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_FIELDS_INVALID", "名称、描述或排序超出允许范围");
        }
        for (BigDecimal value : new BigDecimal[] { price, quota }) {
            if (value != null && (value.signum() < 0 || value.stripTrailingZeros().scale() > 6
                || value.compareTo(new BigDecimal("100000000000000")) >= 0)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CODING_PLAN_AMOUNT_INVALID", "金额最多支持 14 位整数和 6 位小数");
            }
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
