package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.starryapi.common.ApiException;

class BrandingStorageServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storesPngUnderBrandingPublicPath() throws Exception {
        BrandingStorageService service = new BrandingStorageService(tempDirectory.toString(), 1024, 2048);
        Path upload = tempDirectory.resolve("upload.bin");
        Files.write(upload, new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
        });

        AppearanceDtos.BrandingUploadResponse response = service.storeUploadedFile("logo", upload, 1024);

        assertThat(response.kind()).isEqualTo("logo");
        assertThat(response.fileName()).startsWith("logo-").endsWith(".png");
        assertThat(response.url()).isEqualTo("/assets/branding/" + response.fileName());
        assertThat(tempDirectory.resolve(response.fileName())).exists();
    }

    @Test
    void rejectsSvgAsBrandingImage() throws Exception {
        BrandingStorageService service = new BrandingStorageService(tempDirectory.toString(), 1024, 2048);
        Path upload = tempDirectory.resolve("fake.png");
        Files.writeString(upload, "<svg onload=alert(1)></svg>");

        assertThatThrownBy(() -> service.storeUploadedFile("logo", upload, 1024))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("BRANDING_FILE_INVALID")
            );
    }

    @Test
    void ignoresPathTraversalWhenDeleting() {
        BrandingStorageService service = new BrandingStorageService(tempDirectory.toString(), 1024, 2048);

        assertThat(service.resolveStored("/assets/branding/../secret.png")).isNull();
        assertThat(service.resolveStored("/assets/model-icons/x.png")).isNull();
        assertThat(service.resolveStored("/assets/branding/logo-ok.png"))
            .isEqualTo(tempDirectory.resolve("logo-ok.png"));
    }
}
