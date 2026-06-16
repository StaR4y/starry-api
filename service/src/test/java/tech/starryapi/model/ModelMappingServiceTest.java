package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import tech.starryapi.common.ApiException;

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
}
