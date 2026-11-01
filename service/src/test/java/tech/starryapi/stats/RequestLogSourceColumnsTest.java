package tech.starryapi.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * The source columns of the request log (design 5.7): what a row keeps of the tier that served it, how it gets to the
 * table and to the administrator's list, and that a row of a request without tiers keeps none of them.
 */
class RequestLogSourceColumnsTest {

    private final RequestLogRepository repository = mock(RequestLogRepository.class);
    private final StatsService service = new StatsService(repository);

    @BeforeEach
    void saveWhatItIsGiven() {
        when(repository.save(any(RequestLogEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void theEighteenArgumentConstructorLeavesEverySourceColumnNull() {
        StatsService.RequestLog row = untiered();

        assertThat(row.sourceTierRef()).isNull();
        assertThat(row.sourceTierLevel()).isNull();
        assertThat(row.sourceTierName()).isNull();
        assertThat(row.priceFactor()).isNull();
        assertThat(row.tierSelection()).isNull();
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
        assertThat(row.usageSource()).isNull();
        assertThat(row.stopReason()).isNull();
        assertThat(row.degradedFeatures()).isNull();
        // The older constructor goes through it.
        assertThat(new StatsService.RequestLog("r", "k", "u", "a", "/p", "m", 200, true, 1, 2, BigDecimal.ONE, 3, null).sourceTierRef())
            .isNull();
    }

    @Test
    void withoutSourceColumnsTheRowIsTheSameObject() {
        StatsService.RequestLog row = untiered();

        assertThat(row.withSource(null)).isSameAs(row);
    }

    @Test
    void withSourceColumnsOnlyTheSourceColumnsChange() {
        StatsService.RequestLog row = untiered();

        StatsService.RequestLog tiered = row.withSource(new StatsService.SourceColumns(
            "anthropic:kiro", 2, "kiro", new BigDecimal("0.6"), "AUTO", "OPENAI_CHAT", "wire", "ESTIMATED", "MAX_TOKENS", "images"));

        assertThat(tiered.sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(tiered.sourceTierLevel()).isEqualTo(2);
        assertThat(tiered.sourceTierName()).isEqualTo("kiro");
        assertThat(tiered.priceFactor()).isEqualByComparingTo("0.6");
        assertThat(tiered.tierSelection()).isEqualTo("AUTO");
        assertThat(tiered.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(tiered.wireModel()).isEqualTo("wire");
        assertThat(tiered.usageSource()).isEqualTo("ESTIMATED");
        assertThat(tiered.stopReason()).isEqualTo("MAX_TOKENS");
        assertThat(tiered.degradedFeatures()).isEqualTo("images");
        assertThat(tiered.withSource(null)).isSameAs(tiered);
        // Every other component is the one of the row it came from.
        assertThat(tiered.requestId()).isEqualTo(row.requestId());
        assertThat(tiered.accountId()).isEqualTo(row.accountId());
        assertThat(tiered.model()).isEqualTo(row.model());
        assertThat(tiered.credits()).isEqualTo(row.credits());
        assertThat(tiered.errorMessage()).isEqualTo(row.errorMessage());
        assertThat(tiered.upstreamModel()).isEqualTo(row.upstreamModel());
        assertThat(tiered.upstreamSuccess()).isEqualTo(row.upstreamSuccess());
        assertThat(tiered.circuitEpoch()).isEqualTo(row.circuitEpoch());
    }

    @Test
    void theServiceWritesTheSourceColumnsIntoTheEntity() {
        StatsService.RequestLog row = untiered().withSource(new StatsService.SourceColumns(
            "anthropic:kiro", 2, "kiro", new BigDecimal("0.6"), "AUTO", null, null, null, null, null));

        service.log(row).block();

        RequestLogEntity entity = savedEntity();
        assertThat(entity.getSourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(entity.getSourceTierLevel()).isEqualTo(2);
        assertThat(entity.getSourceTierName()).isEqualTo("kiro");
        assertThat(entity.getPriceFactor()).isEqualByComparingTo("0.6");
        assertThat(entity.getTierSelection()).isEqualTo("AUTO");
        assertThat(entity.getSourceDispatch()).isNull();
        assertThat(entity.getWireModel()).isNull();
        assertThat(entity.getUsageSource()).isNull();
        assertThat(entity.getStopReason()).isNull();
        assertThat(entity.getDegradedFeatures()).isNull();
        assertThat(entity.getModel()).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void aRowWithoutTiersWritesNoSourceColumns() {
        service.log(untiered()).block();

        RequestLogEntity entity = savedEntity();
        assertThat(entity.getSourceTierRef()).isNull();
        assertThat(entity.getSourceTierLevel()).isNull();
        assertThat(entity.getSourceTierName()).isNull();
        assertThat(entity.getPriceFactor()).isNull();
        assertThat(entity.getTierSelection()).isNull();
        assertThat(entity.getSourceDispatch()).isNull();
        assertThat(entity.getWireModel()).isNull();
        assertThat(entity.getUsageSource()).isNull();
        assertThat(entity.getStopReason()).isNull();
        assertThat(entity.getDegradedFeatures()).isNull();
    }

    @Test
    void aValueThatIsLongerThanItsColumnIsCutInsteadOfLosingTheRow() {
        String longText = "x".repeat(600);
        StatsService.RequestLog row = untiered().withSource(new StatsService.SourceColumns(
            longText, 1, longText, BigDecimal.ONE, longText, longText, longText, longText, longText, longText));

        service.log(row).block();

        RequestLogEntity entity = savedEntity();
        assertThat(entity.getSourceTierRef()).hasSize(128);
        assertThat(entity.getSourceTierName()).hasSize(64);
        assertThat(entity.getTierSelection()).hasSize(32);
        assertThat(entity.getSourceDispatch()).hasSize(32);
        assertThat(entity.getWireModel()).hasSize(256);
        assertThat(entity.getUsageSource()).hasSize(32);
        assertThat(entity.getStopReason()).hasSize(32);
        assertThat(entity.getDegradedFeatures()).hasSize(512);
    }

    @Test
    void theAdministratorsListCarriesEverySourceColumn() {
        RequestLogEntity entity = new RequestLogEntity();
        entity.setRequestId("r");
        entity.setSourceTierRef("anthropic:kiro");
        entity.setSourceTierLevel(2);
        entity.setSourceTierName("kiro");
        entity.setPriceFactor(new BigDecimal("0.600000"));
        entity.setTierSelection("AUTO");
        entity.setSourceDispatch("OPENAI_CHAT");
        entity.setWireModel("wire");
        entity.setUsageSource("REPORTED");
        entity.setStopReason("END_TURN");
        entity.setDegradedFeatures("images");

        StatsDtos.RequestLogResponse response = RequestLogMapper.toResponse(entity);

        assertThat(response.sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(response.sourceTierLevel()).isEqualTo(2);
        assertThat(response.sourceTierName()).isEqualTo("kiro");
        assertThat(response.priceFactor()).isEqualByComparingTo("0.6");
        assertThat(response.tierSelection()).isEqualTo("AUTO");
        assertThat(response.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(response.wireModel()).isEqualTo("wire");
        assertThat(response.usageSource()).isEqualTo("REPORTED");
        assertThat(response.stopReason()).isEqualTo("END_TURN");
        assertThat(response.degradedFeatures()).isEqualTo("images");
    }

    @Test
    void theThirteenArgumentResponseLeavesEverySourceColumnNull() {
        StatsDtos.RequestLogResponse response = new StatsDtos.RequestLogResponse(
            "r", "k", "a", "/p", "m", 200, true, 1L, 2L, BigDecimal.ONE, 3L, null, null);

        assertThat(response.sourceTierRef()).isNull();
        assertThat(response.sourceTierLevel()).isNull();
        assertThat(response.sourceTierName()).isNull();
        assertThat(response.priceFactor()).isNull();
        assertThat(response.tierSelection()).isNull();
        assertThat(response.sourceDispatch()).isNull();
        assertThat(response.wireModel()).isNull();
        assertThat(response.usageSource()).isNull();
        assertThat(response.stopReason()).isNull();
        assertThat(response.degradedFeatures()).isNull();
    }

    private RequestLogEntity savedEntity() {
        ArgumentCaptor<RequestLogEntity> saved = ArgumentCaptor.forClass(RequestLogEntity.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    private static StatsService.RequestLog untiered() {
        return new StatsService.RequestLog("request-1", "key-1", "user-1", "account-1", "/v1/messages", "claude-sonnet-4.5",
            200, true, 8_000, 1_000, new BigDecimal("0.039"), 120, null, "circuit", "PRIMARY", "claude-sonnet-4.5", true, null);
    }
}
