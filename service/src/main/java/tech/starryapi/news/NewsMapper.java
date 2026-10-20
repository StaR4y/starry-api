package tech.starryapi.news;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import tech.starryapi.news.NewsDtos.DailyIndex;
import tech.starryapi.news.NewsDtos.DailyIndexItem;
import tech.starryapi.news.NewsDtos.DailyReport;
import tech.starryapi.news.NewsDtos.HotTopic;
import tech.starryapi.news.NewsDtos.HotTopics;
import tech.starryapi.news.NewsDtos.NewsItem;
import tech.starryapi.news.NewsDtos.NewsSection;

final class NewsMapper {
    private NewsMapper() {
    }

    static DailyIndex dailies(JsonNode body) {
        List<DailyIndexItem> items = new ArrayList<>();
        for (JsonNode item : array(body, "items")) {
            String date = text(item, "date");
            if (date.isBlank()) {
                continue;
            }
            JsonNode links = item.path("links");
            items.add(new DailyIndexItem(date, text(item, "generatedAt"), text(item, "leadTitle"), text(links, "aihot")));
        }
        return new DailyIndex(items);
    }

    static DailyReport daily(JsonNode body) {
        JsonNode report = body.path("report");
        if (report.isMissingNode() || report.isNull()) {
            report = body;
        }
        List<NewsSection> sections = new ArrayList<>();
        for (JsonNode section : array(report, "sections")) {
            sections.add(new NewsSection(text(section, "label"), items(section.path("items"))));
        }
        JsonNode links = report.path("links");
        return new DailyReport(
            text(report, "date"),
            text(report, "generatedAt"),
            text(links, "aihot"),
            sections,
            items(report.path("flashes"))
        );
    }

    static HotTopics hotTopics(JsonNode body) {
        List<HotTopic> items = new ArrayList<>();
        for (JsonNode item : array(body, "items")) {
            JsonNode links = item.path("links");
            items.add(new HotTopic(
                item.path("rank").asInt(items.size() + 1),
                text(item, "title"),
                text(item.path("source"), "name"),
                item.path("sourceCount").asInt(0),
                text(links, "aihot"),
                text(links, "original")
            ));
        }
        return new HotTopics(items);
    }

    private static List<NewsItem> items(JsonNode node) {
        List<NewsItem> items = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return items;
        }
        for (JsonNode item : node) {
            String title = text(item, "title");
            if (title.isBlank()) {
                continue;
            }
            JsonNode links = item.path("links");
            items.add(new NewsItem(
                title,
                text(item, "summary"),
                text(item.path("source"), "name"),
                text(links, "aihot"),
                text(links, "original")
            ));
        }
        return items;
    }

    private static List<JsonNode> array(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.path(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
