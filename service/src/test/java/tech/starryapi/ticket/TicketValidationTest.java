package tech.starryapi.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import tech.starryapi.common.ApiException;
import tech.starryapi.ticket.TicketDtos.*;

class TicketValidationTest {
    @Test void usersCanOnlyCloseOrReopenEndedTickets() {
        assertThatCode(() -> TicketService.validateTransition(false, "OPEN", "CLOSED")).doesNotThrowAnyException();
        assertThatCode(() -> TicketService.validateTransition(false, "RESOLVED", "OPEN")).doesNotThrowAnyException();
        assertThatThrownBy(() -> TicketService.validateTransition(false, "OPEN", "RESOLVED")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TicketService.validateTransition(false, "IN_PROGRESS", "OPEN")).isInstanceOf(ApiException.class);
    }
    @Test void staleVersionAndInvalidInputsAreRejected() {
        var ticket = new Ticket("id", "u", "name", "title", "API", "OPEN", 2, 1, Instant.now(), Instant.now());
        assertThatThrownBy(() -> TicketService.checkVersion(ticket, 1L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TicketService.validateSubmission(new Submission("title\r\nBcc:attacker", "API", "body", null, null), true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TicketService.validateSubmission(new Submission("title", "API", " ", null, null), true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TicketService.validateSubmission(new Submission("title", "INVALID", "body", null, null), true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TicketService.validateSubmission(new Submission(null, null, "body", null, null), false)).isInstanceOf(ApiException.class);
    }
    @Test void attachmentNamesAreSafeAndBinaryPayloadIsBounded() {
        assertThat(TicketAttachments.safeFilename("../../log\r\n.txt")).isEqualTo("log.txt");
        assertThat(TicketAttachments.safeFilename("C:\\uploads\\错误日志.txt")).isEqualTo("错误日志.txt");
        assertThatThrownBy(() -> TicketAttachments.safeFilename("..")).isInstanceOf(ApiException.class);
        var attachments = new TicketAttachments();
        assertThat(attachments.read(Flux.just(file("a.log", new byte[]{1, 2}))).block().getFirst().bytes()).containsExactly(1, 2);
        assertThatThrownBy(() -> attachments.read(Flux.just(file("a.log", new byte[TicketAttachments.MAX_BYTES + 1]))).block()).isInstanceOf(DataBufferLimitException.class);
        assertThatThrownBy(() -> attachments.read(Flux.just(file("empty", new byte[0]))).block()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> attachments.read(Flux.range(0, 4).map(i -> file("x", new byte[]{1}))).block()).isInstanceOf(ApiException.class);
    }
    @Test void emailSettingsRejectUnsafeLinksAndUnboundedIntervals() {
        assertThatThrownBy(() -> TicketSettingsService.normalize(new Settings(true, "CONTENT", List.of(), 1, ""))).isInstanceOf(ApiException.class);
        for (String url : List.of("javascript:alert(1)", "https://a:b@example.com", "https://example.com/#/login", "https://example.com/?token=x"))
            assertThatThrownBy(() -> TicketSettingsService.normalize(new Settings(true, "CONTENT", List.of(), 60, url))).isInstanceOf(ApiException.class);
        assertThat(TicketSettingsService.normalize(new Settings(false, "SUMMARY", List.of("Admin@example.com"), 60, "https://example.com/console/")).consoleUrl()).isEqualTo("https://example.com/console");
        assertThat(TicketNotificationDispatcher.retrySeconds(1)).isEqualTo(30);
        assertThat(TicketNotificationDispatcher.retrySeconds(999)).isEqualTo(21600);
    }
    private FilePart file(String name, byte[] bytes) {
        FilePart file = mock(FilePart.class); when(file.filename()).thenReturn(name);
        when(file.content()).thenAnswer(i -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))); return file;
    }
}
