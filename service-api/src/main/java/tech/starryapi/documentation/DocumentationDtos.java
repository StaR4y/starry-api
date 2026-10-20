package tech.starryapi.documentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class DocumentationDtos {
    private DocumentationDtos() { }

    public record Content(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 60) String category,
        @NotNull @Size(max = 240) String summary,
        @NotNull @Size(max = 100000) String body,
        @Min(0) @Max(9999) int sortOrder,
        @Valid Translation english
    ) {
        public Content(String title, String category, String summary, String body, int sortOrder) {
            this(title, category, summary, body, sortOrder, null);
        }
        public Content localized(boolean useEnglish) {
            return useEnglish && english != null
                ? new Content(english.title(), english.category(), english.summary(), english.body(), sortOrder)
                : new Content(title, category, summary, body, sortOrder);
        }
    }
    public record Translation(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 60) String category,
        @NotNull @Size(max = 240) String summary,
        @NotBlank @Size(max = 100000) String body
    ) { }
    public record Save(@NotNull @Valid Content content, @Min(1) long version, boolean publish) { }
    public record Version(@Min(1) long version) { }
    public record Settings(@NotNull @Size(max = 2000) String apifoxUrl) { }
    public record Entry(String id, String title, String category, String summary, int sortOrder, Instant updatedAt) { }
    public record Catalog(Settings settings, List<Entry> entries) { }
    public record AdminEntry(String id, String title, String category, String summary, int sortOrder,
        long version, boolean published, boolean hasChanges, Instant updatedAt) { }
    public record Document(String id, Content content, Instant updatedAt, List<Attachment> attachments) { }
    public record AdminDocument(String id, Content draft, Content published, long version,
        Instant publishedAt, Instant updatedAt, List<Attachment> attachments) { }
    public record Attachment(String id, String filename, String contentType, long sizeBytes, String url) { }
}
