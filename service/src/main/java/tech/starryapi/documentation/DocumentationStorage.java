package tech.starryapi.documentation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;

@Component
public class DocumentationStorage {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final long REQUEST_LIMIT = MAX_BYTES + 1024 * 1024;
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
        Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"),
        Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
        Map.entry("txt", "text/plain"), Map.entry("json", "application/json"), Map.entry("md", "text/plain")
    );
    private final Path directory;
    private final byte[] secret = new byte[32];

    public DocumentationStorage(@Value("${starry.documentation.attachment-dir:${user.home}/.starry-api/documentation}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        new SecureRandom().nextBytes(secret);
    }

    public Mono<Stored> store(FilePart file) {
        String filename = filename(file.filename());
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(extension);
        if (type == null) return Mono.error(DocumentationService.invalid("不支持此附件格式"));
        String id = UUID.randomUUID().toString();
        return Mono.usingWhen(Mono.fromCallable(() -> {
            Files.createDirectories(directory);
            return Files.createFile(path(id));
        }).subscribeOn(Schedulers.boundedElastic()), path -> file.transferTo(path).then(Mono.fromCallable(() -> {
            long size = Files.size(path);
            if (size < 1 || size > MAX_BYTES) throw DocumentationService.invalid("附件大小必须在 1 B 到 64 MB 之间");
            try (InputStream input = Files.newInputStream(path)) {
                if (!matches(extension, input.readNBytes(16))) throw DocumentationService.invalid("文件内容与扩展名不匹配");
            }
            return new Stored(id, filename, type, size);
        }).subscribeOn(Schedulers.boundedElastic())), path -> Mono.empty(),
            (path, error) -> remove(id), path -> remove(id));
    }

    public Mono<Void> remove(String id) {
        return Mono.fromRunnable(() -> {
            try { Files.deleteIfExists(path(id)); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Resource> resource(String id) {
        return Mono.fromCallable(() -> {
            Path path = path(id);
            if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw DocumentationService.notFound();
            return (Resource) new FileSystemResource(path);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public String signedUrl(String id, boolean admin) {
        long expires = Instant.now().plusSeconds(3600).getEpochSecond();
        return "/documentation/media/" + id + "?expires=" + expires + "&admin=" + admin + "&signature=" + sign(id, expires, admin);
    }

    public void verifySignature(String id, long expires, boolean admin, String signature) {
        long now = Instant.now().getEpochSecond();
        if (signature == null || signature.length() != 64 || expires <= now || expires > now + 3600
            || !MessageDigest.isEqual(sign(id, expires, admin).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII)))
            throw new ApiException(HttpStatus.FORBIDDEN, "DOCUMENTATION_MEDIA_EXPIRED", "附件链接已过期，请刷新文档");
    }

    private String sign(String id, long expires, boolean admin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + "\n" + expires + "\n" + admin).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private Path path(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw DocumentationService.notFound();
        return directory.resolve(id + ".bin");
    }
    static String filename(String value) {
        String name = value == null ? "" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        if (name.isBlank() || name.length() > 180 || !name.contains(".")) throw DocumentationService.invalid("附件名称无效");
        return name;
    }
    static boolean matches(String ext, byte[] b) {
        String hex = HexFormat.of().formatHex(b);
        String ascii = new String(b, StandardCharsets.ISO_8859_1);
        return switch (ext) {
            case "png" -> hex.startsWith("89504e470d0a1a0a");
            case "jpg", "jpeg" -> hex.startsWith("ffd8ff");
            case "gif" -> ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a");
            case "webp" -> ascii.startsWith("RIFF") && ascii.length() >= 12 && ascii.substring(8, 12).equals("WEBP");
            case "mp4" -> ascii.length() >= 12 && ascii.substring(4, 8).equals("ftyp");
            case "webm" -> hex.startsWith("1a45dfa3");
            case "pdf" -> ascii.startsWith("%PDF-");
            case "zip" -> hex.startsWith("504b0304") || hex.startsWith("504b0506");
            default -> true;
        };
    }
    public record Stored(String id, String filename, String contentType, long sizeBytes) { }
}
