package tech.starryapi.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;

@Service
public class BrandingStorageService {

    static final String PUBLIC_PATH = "/assets/branding/";

    private final Path brandingDirectory;
    private final long maxLogoBytes;
    private final long maxBackgroundBytes;

    public BrandingStorageService(
        @Value("${starry.appearance.branding-dir:${user.home}/.starry-api/branding}") String brandingDirectory,
        @Value("${starry.appearance.max-logo-bytes:8388608}") long maxLogoBytes,
        @Value("${starry.appearance.max-background-bytes:16777216}") long maxBackgroundBytes
    ) {
        this.brandingDirectory = Path.of(brandingDirectory).toAbsolutePath().normalize();
        this.maxLogoBytes = maxLogoBytes;
        this.maxBackgroundBytes = maxBackgroundBytes;
    }

    public Path directory() {
        return brandingDirectory;
    }

    public Mono<AppearanceDtos.BrandingUploadResponse> store(String kind, Mono<FilePart> filePart) {
        long limit = "background".equals(kind) ? maxBackgroundBytes : maxLogoBytes;
        return filePart.switchIfEmpty(Mono.error(invalidFile("Branding file is required")))
            .flatMap(file -> Mono.fromCallable(this::createUploadFile)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(upload -> file.transferTo(upload)
                    .then(Mono.fromCallable(() -> storeUploadedFile(kind, upload, limit))
                        .subscribeOn(Schedulers.boundedElastic()))
                    .doFinally(signal -> deleteQuietly(upload))));
    }

    AppearanceDtos.BrandingUploadResponse storeUploadedFile(String kind, Path upload, long maxBytes) {
        try {
            long size = Files.size(upload);
            if (size <= 0) {
                throw invalidFile("Branding file is empty");
            }
            if (size > maxBytes) {
                throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "BRANDING_FILE_TOO_LARGE",
                    "Branding file exceeds the configured size limit"
                );
            }
            ImageType imageType = detectImageType(upload);
            String fileName = kind + "-" + UUID.randomUUID() + imageType.extension;
            Path target = brandingDirectory.resolve(fileName);
            Files.createDirectories(brandingDirectory);
            move(upload, target);
            return new AppearanceDtos.BrandingUploadResponse(PUBLIC_PATH + fileName, fileName, size, kind);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "BRANDING_STORAGE_FAILED",
                "Unable to store branding file"
            );
        }
    }

    public void deletePublicUrl(String url) {
        Path stored = resolveStored(url);
        if (stored != null) {
            deleteQuietly(stored);
        }
    }

    Path resolveStored(String url) {
        if (url == null || !url.startsWith(PUBLIC_PATH)) {
            return null;
        }
        String fileName = url.substring(PUBLIC_PATH.length());
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return null;
        }
        Path resolved = brandingDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(brandingDirectory)) {
            return null;
        }
        return resolved;
    }

    private Path createUploadFile() throws IOException {
        Files.createDirectories(brandingDirectory);
        return Files.createTempFile(brandingDirectory, ".upload-", ".tmp");
    }

    private ImageType detectImageType(Path upload) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(upload)) {
            header = input.readNBytes(12);
        }
        if (matches(header, new int[] { 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a }, 0)) {
            return ImageType.PNG;
        }
        if (matches(header, new int[] { 0xff, 0xd8, 0xff }, 0)) {
            return ImageType.JPEG;
        }
        if (
            matches(header, new int[] { 0x52, 0x49, 0x46, 0x46 }, 0)
                && matches(header, new int[] { 0x57, 0x45, 0x42, 0x50 }, 8)
        ) {
            return ImageType.WEBP;
        }
        throw invalidFile("Only PNG, JPEG, and WebP branding images are supported");
    }

    private boolean matches(byte[] source, int[] expected, int offset) {
        if (source.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((source[offset + index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private ApiException invalidFile(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BRANDING_FILE_INVALID", message);
    }

    private enum ImageType {
        PNG(".png"),
        JPEG(".jpg"),
        WEBP(".webp");

        private final String extension;

        ImageType(String extension) {
            this.extension = extension;
        }
    }
}
