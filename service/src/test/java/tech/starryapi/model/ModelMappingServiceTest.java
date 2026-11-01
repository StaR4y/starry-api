package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.ObjectMapper;

class ModelMappingServiceTest {

    @Test
    void appliesHighestPriorityWildcardRule() {
        ModelMappingRepository repository = Mockito.mock(ModelMappingRepository.class);
        ModelMappingEntity rule = new ModelMappingEntity();
        rule.setEnabled(true);
        rule.setMappingType("replace");
        rule.setSourceModel("gpt-*");
        rule.setTargetModels("[\"claude-sonnet-4.5\"]");
        rule.setPriority(1);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(Flux.just(rule));

        ModelMappingService service = new ModelMappingService(repository, new ObjectMapper());
        service.refresh().block();

        assertThat(service.apply("gpt-4o", "key-1")).isEqualTo("claude-sonnet-4.5");
        assertThat(service.apply("claude-haiku-4.5", "key-1")).isEqualTo("claude-haiku-4.5");
    }

    @Test
    void treatsRegexMetacharactersAsLiteralsInWildcardRule() {
        ModelMappingRepository repository = Mockito.mock(ModelMappingRepository.class);
        ModelMappingEntity rule = new ModelMappingEntity();
        rule.setEnabled(true);
        rule.setMappingType("replace");
        rule.setSourceModel("gpt-(4o|5)*");
        rule.setTargetModels("[\"claude-sonnet-4.5\"]");
        rule.setPriority(1);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(Flux.just(rule));

        ModelMappingService service = new ModelMappingService(repository, new ObjectMapper());
        service.refresh().block();

        assertThat(service.apply("gpt-4o", "key-1")).isEqualTo("gpt-4o");
        assertThat(service.apply("gpt-(4o|5)-preview", "key-1")).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void knowsWhichModelsAKeyHasAMappingSourceWithAnAtFor() {
        ModelMappingRepository repository = Mockito.mock(ModelMappingRepository.class);
        ModelMappingEntity exact = rule("claude-sonnet-4@20250514", "[\"claude-sonnet-4.5\"]", null);
        ModelMappingEntity wildcard = rule("gpt-*", "[\"claude-sonnet-4.5\"]", null);
        ModelMappingEntity forAnotherKey = rule("claude-opus-4@20250514", "[\"claude-opus-4.5\"]", "[\"key-2\"]");
        ModelMappingEntity withoutTarget = rule("claude-haiku-4@20250514", "[]", null);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(Flux.just(exact, wildcard, forAnotherKey, withoutTarget));
        ModelMappingService service = new ModelMappingService(repository, new ObjectMapper());
        service.refresh().block();

        assertThat(service.hasAtSource("claude-sonnet-4@20250514", "key-1")).isTrue();
        assertThat(service.hasAtSource("Claude-Sonnet-4@20250514", "key-1")).isTrue();
        // A pattern with no @ of its own says nothing about an id that has one; a rule of another key and a rule without a
        // target map nothing for this key.
        assertThat(service.hasAtSource("gpt-5", "key-1")).isFalse();
        assertThat(service.hasAtSource("gpt-5@kiro", "key-1")).isFalse();
        assertThat(service.hasAtSource("claude-opus-4@20250514", "key-1")).isFalse();
        assertThat(service.hasAtSource("claude-opus-4@20250514", "key-2")).isTrue();
        assertThat(service.hasAtSource("claude-haiku-4@20250514", "key-1")).isFalse();
        assertThat(service.hasAtSource("claude-sonnet-4", "key-1")).isFalse();
    }

    @Test
    void aSourceWrittenAroundAnAtTakesTheIdsThatItsWildcardsReach() {
        ModelMappingRepository repository = Mockito.mock(ModelMappingRepository.class);
        ModelMappingEntity dated = rule("claude-sonnet-4@*", "[\"claude-sonnet-4.5\"]", null);
        ModelMappingEntity named = rule("*@cheap", "[\"claude-haiku-4.5\"]", null);
        ModelMappingEntity everything = rule("*", "[\"claude-opus-4.5\"]", null);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(Flux.just(dated, named));
        ModelMappingService service = new ModelMappingService(repository, new ObjectMapper());
        service.refresh().block();

        assertThat(service.hasAtSource("claude-sonnet-4@20250514", "key-1")).isTrue();
        assertThat(service.hasAtSource("claude-sonnet-4.5@cheap", "key-1")).isTrue();
        assertThat(service.hasAtSource("claude-sonnet-4.5@kiro", "key-1")).isFalse();
        assertThat(service.hasAtSource("claude-sonnet-4", "key-1")).isFalse();

        // A bare * has no @ of its own: it maps the base of a suffixed id, and the suffix may still be a selector.
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(Flux.just(everything));
        service.refresh().block();
        assertThat(service.hasAtSource("claude-sonnet-4.5@kiro", "key-1")).isFalse();
    }

    private static ModelMappingEntity rule(String source, String targets, String apiKeyIds) {
        ModelMappingEntity rule = new ModelMappingEntity();
        rule.setEnabled(true);
        rule.setMappingType("replace");
        rule.setSourceModel(source);
        rule.setTargetModels(targets);
        rule.setApiKeyIds(apiKeyIds);
        rule.setPriority(1);
        return rule;
    }

    @Test
    void rejectsOverlongSourceModelPattern() {
        ModelMappingRepository repository = Mockito.mock(ModelMappingRepository.class);
        ModelMappingService service = new ModelMappingService(repository, new ObjectMapper());
        ModelDtos.CreateMappingRequest request = new ModelDtos.CreateMappingRequest(
            "bad",
            "replace",
            "a".repeat(ModelMappingService.MAX_MODEL_PATTERN_LENGTH + 1),
            List.of("claude-sonnet-4.5"),
            null,
            100,
            null
        );

        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(() -> service.create(request).block())
            .withMessageContaining("sourceModel");
    }

    @Test
    void invalidationPreventsInFlightRefreshFromPublishingPreRestoreRules() {
        ModelMappingRepository repository = Mockito.mock(ModelMappingRepository.class);
        Sinks.Many<ModelMappingEntity> staleQuery = Sinks.many().unicast().onBackpressureBuffer();
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(staleQuery.asFlux());
        ModelMappingEntity staleRule = new ModelMappingEntity();
        staleRule.setEnabled(true);
        staleRule.setMappingType("replace");
        staleRule.setSourceModel("gpt-*");
        staleRule.setTargetModels("[\"stale-target\"]");
        staleRule.setPriority(1);
        ModelMappingService service = new ModelMappingService(repository, new ObjectMapper());

        service.refresh().subscribe();
        service.invalidateAllCaches();
        staleQuery.tryEmitNext(staleRule);
        staleQuery.tryEmitComplete();

        assertThat(service.apply("gpt-5", "key-1")).isEqualTo("gpt-5");
    }
}
