package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.ticket.TicketDtos.*;

class TicketMailerTest {
    @Test void summaryOmitsBodyAndContentIncludesPlainTextAndPrivateAttachmentNames() {
        var ticket = new Ticket("01234567-test", "user", "alice", "API 问题", "API", "OPEN", 1, 1, Instant.now(), Instant.now());
        var messages = List.of(new Message(1, "alice", false, "MESSAGE", "private <script>payload</script>", "OPEN", Instant.now(), List.of(new Attachment("f", "error.log", 10))));
        String summary = TicketMailer.body(ticket, messages, new Settings(true, "SUMMARY", List.of(), 60, "https://example.com"));
        assertThat(summary).doesNotContain("private", "error.log").contains("/#/tickets/01234567-test");
        String full = TicketMailer.body(ticket, messages, new Settings(true, "CONTENT", List.of(), 60, ""));
        assertThat(full).contains("private <script>payload</script>", "error.log", "不接收工单回复");
    }
}
