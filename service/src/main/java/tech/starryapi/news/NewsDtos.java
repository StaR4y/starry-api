package tech.starryapi.news;

import java.util.List;

public final class NewsDtos {
    private NewsDtos() {
    }

    public record DailyIndex(List<DailyIndexItem> items) {
        public DailyIndex {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record DailyIndexItem(String date, String generatedAt, String leadTitle, String href) {
    }

    public record DailyReport(
        String date,
        String generatedAt,
        String href,
        List<NewsSection> sections,
        List<NewsItem> flashes
    ) {
        public DailyReport {
            sections = sections == null ? List.of() : List.copyOf(sections);
            flashes = flashes == null ? List.of() : List.copyOf(flashes);
        }
    }

    public record NewsSection(String label, List<NewsItem> items) {
        public NewsSection {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record NewsItem(String title, String summary, String source, String aihotUrl, String originalUrl) {
    }

    public record HotTopic(int rank, String title, String source, int sourceCount, String aihotUrl, String originalUrl) {
    }

    public record HotTopics(List<HotTopic> items) {
        public HotTopics {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
