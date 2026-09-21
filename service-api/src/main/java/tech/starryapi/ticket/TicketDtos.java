package tech.starryapi.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TicketDtos {
    private TicketDtos() { }

    public record Submission(String title, String category, String body, Long version, String status) { }
    public record StatusUpdate(String status, long version) { }
    public record Ticket(String id, String userId, String username, String title, String category,
                         String status, long version, int messageCount, Instant createdAt, Instant updatedAt) { }
    public record Page(List<Ticket> items, long total, int page, int pageSize, Map<String, Long> counts) { }
    public record Attachment(String id, String filename, int sizeBytes) { }
    public record Message(long id, String actorName, boolean admin, String kind, String body,
                          String status, Instant createdAt, List<Attachment> attachments) { }
    public record ThreadPage(Ticket ticket, List<Message> messages, boolean hasMore, long nextBefore) { }
    public record Settings(boolean enabled, String mode, List<String> recipients, int reminderMinutes,
                           String consoleUrl) { }
    public record NotificationFailure(String ticketId, String title, String reason, int attempts,
                                      Instant nextAttemptAt) { }
    public record SettingsView(Settings settings, boolean smtpConfigured, List<String> effectiveRecipients,
                               List<String> availableRecipients, List<NotificationFailure> failures) { }
}
