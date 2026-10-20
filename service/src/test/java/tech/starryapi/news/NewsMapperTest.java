package tech.starryapi.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class NewsMapperTest {

    @Test
    void mapsDailyIndexAndReport() throws Exception {
        var json = JsonMapper.builder().build();
        var index = NewsMapper.dailies(json.readTree("""
            {"items":[{"date":"2026-09-21","generatedAt":"2026-09-21T00:00:18Z","leadTitle":"Qwen","links":{"aihot":"https://aihot.news/daily/2026-09-21"}}]}
            """));
        assertThat(index.items()).singleElement().satisfies(item -> {
            assertThat(item.date()).isEqualTo("2026-09-21");
            assertThat(item.leadTitle()).isEqualTo("Qwen");
        });

        var report = NewsMapper.daily(json.readTree("""
            {"report":{"date":"2026-09-21","links":{"aihot":"https://aihot.news/daily/2026-09-21"},
              "sections":[{"label":"模型","items":[{"title":"A","summary":"s","source":{"name":"Qwen"},"links":{"aihot":"https://aihot.news/items/a","original":"https://qwen.ai"}}]}],
              "flashes":[]}}
            """));
        assertThat(report.sections()).singleElement().satisfies(section -> {
            assertThat(section.label()).isEqualTo("模型");
            assertThat(section.items().getFirst().originalUrl()).isEqualTo("https://qwen.ai");
        });
    }
}
