package tech.starryapi.stats;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class StatsService {

    private final RequestLogRepository repository;

    public StatsService(RequestLogRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> log(RequestLog event) {
        RequestLogEntity entity = new RequestLogEntity();
        entity.setRequestId(event.requestId());
        entity.setApiKeyId(event.apiKeyId());
        entity.setUserId(event.userId());
        entity.setAccountId(event.accountId());
        entity.setPath(event.path());
        entity.setModel(event.model());
        entity.setStatusCode(event.statusCode());
        entity.setSuccess(event.success());
        entity.setInputTokens(event.inputTokens());
        entity.setOutputTokens(event.outputTokens());
        entity.setCredits(event.credits() == null ? BigDecimal.ZERO : event.credits());
        entity.setLatencyMs(event.latencyMs());
        entity.setErrorMessage(sanitize(event.errorMessage()));
        entity.setCircuitKey(event.circuitKey());
        entity.setCircuitTier(event.circuitTier());
        entity.setUpstreamModel(event.upstreamModel());
        entity.setUpstreamSuccess(event.upstreamSuccess());
        entity.setCircuitEpoch(event.circuitEpoch());
        entity.setSourceTierRef(clip(event.sourceTierRef(), 128));
        entity.setSourceTierLevel(event.sourceTierLevel());
        entity.setSourceTierName(clip(event.sourceTierName(), 64));
        entity.setPriceFactor(event.priceFactor());
        entity.setTierSelection(clip(event.tierSelection(), 32));
        entity.setSourceDispatch(clip(event.sourceDispatch(), 32));
        entity.setWireModel(clip(event.wireModel(), 256));
        entity.setUsageSource(clip(event.usageSource(), 32));
        entity.setStopReason(clip(event.stopReason(), 32));
        entity.setDegradedFeatures(clip(event.degradedFeatures(), 512));
        entity.setWithheldInputTokens(event.withheldInputTokens());
        entity.setWithheldOutputTokens(event.withheldOutputTokens());
        return repository.save(entity).then();
    }

    /** The source columns are bounded; a value that is too long is cut rather than costing the whole log row. */
    private static String clip(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return message
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9\\-_.~+/]+=*", "Bearer ***")
            .replaceAll("(?i)(access[_-]?token[\\\"':=\\s]+)[^\\s,}]+", "$1***");
    }

    public record RequestLog(
        String requestId,
        String apiKeyId,
        String userId,
        String accountId,
        String path,
        String model,
        int statusCode,
        boolean success,
        long inputTokens,
        long outputTokens,
        BigDecimal credits,
        long latencyMs,
        String errorMessage,
        String circuitKey,
        String circuitTier,
        String upstreamModel,
        Boolean upstreamSuccess,
        UUID circuitEpoch,
        String sourceTierRef,
        Integer sourceTierLevel,
        String sourceTierName,
        BigDecimal priceFactor,
        String tierSelection,
        String sourceDispatch,
        String wireModel,
        String usageSource,
        String stopReason,
        String degradedFeatures,
        Long withheldInputTokens,
        Long withheldOutputTokens
    ) {
        /** A row of a request that is not served from source tiers: none of the source columns is set. */
        public RequestLog(
            String requestId,
            String apiKeyId,
            String userId,
            String accountId,
            String path,
            String model,
            int statusCode,
            boolean success,
            long inputTokens,
            long outputTokens,
            BigDecimal credits,
            long latencyMs,
            String errorMessage,
            String circuitKey,
            String circuitTier,
            String upstreamModel,
            Boolean upstreamSuccess,
            UUID circuitEpoch
        ) {
            this(
                requestId,
                apiKeyId,
                userId,
                accountId,
                path,
                model,
                statusCode,
                success,
                inputTokens,
                outputTokens,
                credits,
                latencyMs,
                errorMessage,
                circuitKey,
                circuitTier,
                upstreamModel,
                upstreamSuccess,
                circuitEpoch,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }

        public RequestLog(
            String requestId,
            String apiKeyId,
            String userId,
            String accountId,
            String path,
            String model,
            int statusCode,
            boolean success,
            long inputTokens,
            long outputTokens,
            BigDecimal credits,
            long latencyMs,
            String errorMessage
        ) {
            this(
                requestId,
                apiKeyId,
                userId,
                accountId,
                path,
                model,
                statusCode,
                success,
                inputTokens,
                outputTokens,
                credits,
                latencyMs,
                errorMessage,
                null,
                null,
                null,
                null,
                null
            );
        }

        /**
         * This row with the source columns of a request that was served from source tiers (design 5.7). Null leaves
         * the row as it is, which is what an untiered request passes.
         */
        public RequestLog withSource(SourceColumns source) {
            if (source == null) {
                return this;
            }
            return new RequestLog(
                requestId,
                apiKeyId,
                userId,
                accountId,
                path,
                model,
                statusCode,
                success,
                inputTokens,
                outputTokens,
                credits,
                latencyMs,
                errorMessage,
                circuitKey,
                circuitTier,
                upstreamModel,
                upstreamSuccess,
                circuitEpoch,
                source.tierRef(),
                source.level(),
                source.name(),
                source.factor(),
                source.selection(),
                source.dispatch(),
                source.wireModel(),
                source.usageSource(),
                source.stopReason(),
                source.degradedFeatures(),
                source.withheldInputTokens(),
                source.withheldOutputTokens()
            );
        }
    }

    /**
     * Which source tier an attempt went to and how, as the request log keeps it. The first five are known for every
     * attempt of a request served from tiers; the rest describe a source that is not a native account and stay null
     * for one.
     *
     * @param tierRef the tier, as {@code vendor:tierId}
     * @param factor the tier's price factor, which the attempt would be charged at
     * @param selection how the tier was chosen: AUTO or PINNED
     * @param dispatch how the request was sent, when that was not natively
     * @param withheldInputTokens what a relay reported as the input of an answer the gateway held back (D11), or null: the
     *     operator's cost, which is no usage of the user's, so it is not among the tokens of the row
     * @param withheldOutputTokens the same for the output
     */
    public record SourceColumns(
        String tierRef,
        Integer level,
        String name,
        BigDecimal factor,
        String selection,
        String dispatch,
        String wireModel,
        String usageSource,
        String stopReason,
        String degradedFeatures,
        Long withheldInputTokens,
        Long withheldOutputTokens
    ) {

        /** The columns of an attempt that was answered or failed with nothing held back. */
        public SourceColumns(
            String tierRef,
            Integer level,
            String name,
            BigDecimal factor,
            String selection,
            String dispatch,
            String wireModel,
            String usageSource,
            String stopReason,
            String degradedFeatures
        ) {
            this(tierRef, level, name, factor, selection, dispatch, wireModel, usageSource, stopReason, degradedFeatures, null, null);
        }
    }
}
