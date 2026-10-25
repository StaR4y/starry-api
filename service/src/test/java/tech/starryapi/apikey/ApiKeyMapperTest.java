package tech.starryapi.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What the key list tells about the source pool a key prefers: only what the owner chose, so the list reads as it did. */
class ApiKeyMapperTest {

    private static ApiKeyEntity key(String tierPreference) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId("key-1");
        entity.setModelProvider("Anthropic");
        entity.setTierPreference(tierPreference);
        return entity;
    }

    @Test
    void aKeyThatPrefersAPoolSaysWhich() {
        assertThat(ApiKeyMapper.toResponse(key("kiro+")).tierPreference()).isEqualTo("kiro+");
        assertThat(ApiKeyMapper.toResponse(key("l2")).tierPreference()).isEqualTo("l2");
    }

    @Test
    void aKeyThatLeavesTheChoiceToTheGatewaySaysNothing() {
        assertThat(ApiKeyMapper.toResponse(key("auto")).tierPreference()).isNull();
        assertThat(ApiKeyMapper.toResponse(key(null)).tierPreference()).isNull();
        assertThat(ApiKeyMapper.toResponse(key(" ")).tierPreference()).isNull();
        assertThat(ApiKeyMapper.toResponse(new ApiKeyEntity()).tierPreference()).isNull();
    }
}
