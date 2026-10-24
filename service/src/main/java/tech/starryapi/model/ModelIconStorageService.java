package tech.starryapi.model;

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
public class ModelIconStorageService {

    private static final String PUBLIC_PATH = "/assets/model-icons/";

    private final Path iconDirectory;
    private final long maxIconBytes;

    public ModelIconStorageService(
        @Value("${starry.models.icon-dir:${user.home}/.starry-api/model-icons}") String iconDirectory,
        @Value("${starry.models.max-icon-bytes:2097152}") long maxIconBytes
    ) {
        this.iconDirectory = Path.of(iconDirectory).toAbsolutePath().normalize();
        this.maxIconBytes = maxIconBytes;
    }

    public Mono<ModelDtos.ModelIconUploadResponse> store(Mono<FilePart> filePart) {
        return filePart.switchIfEmpty(Mono.error(invalidFile("Model icon file is required")))
            .flatMap(file -> Mono.fromCallable(this::createUploadFile)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(upload -> file.transferTo(upload)
                    .then(Mono.fromCallable(() -> storeUploadedFile(upload))
                        .subscribeOn(Schedulers.boundedElastic()))
                    .doFinally(signal -> deleteQuietly(upload))));
    }

    ModelDtos.ModelIconUploadResponse storeUploadedFile(Path upload) {
        try {
            long size = Files.size(upload);
            if (size <= 0) {
                throw invalidFile("Model icon file is empty");
            }
            if (size > maxIconBytes) {
                throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "MODEL_ICON_TOO_LARGE",
                    "Model icon exceeds the configured size limit"
                );
            }

            ImageType imageType = detectImageType(upload);
            String fileName = UUID.randomUUID() + imageType.extension;
            Path target = iconDirectory.resolve(fileName);
            move(upload, target);
            return new ModelDtos.ModelIconUploadResponse(PUBLIC_PATH + fileName, fileName, size);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MODEL_ICON_STORAGE_FAILED",
                "Unable to store model icon"
            );
        }
    }

    private Path createUploadFile() throws IOException {
        Files.createDirectories(iconDirectory);
        return Files.createTempFile(iconDirectory, ".upload-", ".tmp");
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
        throw invalidFile("Only PNG, JPEG, and WebP model icons are supported");
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
            // Temporary upload cleanup is best effort.
        }
    }

    private ApiException invalidFile(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ICON_INVALID", message);
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
