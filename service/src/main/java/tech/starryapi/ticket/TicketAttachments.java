package tech.starryapi.ticket;

import java.util.List;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

@Component
public class TicketAttachments {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_FILES = 3;

    public Mono<List<Upload>> read(Flux<FilePart> files) {
        return files.take(MAX_FILES + 1L).collectList().flatMap(parts -> {
            if (parts.size() > MAX_FILES) return Mono.error(invalid("每次最多上传 3 个附件"));
            return Flux.fromIterable(parts).concatMap(file -> {
                String filename = safeFilename(file.filename());
                return DataBufferUtils.join(file.content(), MAX_BYTES).map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        if (bytes.length == 0) throw invalid("附件不能为空");
                        return new Upload(UUID.randomUUID().toString(), filename, bytes);
                    } finally { DataBufferUtils.release(buffer); }
                }).switchIfEmpty(Mono.error(invalid("附件不能为空")));
            }).collectList();
        });
    }

    static String safeFilename(String value) {
        String name = value == null ? "" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 180)
            throw invalid("附件文件名无效或过长");
        return name;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "TICKET_ATTACHMENT_INVALID", message);
    }
    public record Upload(String id, String filename, byte[] bytes) { }
    public record Download(String filename, byte[] bytes) { }
}
