package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.starryapi.common.ApiException;

class ModelIconStorageServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storesPngUsingGeneratedPublicName() throws Exception {
        ModelIconStorageService service = new ModelIconStorageService(tempDirectory.toString(), 1024);
        Path upload = tempDirectory.resolve("logo.svg");
        Files.write(upload, new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
        });

        ModelDtos.ModelIconUploadResponse response = service.storeUploadedFile(upload);

        assertThat(response.fileName()).endsWith(".png").doesNotContain("logo");
        assertThat(response.url()).isEqualTo("/assets/model-icons/" + response.fileName());
        assertThat(response.sizeBytes()).isEqualTo(9);
        assertThat(tempDirectory.resolve(response.fileName())).exists();
        assertThat(upload).doesNotExist();
    }

    @Test
    void rejectsFilesWithoutSupportedImageSignature() throws Exception {
        ModelIconStorageService service = new ModelIconStorageService(tempDirectory.toString(), 1024);
        Path upload = tempDirectory.resolve("fake.png");
        Files.writeString(upload, "<svg onload=alert(1)></svg>");

        assertThatThrownBy(() -> service.storeUploadedFile(upload))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("MODEL_ICON_INVALID")
            );
    }

    @Test
    void rejectsFilesOverConfiguredLimit() throws Exception {
        ModelIconStorageService service = new ModelIconStorageService(tempDirectory.toString(), 8);
        Path upload = tempDirectory.resolve("large.png");
        Files.write(upload, new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
        });

        assertThatThrownBy(() -> service.storeUploadedFile(upload))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("MODEL_ICON_TOO_LARGE")
            );
    }
}
