package tech.starryapi.documentation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.*;
import tech.starryapi.documentation.DocumentationDtos.*;

class DocumentationControllerTest {
    @TempDir Path directory;

    @Test void blocksUnauthenticatedReadersAndNonAdminWritesBeforeMultipartParsing() {
        var auth = mock(AdminAuth.class);
        var service = mock(DocumentationService.class);
        when(auth.verify(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "AUTH", "Login required"));
        when(auth.verifyAdmin(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ADMIN", "Admin required"));
        var client = client(auth, service, new DocumentationStorage(directory.toString()));
        client.get().uri("/documentation").exchange().expectStatus().isUnauthorized();
        client.post().uri("/admin/documentation/pages/example/attachments")
            .header("Content-Type", "multipart/form-data; boundary=bad").bodyValue("malformed")
            .exchange().expectStatus().isForbidden();
        client.put().uri("/admin/documentation/settings").bodyValue(new Settings("")).exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }

    @Test void validatesContentAndRejectsOversizedBodies() {
        var auth = mock(AdminAuth.class);
        when(auth.verifyAdmin(any())).thenReturn(new AdminPrincipal("admin", "admin", "ADMIN", Instant.now().plusSeconds(60), false));
        var service = mock(DocumentationService.class);
        var client = client(auth, service, new DocumentationStorage(directory.toString()));
        client.post().uri("/admin/documentation/pages").bodyValue(new Content("", "category", "", "", 0))
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/documentation/pages")
            .bodyValue(new Content("Original", "category", "", "body", 0, new Translation("", "category", "", "body")))
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/documentation/pages")
            .bodyValue(new Content("Original", "category", "", "body", 0, new Translation("English", "category", "", "x".repeat(100001))))
            .exchange().expectStatus().isBadRequest();
        client.put().uri("/admin/documentation/pages/x").bodyValue(new Save(new Content("Title", "category", "", "", 0), 0, true))
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/documentation/pages/x/attachments").header("Content-Length", "70000000")
            .exchange().expectStatus().isEqualTo(413);
        verifyNoInteractions(service);
    }

    @Test void localeChoosesEnglishWhileUnknownLocalesUseTheOriginal() {
        var auth = mock(AdminAuth.class);
        var service = mock(DocumentationService.class);
        var original = new Document("guide", new Content("原文", "category", "", "body", 0), Instant.now(), List.of());
        var english = new Document("guide", new Content("English", "category", "", "body", 0), Instant.now(), List.of());
        when(service.read("guide")).thenReturn(Mono.just(original));
        when(service.read("guide", true)).thenReturn(Mono.just(english));
        when(service.catalog("key", true)).thenReturn(Mono.just(new Catalog(new Settings(""), List.of())));
        var client = client(auth, service, new DocumentationStorage(directory.toString()));
        client.get().uri("/documentation/pages/guide?lang=en-US").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data.content.title").isEqualTo("English");
        client.get().uri("/documentation/pages/guide?lang=fr").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data.content.title").isEqualTo("原文");
        client.get().uri("/documentation?lang=en-GB&q=key").exchange().expectStatus().isOk();
        verify(service).catalog("key", true);
        verify(auth, atLeast(3)).verify(any());
    }

    @Test void servesVideoByteRangesWithoutLeakingSessionCredentials() throws Exception {
        var auth = mock(AdminAuth.class);
        var service = mock(DocumentationService.class);
        var storage = new DocumentationStorage(directory.toString());
        String id = UUID.randomUUID().toString();
        Files.write(directory.resolve(id + ".bin"), new byte[]{0,1,2,3,4,5,6,7,8,9});
        when(service.media(eq(id), anyLong(), eq(false), eq("signed"))).thenReturn(Mono.just(new Attachment(id, "sample.mp4", "video/mp4", 10, "")));
        client(auth, service, storage).get().uri("/documentation/media/" + id + "?expires=1&admin=false&signature=signed")
            .header("Range", "bytes=2-5").exchange().expectStatus().isEqualTo(206)
            .expectHeader().valueEquals("Content-Range", "bytes 2-5/10")
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectBody(byte[].class).isEqualTo(new byte[]{2,3,4,5});
        verifyNoInteractions(auth);
    }

    @Test void rejectsChunkedRequestsOverTheLimit() {
        var auth = mock(AdminAuth.class);
        var filter = new DocumentationRequestFilter(auth, new ObjectMapper().findAndRegisterModules());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/admin/documentation/pages"));
        var request = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override public reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
                return reactor.core.publisher.Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[1024 * 1024 + 1]));
            }
        };
        assertThatThrownBy(() -> filter.filter(exchange.mutate().request(request).build(), ex -> ex.getRequest().getBody().then()).block())
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void rejectsDisguisedFilesAndCleansIncompleteUploads() throws Exception {
        var storage = new DocumentationStorage(directory.toString());
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("../fake.png");
        when(file.transferTo(any(Path.class))).thenAnswer(call -> Mono.fromRunnable(() -> {
            try { Files.writeString(call.getArgument(0, Path.class), "not a png"); }
            catch (Exception e) { throw new RuntimeException(e); }
        }));
        assertThatThrownBy(() -> storage.store(file).block()).isInstanceOf(ApiException.class);
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        assertThat(DocumentationStorage.filename("../image.png")).isEqualTo("image.png");
        assertThat(DocumentationStorage.matches("png", HexFormat.of().parseHex("89504e470d0a1a0a"))).isTrue();
        assertThatThrownBy(() -> storage.resource("../../secret").block()).isInstanceOf(ApiException.class);
        String id = UUID.randomUUID().toString();
        var query = org.springframework.web.util.UriComponentsBuilder.fromUriString(storage.signedUrl(id, false)).build().getQueryParams();
        long expires = Long.parseLong(query.getFirst("expires"));
        String signature = query.getFirst("signature");
        storage.verifySignature(id, expires, false, signature);
        assertThatThrownBy(() -> storage.verifySignature(id, expires, true, signature)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.verifySignature(id, Instant.now().minusSeconds(1).getEpochSecond(), false, signature)).isInstanceOf(ApiException.class);
    }

    private WebTestClient client(AdminAuth auth, DocumentationService service, DocumentationStorage storage) {
        return WebTestClient.bindToController(new DocumentationController(auth, service, storage))
            .controllerAdvice(new GlobalExceptionHandler()).webFilter(new DocumentationRequestFilter(auth, new ObjectMapper().findAndRegisterModules())).build();
    }
}
